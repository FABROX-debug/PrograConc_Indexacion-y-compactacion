package pcp.p2;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Un nodo (computadora) del cluster, representado por un Thread que maneja un
 * pool de tantos hilos como nucleos tiene el equipo.
 *  1) Recibe sus particiones: las copia por trozos (RandomAccessFile) desde el
 *     repositorio a su disco local (cluster/nodoX), respetando su capacidad.
 *  2) Procesa los bloques de la cola con sus hilos (seek + lectura por bloques).
 *  3) Acumula los indicadores por region en su archivo resultados.dat y anota
 *     las operaciones sospechosas en sospechosas.dat.
 */
public final class NodoCluster extends Thread {

    private static final int TROZO_COPIA = 4 * 1024 * 1024;

    final ConfigCluster cluster;
    final ConfigCluster.NodoConfig cfg;
    final int indice;
    final List<Balanceador.Particion> particiones;
    private final ColaBloques cola;
    private final long t0;

    // metricas (se leen despues de join)
    long bytesAsignados;
    long tTransferMs, tTotalMs;
    final AtomicLong bytesProcesados = new AtomicLong();
    final AtomicInteger bloquesPropios = new AtomicInteger(), bloquesRobados = new AtomicInteger();
    Throwable error;

    private RandomAccessFile resultados, sospechosas;

    public NodoCluster(ConfigCluster cluster, int indice, List<Balanceador.Particion> particiones, ColaBloques cola, long t0) {
        super("Nodo" + (indice + 1) + "-" + cluster.nodos.get(indice).nombre());
        this.cluster = cluster;
        this.cfg = cluster.nodos.get(indice);
        this.indice = indice;
        this.particiones = particiones;
        this.cola = cola;
        this.t0 = t0;
    }

    @Override
    public void run() {
        AtomicInteger nHilo = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(cfg.hilos(),
                r -> new Thread(r, getName() + "-h" + nHilo.incrementAndGet()));
        try {
            limpiarDirectorio(cfg.dir().toFile());

            // 1) Transferencia de particiones al disco local
            List<Future<?>> tareas = new ArrayList<>();
            for (Balanceador.Particion p : particiones) {
                bytesAsignados += p.bytes();
                if (bytesAsignados > cfg.discoBytes())
                    throw new IllegalStateException(getName() + ": disco insuficiente");
                Path origen = cluster.repositorio.resolve(Regiones.NOMBRE[p.region()] + ".dat");
                Path destino = cfg.dir().resolve(nombreLocal(p.region(), p.ini()));
                try (RandomAccessFile d = Formato.abrirNuevo(destino)) {
                    d.setLength(p.bytes());
                }
                for (long off = 0; off < p.bytes(); off += TROZO_COPIA) {
                    long o = off, len = Math.min(TROZO_COPIA, p.bytes() - off);
                    tareas.add(pool.submit(() -> copiar(origen, p.ini() * Formato.TAM_REG + o, destino, o, len)));
                }
            }
            esperar(tareas);
            tTransferMs = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("  [t=%6d ms] %-22s recibio %3d particiones (%.1f MB)%n",
                    tTransferMs, getName(), particiones.size(), Formato.mb(bytesAsignados));

            resultados = Formato.abrirNuevo(cfg.dir().resolve("resultados.dat"));
            Acumulador.inicializar(resultados, Regiones.TOTAL);
            sospechosas = Formato.abrirNuevo(cfg.dir().resolve("sospechosas.dat"));
            cola.marcarListo(indice);

            // 2) Procesamiento con los hilos del nodo
            tareas.clear();
            for (int h = 0; h < cfg.hilos(); h++)
                tareas.add(pool.submit(() -> { trabajar(); return null; }));
            esperar(tareas);
        } catch (Throwable e) {
            error = e;
            cola.marcarListo(indice);
        } finally {
            pool.shutdownNow();
            cerrar(resultados);
            cerrar(sospechosas);
            tTotalMs = (System.nanoTime() - t0) / 1_000_000;
        }
        System.out.printf("  [t=%6d ms] %-22s termino: %.1f MB procesados, bloques propios=%d robados=%d%n",
                tTotalMs, getName(), Formato.mb(bytesProcesados.get()), bloquesPropios.get(), bloquesRobados.get());
    }

    /** Ciclo de un hilo del nodo: toma bloques de la cola hasta que no haya mas. */
    private void trabajar() throws IOException, InterruptedException {
        Acumulador.Huella huella = new Acumulador.Huella();
        byte[] buf = new byte[Formato.BLOQUE_IO * Formato.TAM_REG];
        ByteBuffer bb = ByteBuffer.wrap(buf);
        ColaBloques.Bloque b;
        while ((b = cola.tomar(indice)) != null) {
            // el bloque esta en el disco de su propietario (si fue robado, es una lectura remota)
            Path archivo = cluster.nodos.get(b.propietario()).dir().resolve(nombreLocal(b.region(), b.iniParticion()));
            Acumulador acu = new Acumulador();
            try (RandomAccessFile raf = new RandomAccessFile(archivo.toFile(), "r")) {
                for (long desde = b.desde(); desde < b.hasta(); desde += Formato.BLOQUE_IO) {
                    int cant = (int) Math.min(Formato.BLOQUE_IO, b.hasta() - desde);
                    raf.seek((desde - b.iniParticion()) * Formato.TAM_REG);
                    raf.readFully(buf, 0, cant * Formato.TAM_REG);
                    for (int k = 0; k < cant; k++) {
                        int off = k * Formato.TAM_REG;
                        if (acu.agregar(bb, buf, off, cluster.umbralCentimos, huella))
                            anotarSospechosa(b.region(), desde + k, bb.getLong(off), Math.round(bb.getDouble(off + 24) * 100));
                    }
                }
            }
            synchronized (resultados) {
                acu.acumularEn(resultados, (long) b.region() * Acumulador.TAM);
            }
            bytesProcesados.addAndGet(b.bytes());
            if (b.propietario() == indice) bloquesPropios.incrementAndGet();
            else bloquesRobados.incrementAndGet();
        }
    }

    private void anotarSospechosa(int region, long registro, long cuenta, long montoCent) throws IOException {
        synchronized (sospechosas) {
            sospechosas.seek(sospechosas.length());
            sospechosas.writeInt(region);
            sospechosas.writeLong(registro);
            sospechosas.writeLong(cuenta);
            sospechosas.writeLong(montoCent);
        }
    }

    private static Void copiar(Path origen, long posOrigen, Path destino, long posDestino, long len) throws IOException {
        byte[] buf = new byte[64 * 1024];
        try (RandomAccessFile in = new RandomAccessFile(origen.toFile(), "r");
             RandomAccessFile out = new RandomAccessFile(destino.toFile(), "rw")) {
            for (long hecho = 0; hecho < len; ) {
                int n = (int) Math.min(buf.length, len - hecho);
                in.seek(posOrigen + hecho);
                in.readFully(buf, 0, n);
                out.seek(posDestino + hecho);
                out.write(buf, 0, n);
                hecho += n;
            }
        }
        return null;
    }

    static String nombreLocal(int region, long ini) {
        return Regiones.NOMBRE[region] + "_" + ini + ".dat";
    }

    private static void esperar(List<Future<?>> tareas) throws InterruptedException, ExecutionException {
        for (Future<?> f : tareas) f.get();
    }

    private static void limpiarDirectorio(File dir) {
        File[] fs = dir.listFiles();
        if (fs != null)
            for (File f : fs)
                if (f.isFile()) f.delete();
        dir.mkdirs();
    }

    private static void cerrar(RandomAccessFile raf) {
        try {
            if (raf != null) raf.close();
        } catch (IOException ignored) {
        }
    }
}

package pcp.p2;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/** Version serial: un solo hilo recorre todo el repositorio region por region. */
public final class ProcesadorSerial {

    private ProcesadorSerial() { }

    /** Escribe cluster/resultados_serial.dat y devuelve el tiempo en ms. */
    public static long ejecutar(ConfigCluster cfg) throws IOException {
        System.out.println("== VERSION SERIAL (1 hilo, todo el repositorio) ==");
        long t0 = System.nanoTime();
        Acumulador.Huella huella = new Acumulador.Huella();
        byte[] buf = new byte[Formato.BLOQUE_IO * Formato.TAM_REG];
        ByteBuffer bb = ByteBuffer.wrap(buf);
        Path salida = cfg.dirCluster.resolve("resultados_serial.dat");
        try (RandomAccessFile out = Formato.abrirNuevo(salida)) {
            Acumulador.inicializar(out, Regiones.TOTAL);
            for (int r = 0; r < Regiones.TOTAL; r++) {
                Acumulador acu = new Acumulador();
                try (RandomAccessFile in = new RandomAccessFile(cfg.repositorio.resolve(Regiones.NOMBRE[r] + ".dat").toFile(), "r")) {
                    long n = in.length() / Formato.TAM_REG;
                    for (long desde = 0; desde < n; desde += Formato.BLOQUE_IO) {
                        int cant = (int) Math.min(Formato.BLOQUE_IO, n - desde);
                        in.seek(desde * Formato.TAM_REG);
                        in.readFully(buf, 0, cant * Formato.TAM_REG);
                        for (int k = 0; k < cant; k++)
                            acu.agregar(bb, buf, k * Formato.TAM_REG, cfg.umbralCentimos, huella);
                    }
                }
                acu.escribir(out, (long) r * Acumulador.TAM);
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        try (RandomAccessFile t = Formato.abrirNuevo(cfg.dirCluster.resolve("tiempo_serial.dat"))) {
            t.writeLong(ms);
        }
        System.out.println("  Tiempo serial: " + ms + " ms");
        try (RandomAccessFile txt = Formato.abrirNuevo(cfg.dirCluster.resolve("reporte_SERIAL.txt"))) {
            Formato.linea(txt, "RESULTADOS SERIAL - tiempo " + ms + " ms");
            Reporte.tablaRegiones(txt, salida);
        }
        return ms;
    }

    /** Tiempo serial guardado por la ultima ejecucion, o -1. */
    public static long tiempoGuardado(ConfigCluster cfg) {
        Path p = cfg.dirCluster.resolve("tiempo_serial.dat");
        if (!p.toFile().exists()) return -1;
        try (RandomAccessFile t = new RandomAccessFile(p.toFile(), "r")) {
            return t.readLong();
        } catch (IOException e) {
            return -1;
        }
    }
}

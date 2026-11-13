package lsdfs;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paralelismo por archivos regionales independientes.
 * Se crean H hilos trabajadores (idea de NodoCluster: cada uno es un Thread que lleva
 * sus propias metricas); todos toman archivos de una ColaArchivos compartida hasta
 * vaciarla. Cada archivo (y su .meta / .idx) lo procesa un solo hilo, por eso no hace
 * falta sincronizar el acceso a los archivos: solo la cola y el mapa de resultados.
 */
public final class EjecutorParalelo {

    /** Operacion que se aplica a un archivo regional (PACK o INDEX). */
    @FunctionalInterface
    public interface TareaArchivo<R> {
        R procesar(String region) throws Exception;
    }

    private EjecutorParalelo() { }

    /**
     * Ejecuta la tarea sobre todas las regiones con 'hilos' trabajadores.
     * Devuelve los resultados en el mismo orden que la version serial.
     * Si 'detalle' no es null, agrega una linea de metricas por hilo.
     */
    public static <R> List<R> ejecutar(Repositorio repo, int hilos, TareaArchivo<R> tarea, List<String> detalle)
            throws Exception {
        List<String> regiones = repo.regiones();
        ColaArchivos cola = new ColaArchivos(repo, regiones);
        Map<String, R> resultados = new ConcurrentHashMap<>();
        long t0 = System.nanoTime();

        List<Trabajador<R>> ts = new ArrayList<>();
        for (int i = 0; i < Math.max(1, hilos); i++) ts.add(new Trabajador<>("Hilo-" + (i + 1), cola, tarea, resultados, repo));
        for (Trabajador<R> t : ts) t.start();
        for (Trabajador<R> t : ts) t.join();

        for (Trabajador<R> t : ts) {
            if (t.error != null) throw new IllegalStateException("Fallo en " + t.getName() + ": " + t.error.getMessage(), t.error);
            if (detalle != null)
                detalle.add(String.format("  %-8s archivos=%2d  bytes=%12s  ocupado=%8.2f ms  termino en t=%8.2f ms  %s",
                        t.getName(), t.archivos.size(), Utilidades.kb(t.bytes), Utilidades.ms(t.nanosOcupado),
                        Utilidades.ms(t.finNs - t0), t.archivos));
        }

        List<R> ordenados = new ArrayList<>();
        for (String r : regiones) ordenados.add(resultados.get(r));
        return ordenados;
    }

    /** Hilo trabajador: toma archivos de la cola hasta que se vacia. */
    private static final class Trabajador<R> extends Thread {
        private final ColaArchivos cola;
        private final TareaArchivo<R> tarea;
        private final Map<String, R> resultados;
        private final Repositorio repo;
        final List<String> archivos = new ArrayList<>();
        long bytes, nanosOcupado, finNs;
        Throwable error;

        Trabajador(String nombre, ColaArchivos cola, TareaArchivo<R> tarea, Map<String, R> resultados, Repositorio repo) {
            super(nombre);
            this.cola = cola;
            this.tarea = tarea;
            this.resultados = resultados;
            this.repo = repo;
        }

        @Override
        public void run() {
            try {
                String region;
                while ((region = cola.tomar()) != null) {
                    long t = System.nanoTime();
                    bytes += Files.size(repo.datos(region));
                    resultados.put(region, tarea.procesar(region));
                    nanosOcupado += System.nanoTime() - t;
                    archivos.add(region);
                }
            } catch (Throwable e) {
                error = e;
            } finally {
                finNs = System.nanoTime();
            }
        }
    }
}

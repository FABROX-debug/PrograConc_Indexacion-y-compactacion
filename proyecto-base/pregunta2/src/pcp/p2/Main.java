package pcp.p2;

import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Pregunta 2 - Paralelismo orientado a datos: balanceo de carga en un cluster.
 * Uso: java -cp out pcp.p2.Main [serial | cluster [ESTRATEGIA] | comparar]
 * Al iniciar siempre genera el repositorio. Sin argumentos muestra un menu.
 * La configuracion se lee de cluster.properties.
 */
public final class Main {

    private static final Path CONFIG = Path.of("cluster.properties");
    private static final int FACTOR_DEFECTO = 300;   // movimientos por cada mil habitantes

    public static void main(String[] args) throws Exception {
        generarRepositorio();   // siempre se parte de un repositorio completo y conocido
        if (args.length > 0) {
            ejecutar(args[0], args.length > 1 ? args[1] : null);
            return;
        }
        Scanner sc = new Scanner(System.in);
        while (true) {
            System.out.println();
            System.out.println("=====================================================================");
            System.out.println(" PREGUNTA 2 - PARALELISMO ORIENTADO A DATOS (CLUSTER BANCARIO)");
            System.out.println(" Un banco procesa los movimientos de cuentas de las 25 regiones del Peru");
            System.out.println(" (repositorio/LIMA.dat ... TUMBES.dat). El trabajo se reparte entre 4");
            System.out.println(" computadoras de distinta capacidad definidas en " + CONFIG + ".");
            System.out.println("=====================================================================");
            System.out.println(" 1) Version SERIAL      -> 1 solo hilo procesa todas las regiones");
            System.out.println("    --- Version PARALELA: el cluster reparte las regiones entre los nodos ---");
            System.out.println(" 2) Cluster             -> usa la estrategia escrita en " + CONFIG);
            System.out.println(" 3) ROUND_ROBIN         -> reparte por turnos, SIN mirar la capacidad");
            System.out.println(" 4) PONDERADO           -> reparte segun capacidad (Lima al nodo mas potente)");
            System.out.println(" 5) DINAMICO            -> PONDERADO + el nodo que acaba antes ayuda a otros");
            System.out.println(" 6) Comparar todo       -> ejecuta 1,3,4,5 y muestra que estrategia balancea mejor");
            System.out.println(" 0) Salir");
            System.out.print("Opcion: ");
            if (!sc.hasNextLine()) return;
            switch (sc.nextLine().trim()) {
                case "1" -> ejecutar("serial", null);
                case "2" -> ejecutar("cluster", null);
                case "3" -> ejecutar("cluster", "ROUND_ROBIN");
                case "4" -> ejecutar("cluster", "PONDERADO");
                case "5" -> ejecutar("cluster", "DINAMICO");
                case "6" -> ejecutar("comparar", null);
                case "0" -> { return; }
                default -> System.out.println("Opcion invalida");
            }
        }
    }

    private static void ejecutar(String opcion, String arg) throws Exception {
        ConfigCluster cfg = new ConfigCluster(CONFIG);
        cfg.dirCluster.toFile().mkdirs();
        switch (opcion) {
            case "serial" -> ProcesadorSerial.ejecutar(cfg);
            case "cluster" -> {
                if (ProcesadorSerial.tiempoGuardado(cfg) < 0) {
                    System.out.println("(Primero se ejecuta la version serial para tener la referencia de tiempo y resultados)");
                    ProcesadorSerial.ejecutar(cfg);
                    System.out.println();
                }
                Maestro.ejecutar(cfg, arg == null ? cfg.estrategia : Estrategia.valueOf(arg.toUpperCase()));
            }
            case "comparar" -> {
                long ts = ProcesadorSerial.ejecutar(cfg);
                List<Maestro.Resumen> rs = new ArrayList<>();
                for (Estrategia e : Estrategia.values()) {
                    System.out.println();
                    rs.add(Maestro.ejecutar(cfg, e));
                }
                Path p = cfg.dirCluster.resolve("comparacion.txt");
                try (RandomAccessFile txt = Formato.abrirNuevo(p)) {
                    String cab = String.format("%-12s %12s %12s %9s %11s %10s",
                            "Estrategia", "Makespan ms", "Desbalance", "Speedup", "Eficiencia", "Correcto");
                    System.out.println();
                    System.out.println("== COMPARACION DE ESTRATEGIAS (T serial = " + ts + " ms, " + cfg.totalHilos() + " hilos en el cluster) ==");
                    System.out.println(cab);
                    Formato.linea(txt, "COMPARACION DE ESTRATEGIAS DE BALANCEO - T serial = " + ts + " ms");
                    Formato.linea(txt, cab);
                    for (Maestro.Resumen r : rs) {
                        String f = String.format("%-12s %12d %12.3f %9.2f %10.1f%% %10s", r.estrategia(), r.makespanMs(),
                                r.desbalance(), r.speedup(), r.eficiencia() * 100, r.coincide() ? "SI" : "NO");
                        System.out.println(f);
                        Formato.linea(txt, f);
                    }
                }
                System.out.println("Reporte: " + p);
            }
            default -> System.out.println("Opcion desconocida: " + opcion);
        }
    }

    /**
     * Genera el repositorio al iniciar y borra los resultados de ejecuciones
     * anteriores, para que todo se compare contra los datos recien creados.
     */
    private static void generarRepositorio() throws Exception {
        ConfigCluster cfg = new ConfigCluster(CONFIG);
        System.out.println("Generando repositorio " + cfg.repositorio + " (" + FACTOR_DEFECTO + " movimientos por mil habitantes)...");
        long t = System.nanoTime();
        GeneradorRepositorio.generar(cfg.repositorio, FACTOR_DEFECTO);
        System.out.printf("Repositorio listo en %d ms%n", (System.nanoTime() - t) / 1_000_000);
        cfg.dirCluster.resolve("tiempo_serial.dat").toFile().delete();
        cfg.dirCluster.resolve("resultados_serial.dat").toFile().delete();
    }
}

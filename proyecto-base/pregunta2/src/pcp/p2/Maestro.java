package pcp.p2;

import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Nodo maestro: lee la configuracion, calcula la asignacion con el balanceador,
 * lanza los nodos (threads), espera a que terminen, consolida los resultados de
 * cada disco local y evalua la calidad del balanceo.
 */
public final class Maestro {

    /** Metricas de evaluacion de una ejecucion del cluster. */
    public record Resumen(Estrategia estrategia, long makespanMs, double desbalance,
                          double speedup, double eficiencia, boolean coincide) { }

    private Maestro() { }

    public static Resumen ejecutar(ConfigCluster cfg, Estrategia e) throws Exception {
        System.out.printf("== CLUSTER %s | estrategia %s | %d nodos, %d hilos ==%n",
                cfg.nombre, e, cfg.nodos.size(), cfg.totalHilos());
        List<List<Balanceador.Particion>> plan = Balanceador.planificar(cfg, e);
        imprimirPlan(cfg, plan);

        Path dir = cfg.dirCluster;
        List<NodoCluster> nodos = new ArrayList<>();
        long t0;
        try (ColaBloques cola = new ColaBloques(dir.resolve("cola_bloques_" + e + ".dat"), plan,
                cfg.bloqueRegistros, e == Estrategia.DINAMICO)) {
            t0 = System.nanoTime();
            for (int k = 0; k < cfg.nodos.size(); k++)
                nodos.add(new NodoCluster(cfg, k, plan.get(k), cola, t0));
            for (NodoCluster n : nodos) n.start();
            for (NodoCluster n : nodos) n.join();
        }
        long makespan = (System.nanoTime() - t0) / 1_000_000;
        for (NodoCluster n : nodos)
            if (n.error != null) throw new IllegalStateException("Fallo " + n.getName(), n.error);

        // Consolidacion: suma los resultados.dat de todos los discos locales
        Path consolidado = dir.resolve("resultados_" + e + ".dat");
        long sospechosas = 0;
        try (RandomAccessFile out = Formato.abrirNuevo(consolidado)) {
            Acumulador.inicializar(out, Regiones.TOTAL);
            for (NodoCluster n : nodos) {
                try (RandomAccessFile in = new RandomAccessFile(n.cfg.dir().resolve("resultados.dat").toFile(), "r")) {
                    for (int r = 0; r < Regiones.TOTAL; r++) {
                        Acumulador a = new Acumulador();
                        a.leer(in, (long) r * Acumulador.TAM);
                        a.acumularEn(out, (long) r * Acumulador.TAM);
                    }
                }
                sospechosas += n.cfg.dir().resolve("sospechosas.dat").toFile().length() / Formato.TAM_SOSP;
            }
        }

        // Metricas de evaluacion
        double suma = 0, max = 0;
        for (NodoCluster n : nodos) {
            suma += n.tTotalMs;
            max = Math.max(max, n.tTotalMs);
        }
        double desbalance = max / (suma / nodos.size());
        long tSerial = ProcesadorSerial.tiempoGuardado(cfg);
        double speedup = tSerial > 0 ? (double) tSerial / makespan : Double.NaN;
        double eficiencia = speedup / cfg.totalHilos();
        Path serial = dir.resolve("resultados_serial.dat");
        boolean coincide = Reporte.iguales(serial, consolidado);

        Path txtPath = dir.resolve("reporte_" + e + ".txt");
        try (RandomAccessFile txt = Formato.abrirNuevo(txtPath)) {
            Formato.linea(txt, "CLUSTER " + cfg.nombre + " - ESTRATEGIA " + e);
            Formato.linea(txt, "");
            Formato.linea(txt, "METRICAS POR NODO");
            Formato.linea(txt, String.format("%-26s %5s %9s %9s %7s %8s %8s %10s %10s %8s",
                    "Nodo", "Hilos", "Asig.MB", "Proc.MB", "Disco%", "BlqProp", "BlqRob", "T.recep", "T.total", "MB/s"));
            for (NodoCluster n : nodos) {
                double mbProc = Formato.mb(n.bytesProcesados.get());
                Formato.linea(txt, String.format("%-26s %5d %9.1f %9.1f %6.1f%% %8d %8d %8d ms %8d ms %8.1f",
                        n.getName(), n.cfg.hilos(), Formato.mb(n.bytesAsignados), mbProc,
                        100.0 * n.bytesAsignados / n.cfg.discoBytes(), n.bloquesPropios.get(), n.bloquesRobados.get(),
                        n.tTransferMs, n.tTotalMs, mbProc / Math.max(1, n.tTotalMs) * 1000));
            }
            Formato.linea(txt, "");
            Formato.linea(txt, "EVALUACION DEL BALANCEO");
            Formato.linea(txt, String.format("  Makespan (nodo mas lento)      : %d ms", makespan));
            Formato.linea(txt, String.format("  Indice de desbalance Tmax/Tprom: %.3f   (1.000 = ideal)", desbalance));
            Formato.linea(txt, String.format("  Speedup (T serial %d ms)       : %.2f", tSerial, speedup));
            Formato.linea(txt, String.format("  Eficiencia (speedup/%d hilos)  : %.1f%%", cfg.totalHilos(), eficiencia * 100));
            Formato.linea(txt, String.format("  Operaciones sospechosas (>= S/ %,.2f) registradas: %d", cfg.umbralCentimos / 100.0, sospechosas));
            Formato.linea(txt, "  Resultados identicos a la version serial: " + (coincide ? "SI" : "NO (ejecute la version serial)"));
            Formato.linea(txt, "");
            Formato.linea(txt, "ASIGNACION DE REGIONES");
            for (int k = 0; k < plan.size(); k++)
                Formato.linea(txt, "  " + nodos.get(k).getName() + ": " + describir(cfg, plan.get(k)));
            Formato.linea(txt, "");
            Formato.linea(txt, "RESULTADOS CONSOLIDADOS POR REGION");
            Reporte.tablaRegiones(txt, consolidado);
        }

        System.out.printf("  Makespan=%d ms  Desbalance=%.3f  Speedup=%.2f  Eficiencia=%.1f%%  Coincide con serial=%s%n",
                makespan, desbalance, speedup, eficiencia * 100, coincide ? "SI" : "NO");
        System.out.println("  Reporte: " + txtPath);
        return new Resumen(e, makespan, desbalance, speedup, eficiencia, coincide);
    }

    private static void imprimirPlan(ConfigCluster cfg, List<List<Balanceador.Particion>> plan) {
        System.out.println("  Asignacion:");
        for (int k = 0; k < plan.size(); k++) {
            ConfigCluster.NodoConfig n = cfg.nodos.get(k);
            long bytes = plan.get(k).stream().mapToLong(Balanceador.Particion::bytes).sum();
            System.out.printf("   %-14s (%d hilos, disco %4.0f MB) <- %6.1f MB : %s%n",
                    n.nombre(), n.hilos(), Formato.mb(n.discoBytes()), Formato.mb(bytes), describir(cfg, plan.get(k)));
        }
    }

    private static String describir(ConfigCluster cfg, List<Balanceador.Particion> ps) {
        StringBuilder sb = new StringBuilder();
        for (Balanceador.Particion p : ps) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(Regiones.NOMBRE[p.region()]);
            long total = Formato.registros(cfg.repositorio.resolve(Regiones.NOMBRE[p.region()] + ".dat"));
            if (p.registros() < total)   // region partida entre varios nodos
                sb.append(String.format("[%d-%d)", p.ini(), p.fin()));
        }
        return sb.toString();
    }
}

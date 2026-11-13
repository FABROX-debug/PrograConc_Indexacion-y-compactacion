package lsdfs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * LSDFS_Bancario - PACK() e INDEX() sobre un repositorio de movimientos bancarios regionales.
 *
 * Uso:  java -cp out lsdfs.Main                 (menu interactivo)
 *       java -cp out lsdfs.Main <comando> [...]  (ver ayuda())
 *
 * Los datos se guardan en ./datos y se conservan entre ejecuciones: el programa
 * NUNCA los regenera por su cuenta, solo con la opcion "Generar datos".
 */
public final class Main {

    static final Path DATOS = Path.of("datos");
    static final Path TRABAJO = Path.of("trabajo");
    static final Path RESULTADOS = Path.of("resultados");
    static final long SEMILLA = 2026;
    static final int HILOS_DEFECTO = Math.min(Runtime.getRuntime().availableProcessors(), Regiones.TOTAL);
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("America/Lima"));

    private static final Repositorio REPO = new Repositorio(DATOS);
    private static Scanner sc;

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            comando(args);
            return;
        }
        sc = new Scanner(System.in);
        while (true) {
            System.out.println();
            System.out.println("==================================================================");
            System.out.println(" LSDFS BANCARIO - Indexacion y Compactacion de Datos a Gran Escala");
            System.out.println("==================================================================");
            System.out.println(" " + lineaEstado());
            System.out.println("------------------------------------------------------------------");
            System.out.println(" 1) Generar datos");
            System.out.println(" 2) Marcar registros para eliminacion");
            System.out.println(" 3) Ejecutar PACK serial");
            System.out.println(" 4) Ejecutar PACK paralelo  (archivos regionales en simultaneo)");
            System.out.println(" 5) Ejecutar INDEX serial");
            System.out.println(" 6) Ejecutar INDEX paralelo (archivos regionales en simultaneo)");
            System.out.println(" 7) Buscar movimientos por cuenta (busqueda binaria en el indice)");
            System.out.println(" 8) Comparar tiempos serial vs paralelo (sobre copias)");
            System.out.println(" 9) Ver estado y metadata");
            System.out.println(" 0) Salir");
            String op = preguntar("Opcion: ");
            if (op == null || op.equals("0")) return;
            try {
                switch (op) {
                    case "1" -> menuGenerar();
                    case "2" -> menuMarcar();
                    case "3" -> packSerial();
                    case "4" -> packParalelo(leerEntero("Hilos [" + HILOS_DEFECTO + "]: ", HILOS_DEFECTO));
                    case "5" -> indexSerial();
                    case "6" -> indexParalelo(leerEntero("Hilos [" + HILOS_DEFECTO + "]: ", HILOS_DEFECTO));
                    case "7" -> buscar(leerEntero("Numero de cuenta (ej. 10000005): ", -1));
                    case "8" -> comparar(leerEntero("Hilos [" + HILOS_DEFECTO + "]: ", HILOS_DEFECTO),
                            (int) leerEntero("Repeticiones [3]: ", 3));
                    case "9" -> estado();
                    default -> System.out.println("Opcion invalida");
                }
            } catch (NumberFormatException e) {
                System.out.println("ERROR: se esperaba un numero.");
            } catch (Exception e) {
                System.out.println("ERROR: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ comandos

    private static void comando(String[] a) throws Exception {
        switch (a[0]) {
            case "generar" -> {
                if (a.length < 2) { ayuda(); return; }
                if (REPO.existe() && !(a.length > 2 && a[2].equals("--forzar"))) {
                    System.out.println("Ya existe un repositorio en " + DATOS.toAbsolutePath()
                            + ". No se regenera. Use 'generar N --forzar' para reemplazarlo.");
                    return;
                }
                generar(Long.parseLong(a[1]));
            }
            case "marcar" -> marcarPorcentaje(Double.parseDouble(a[1]));
            case "marcar-cuenta" -> marcarCuenta(Long.parseLong(a[1]));
            case "pack-serial" -> packSerial();
            case "pack-paralelo" -> packParalelo(a.length > 1 ? Integer.parseInt(a[1]) : HILOS_DEFECTO);
            case "index-serial" -> indexSerial();
            case "index-paralelo" -> indexParalelo(a.length > 1 ? Integer.parseInt(a[1]) : HILOS_DEFECTO);
            case "buscar" -> buscar(Long.parseLong(a[1]));
            case "comparar" -> comparar(a.length > 1 ? Integer.parseInt(a[1]) : HILOS_DEFECTO,
                    a.length > 2 ? Integer.parseInt(a[2]) : 3);
            case "estado" -> estado();
            default -> ayuda();
        }
    }

    private static void ayuda() {
        System.out.println("Comandos: generar N [--forzar] | marcar PORCENTAJE | marcar-cuenta CUENTA | pack-serial |");
        System.out.println("          pack-paralelo [HILOS] | index-serial | index-paralelo [HILOS] | buscar CUENTA |");
        System.out.println("          comparar [HILOS] [REPETICIONES] | estado");
        System.out.println("Validacion automatica: java -cp out lsdfs.Pruebas [N1 N2 ...]");
    }

    // ------------------------------------------------------------------ operaciones

    private static void menuGenerar() throws Exception {
        if (REPO.existe()) {
            Repositorio.Resumen r = REPO.resumen();
            String s = preguntar("Ya existe un repositorio con " + r.total() + " registros. Reemplazarlo? (s/n): ");
            if (s == null || !s.equalsIgnoreCase("s")) {
                System.out.println("Se conserva el repositorio actual.");
                return;
            }
        }
        generar(leerEntero("Cantidad de registros [1000]: ", 1000));
    }

    private static void generar(long n) throws Exception {
        if (n < 0) throw new IllegalArgumentException("cantidad invalida");
        long ns = GeneradorDatos.generar(REPO, n, SEMILLA);
        System.out.printf("Repositorio generado en %s: %,d registros en %d archivos (%s) en %.2f ms%n",
                DATOS.toAbsolutePath(), n, REPO.regiones().size(), Utilidades.kb(REPO.resumen().bytes()), Utilidades.ms(ns));
    }

    private static void menuMarcar() throws Exception {
        exigirRepositorio();
        System.out.println(" a) Marcar un porcentaje aleatorio de registros activos");
        System.out.println(" b) Marcar todos los movimientos de una cuenta");
        String s = preguntar("Opcion: ");
        if ("a".equalsIgnoreCase(s)) {
            String p = preguntar("Porcentaje [20]: ");
            marcarPorcentaje(p == null || p.isBlank() ? 20 : Double.parseDouble(p.trim()));
        } else if ("b".equalsIgnoreCase(s)) {
            marcarCuenta(leerEntero("Numero de cuenta: ", -1));
        }
    }

    private static void marcarPorcentaje(double pct) throws Exception {
        exigirRepositorio();
        long n = Marcador.marcarPorcentaje(REPO, pct, System.nanoTime());
        System.out.printf("Se marcaron %,d registros para eliminacion (%.1f%% de los activos).%n", n, pct);
        System.out.println(" " + lineaEstado());
    }

    private static void marcarCuenta(long cuenta) throws Exception {
        exigirRepositorio();
        long n = Marcador.marcarCuenta(REPO, cuenta);
        System.out.printf("Se marcaron %,d movimientos de la cuenta %d.%n", n, cuenta);
    }

    private static void packSerial() throws Exception {
        exigirRepositorio();
        long t = System.nanoTime();
        List<Pack.Resultado> rs = Pack.serial(REPO);
        imprimirPack("PACK SERIAL (1 hilo)", rs, System.nanoTime() - t, null);
    }

    private static void packParalelo(long hilos) throws Exception {
        exigirRepositorio();
        List<String> detalle = new ArrayList<>();
        long t = System.nanoTime();
        List<Pack.Resultado> rs = Pack.paralelo(REPO, (int) hilos, detalle);
        imprimirPack("PACK PARALELO (" + hilos + " hilos, un archivo regional por hilo a la vez)", rs, System.nanoTime() - t, detalle);
    }

    private static void imprimirPack(String titulo, List<Pack.Resultado> rs, long ns, List<String> detalle) {
        System.out.println("== " + titulo + " ==");
        System.out.printf("%-14s %8s %10s %8s %13s %13s %s%n", "Archivo", "Leidos", "Eliminados", "Activos", "Antes", "Despues", "Reemplazado");
        long l = 0, e = 0, a = 0, ba = 0, bd = 0;
        for (Pack.Resultado r : rs) {
            System.out.printf("%-14s %,8d %,10d %,8d %13s %13s %s%n", r.region(), r.leidos(), r.eliminados(), r.activos(),
                    Utilidades.kb(r.bytesAntes()), Utilidades.kb(r.bytesDespues()), r.reemplazado() ? "SI" : "no (sin marcados)");
            l += r.leidos(); e += r.eliminados(); a += r.activos(); ba += r.bytesAntes(); bd += r.bytesDespues();
        }
        System.out.printf("%-14s %,8d %,10d %,8d %13s %13s%n", "TOTAL", l, e, a, Utilidades.kb(ba), Utilidades.kb(bd));
        if (detalle != null) detalle.forEach(System.out::println);
        System.out.printf("Tiempo: %.2f ms. Espacio liberado: %s.%n", Utilidades.ms(ns), Utilidades.kb(ba - bd));
        if (e > 0) System.out.println("Los indices de los archivos compactados quedaron DESACTUALIZADOS (ejecute INDEX; la busqueda los reconstruye si hace falta).");
    }

    private static void indexSerial() throws Exception {
        exigirRepositorio();
        long t = System.nanoTime();
        List<Indexador.Resultado> rs = Indexador.serial(REPO);
        imprimirIndex("INDEX SERIAL (1 hilo)", rs, System.nanoTime() - t, null);
    }

    private static void indexParalelo(long hilos) throws Exception {
        exigirRepositorio();
        List<String> detalle = new ArrayList<>();
        long t = System.nanoTime();
        List<Indexador.Resultado> rs = Indexador.paralelo(REPO, (int) hilos, detalle);
        imprimirIndex("INDEX PARALELO (" + hilos + " hilos)", rs, System.nanoTime() - t, detalle);
    }

    private static void imprimirIndex(String titulo, List<Indexador.Resultado> rs, long ns, List<String> detalle) {
        System.out.println("== " + titulo + " ==");
        System.out.printf("%-14s %8s %9s %12s%n", "Archivo", "Leidos", "Entradas", "Indice");
        long l = 0, n = 0;
        for (Indexador.Resultado r : rs) {
            System.out.printf("%-14s %,8d %,9d %12s%n", r.region(), r.registrosLeidos(), r.entradas(), Utilidades.kb(r.bytesIndice()));
            l += r.registrosLeidos();
            n += r.entradas();
        }
        System.out.printf("%-14s %,8d %,9d   (los registros marcados no se indexan)%n", "TOTAL", l, n);
        if (detalle != null) detalle.forEach(System.out::println);
        System.out.printf("Tiempo: %.2f ms%n", Utilidades.ms(ns));
    }

    private static void buscar(long cuenta) throws Exception {
        exigirRepositorio();
        Buscador.Resultado r = Buscador.buscar(REPO, cuenta);
        if (!r.indicesReconstruidos().isEmpty())
            System.out.println("Indice desactualizado o inexistente: se reconstruyo " + r.indicesReconstruidos());
        int reg = Regiones.deCuenta(cuenta);
        System.out.printf("Cuenta %d (%s): %d movimientos activos, %d marcados para eliminar omitidos.%n",
                cuenta, reg >= 0 ? Regiones.NOMBRE[reg] : "region desconocida", r.movimientos().size(), r.marcadosOmitidos());
        System.out.printf("Busqueda binaria: %d lecturas del indice. Tiempo total: %.3f ms%n", r.pasosBinarios(), Utilidades.ms(r.nanos()));
        if (r.movimientos().isEmpty()) return;
        System.out.printf("%-14s %10s %-16s %-13s %-7s %14s  %-26s %s%n", "Archivo", "Offset", "Fecha", "Tipo", "Canal", "Monto S/", "Titular", "Concepto");
        for (Buscador.Movimiento m : r.movimientos()) {
            Registro g = m.registro();
            System.out.printf("%-14s %10d %-16s %-13s %-7s %14s  %-26s %s%n", m.region(), m.offset(),
                    FECHA.format(Instant.ofEpochMilli(g.fecha)), Registro.TIPO[g.tipo], Registro.CANAL[g.canal],
                    Utilidades.soles(g.montoCentimos), g.titular, g.concepto);
        }
    }

    private static void comparar(long hilos, int repeticiones) throws Exception {
        exigirRepositorio();
        if (REPO.resumen().marcados() == 0)
            System.out.println("AVISO: no hay registros marcados; PACK no tendra nada que eliminar (use la opcion 2).");
        Comparador.comparar(REPO, TRABAJO, (int) hilos, repeticiones, RESULTADOS.resolve("tiempos.txt"), "COMPARACION desde el menu");
    }

    private static void estado() throws Exception {
        exigirRepositorio();
        System.out.println("Repositorio: " + DATOS.toAbsolutePath());
        System.out.printf("%-14s %8s %8s %8s %12s %8s  %s%n", "Archivo", "Total", "Activos", "Marcados", "Bytes", "Version", "Indice");
        for (String r : REPO.regiones()) {
            Metadata m = REPO.cargarMeta(r);
            String idx = !Files.exists(REPO.indice(r)) ? "no existe"
                    : REPO.indiceVigente(r) ? "vigente (" + m.getLong(Metadata.IDX_ENTRADAS, 0) + " entradas)"
                    : "DESACTUALIZADO";
            System.out.printf("%-14s %,8d %,8d %,8d %12s %8d  %s%n", r, m.getLong(Metadata.TOTAL, 0), m.getLong(Metadata.ACTIVOS, 0),
                    m.getLong(Metadata.ELIMINADOS, 0), Utilidades.kb(m.getLong(Metadata.BYTES, 0)), m.getLong(Metadata.VERSION, 0), idx);
        }
        System.out.println(" " + lineaEstado());
        System.out.println("Metadata global (" + REPO.metaGlobal().getFileName() + "):");
        for (String l : Files.readAllLines(REPO.metaGlobal())) if (!l.startsWith("#")) System.out.println("   " + l);
    }

    // ------------------------------------------------------------------ auxiliares

    private static String lineaEstado() {
        try {
            if (!REPO.existe()) return "Repositorio vacio en " + DATOS + " -> use la opcion 1 para generar datos.";
            Repositorio.Resumen r = REPO.resumen();
            return String.format("Repositorio: %d archivos | %,d registros (%,d activos, %,d marcados) | %s | indices vigentes %d/%d",
                    r.archivos(), r.total(), r.activos(), r.marcados(), Utilidades.kb(r.bytes()), r.indicesVigentes(), r.archivos());
        } catch (Exception e) {
            return "No se pudo leer el repositorio: " + e.getMessage();
        }
    }

    private static void exigirRepositorio() {
        if (!REPO.existe()) throw new IllegalStateException("No hay repositorio en " + DATOS.toAbsolutePath() + ". Genere datos primero (opcion 1).");
    }

    private static String preguntar(String msg) {
        System.out.print(msg);
        return sc.hasNextLine() ? sc.nextLine().trim() : null;
    }

    private static long leerEntero(String msg, long def) {
        String s = preguntar(msg);
        if (s == null || s.isBlank()) {
            if (def < 0) throw new IllegalArgumentException("se requiere un valor");
            return def;
        }
        return Long.parseLong(s.replace(",", "").replace(".", ""));
    }
}

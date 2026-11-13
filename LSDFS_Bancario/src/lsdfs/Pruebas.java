package lsdfs;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Validacion automatica de PACK e INDEX (serial y paralelo).
 * Trabaja en ./pruebas/nN, nunca en ./datos.
 * Uso: java -cp out lsdfs.Pruebas [N1 N2 ...]   (por defecto 100 1000 10000)
 * Escribe resultados/validacion.txt con los chequeos y los tiempos medidos.
 */
public final class Pruebas {

    private static final List<String> salida = new ArrayList<>();
    private static int fallos = 0;

    public static void main(String[] args) throws Exception {
        long[] tamanos = args.length == 0 ? new long[]{100, 1_000, 10_000}
                : Arrays.stream(args).mapToLong(Long::parseLong).toArray();
        int hilos = Main.HILOS_DEFECTO;
        log("VALIDACION LSDFS_Bancario - " + LocalDateTime.now().withNano(0)
                + " | nucleos=" + Runtime.getRuntime().availableProcessors() + " | hilos paralelos=" + hilos
                + " | Java " + System.getProperty("java.version"));

        List<String> tiempos = new ArrayList<>();
        tiempos.add(String.format("%-8s %8s %13s %13s %13s %13s", "N", "Marcados", "PACK serial", "PACK paral.", "INDEX serial", "INDEX paral."));
        for (long n : tamanos) tiempos.add(probar(n, hilos));

        log("");
        log("TIEMPOS MEDIDOS (una ejecucion por tamano, en milisegundos; incluyen lectura/escritura de disco):");
        tiempos.forEach(Pruebas::log);
        log("");
        log(fallos == 0 ? "RESULTADO: TODOS LOS CHEQUEOS PASARON" : "RESULTADO: " + fallos + " CHEQUEOS FALLARON");

        Path informe = Main.RESULTADOS.resolve("validacion.txt");
        Files.createDirectories(informe.getParent());
        Files.write(informe, salida, StandardCharsets.UTF_8);
        System.out.println("Informe: " + informe.toAbsolutePath());
        if (fallos > 0) System.exit(1);
    }

    private static String probar(long n, int hilos) throws Exception {
        log("");
        log("================ N = " + n + " registros ================");
        Path base = Path.of("pruebas", "n" + n);
        Utilidades.borrarDirectorio(base);
        Repositorio orig = new Repositorio(base.resolve("original"));
        Repositorio ser = new Repositorio(base.resolve("serial"));
        Repositorio par = new Repositorio(base.resolve("paralelo"));

        // 1) generar y marcar 20 %
        GeneradorDatos.generar(orig, n, Main.SEMILLA);
        chequear("Se generaron exactamente " + n + " registros", contar(orig) == n);
        long marcados = Marcador.marcarPorcentaje(orig, 20, Main.SEMILLA);
        log("  Marcados para eliminar: " + marcados);

        // foto de lo esperado: registros activos (bytes exactos) por archivo, en orden
        Map<String, List<byte[]>> esperados = new HashMap<>();
        long activosEsperados = 0;
        for (String r : orig.regiones()) {
            List<byte[]> l = new ArrayList<>();
            try (ArchivoDatos.Lector in = new ArchivoDatos.Lector(orig.datos(r))) {
                while (in.siguiente()) if (Registro.esActivo(in.raw)) l.add(in.raw);
            }
            esperados.put(r, l);
            activosEsperados += l.size();
        }
        chequear("Metadata tras marcar: activos + marcados = total",
                orig.resumen().activos() == activosEsperados && orig.resumen().marcados() == marcados);

        // indice previo al PACK, para comprobar despues que PACK lo deja desactualizado
        Indexador.serial(orig);
        Utilidades.copiarDirectorio(orig.dir, ser.dir);
        Utilidades.copiarDirectorio(orig.dir, par.dir);

        // 2) PACK serial y paralelo
        long t = System.nanoTime();
        Pack.serial(ser);
        double tPs = Utilidades.ms(System.nanoTime() - t);
        t = System.nanoTime();
        Pack.paralelo(par, hilos, null);
        double tPp = Utilidades.ms(System.nanoTime() - t);

        for (Repositorio rep : List.of(ser, par)) {
            String q = rep == ser ? "serial" : "paralelo";
            boolean conserva = true, sinMarcados = true;
            long total = 0;
            for (String r : rep.regiones()) {
                List<byte[]> l = new ArrayList<>();
                try (ArchivoDatos.Lector in = new ArchivoDatos.Lector(rep.datos(r))) {
                    while (in.siguiente()) {
                        l.add(in.raw);
                        if (!Registro.esActivo(in.raw)) sinMarcados = false;
                    }
                }
                total += l.size();
                List<byte[]> e = esperados.get(r);
                if (l.size() != e.size()) conserva = false;
                else for (int i = 0; i < l.size(); i++) if (!Arrays.equals(l.get(i), e.get(i))) conserva = false;
            }
            chequear("PACK " + q + " conserva todos los activos byte a byte y en el mismo orden", conserva);
            chequear("PACK " + q + " elimina solo los marcados (" + n + " - " + marcados + " = " + total + ")",
                    sinMarcados && total == n - marcados);
            chequear("PACK " + q + " actualiza metadata (marcados=0, version 2, bytes reales)", metadataCoherente(rep));
            chequear("PACK " + q + " deja desactualizados solo los indices de archivos compactados", indicesTrasPack(rep));
        }
        chequear("PACK serial y paralelo: archivos .dat identicos", mismosDat(ser, par));

        // 3) INDEX serial y paralelo
        t = System.nanoTime();
        Indexador.serial(ser);
        double tIs = Utilidades.ms(System.nanoTime() - t);
        t = System.nanoTime();
        Indexador.paralelo(par, hilos, null);
        double tIp = Utilidades.ms(System.nanoTime() - t);
        chequear("INDEX: todos los indices vigentes", indicesVigentes(ser) == ser.regiones().size()
                && indicesVigentes(par) == par.regiones().size());
        chequear("INDEX serial y paralelo: archivos .dat e .idx identicos", Comparador.mismosArchivos(ser, par));
        chequear("INDEX: entradas ordenadas por cuenta y offsets apuntan a registros de esa cuenta", referenciasCorrectas(ser));

        // 4) busqueda de TODAS las cuentas comparada con busqueda lineal
        TreeSet<Long> cuentas = new TreeSet<>();
        Map<Long, Integer> frecuencia = new HashMap<>();
        for (String r : ser.regiones())
            try (ArchivoDatos.Lector in = new ArchivoDatos.Lector(ser.datos(r))) {
                while (in.siguiente()) {
                    long c = Registro.cuentaDe(in.raw);
                    cuentas.add(c);
                    frecuencia.merge(c, 1, Integer::sum);
                }
            }
        boolean todas = true;
        long movimientos = 0;
        for (long c : cuentas) {
            Buscador.Resultado res = Buscador.buscar(ser, c);
            List<Long> lineal = Buscador.busquedaLineal(ser, Regiones.NOMBRE[Regiones.deCuenta(c)], c);
            List<Long> idx = res.movimientos().stream().map(Buscador.Movimiento::offset).toList();
            if (!idx.equals(lineal) || !res.indicesReconstruidos().isEmpty()) todas = false;
            movimientos += idx.size();
        }
        long repetidas = frecuencia.values().stream().filter(v -> v > 1).count();
        chequear("INDEX encuentra todos los movimientos de las " + cuentas.size() + " cuentas (" + repetidas
                + " cuentas con movimientos repetidos), igual que la busqueda lineal", todas && movimientos == n - marcados);
        chequear("Cuenta inexistente devuelve 0 movimientos",
                Buscador.buscar(ser, Regiones.primeraCuenta(0) + 9_999_999).movimientos().isEmpty());

        // 5) marcar despues de indexar: el indice sigue valido y la busqueda omite los marcados
        long cuenta = cuentas.first();
        int antes = Buscador.buscar(ser, cuenta).movimientos().size();
        long m2 = Marcador.marcarCuenta(ser, cuenta);
        Buscador.Resultado tras = Buscador.buscar(ser, cuenta);
        chequear("Tras marcar la cuenta " + cuenta + " (" + antes + " movs) la busqueda devuelve 0 y omite " + m2,
                tras.movimientos().isEmpty() && tras.marcadosOmitidos() == m2 && m2 == antes);

        // 6) PACK deja el indice desactualizado y la busqueda lo reconstruye
        String region = Regiones.NOMBRE[Regiones.deCuenta(cuenta)];
        Pack.packArchivo(ser, region);
        boolean desact = !ser.indiceVigente(region);
        boolean ok = true, reconstruido = false;
        for (long c : cuentas.subSet(Regiones.primeraCuenta(Regiones.deCuenta(cuenta)), Regiones.primeraCuenta(Regiones.deCuenta(cuenta) + 1))) {
            Buscador.Resultado res = Buscador.buscar(ser, c);
            reconstruido |= res.indicesReconstruidos().contains(region);
            if (!res.movimientos().stream().map(Buscador.Movimiento::offset).toList()
                    .equals(Buscador.busquedaLineal(ser, region, c))) ok = false;
        }
        chequear("PACK posterior deja " + region + ".idx desactualizado, la busqueda lo reconstruye y los resultados son correctos",
                desact && reconstruido && ok && ser.indiceVigente(region));

        return String.format("%-8d %8d %10.2f ms %10.2f ms %10.2f ms %10.2f ms", n, marcados, tPs, tPp, tIs, tIp);
    }

    private static long contar(Repositorio rep) throws IOException {
        long n = 0;
        for (String r : rep.regiones())
            try (ArchivoDatos.Lector in = new ArchivoDatos.Lector(rep.datos(r))) {
                while (in.siguiente()) n++;
            }
        return n;
    }

    private static boolean metadataCoherente(Repositorio rep) throws IOException {
        for (String r : rep.regiones()) {
            Metadata m = rep.cargarMeta(r);
            if (m.getLong(Metadata.ELIMINADOS, -1) != 0) return false;
            if (m.getLong(Metadata.BYTES, -1) != Files.size(rep.datos(r))) return false;
            if (m.getLong(Metadata.TOTAL, -1) != m.getLong(Metadata.ACTIVOS, -2)) return false;
            // solo cambia de version el archivo que tenia registros marcados
            if (m.getLong(Metadata.PACK_ELIMINADOS, 0) > 0 && m.getLong(Metadata.VERSION, 0) != 2) return false;
        }
        return true;
    }

    private static int indicesVigentes(Repositorio rep) throws IOException {
        int n = 0;
        for (String r : rep.regiones()) if (rep.indiceVigente(r)) n++;
        return n;
    }

    /** Tras PACK: indice desactualizado si y solo si el archivo fue compactado. */
    private static boolean indicesTrasPack(Repositorio rep) throws IOException {
        boolean algunoCompactado = false;
        for (String r : rep.regiones()) {
            boolean compactado = rep.cargarMeta(r).getLong(Metadata.PACK_ELIMINADOS, 0) > 0;
            algunoCompactado |= compactado;
            if (rep.indiceVigente(r) == compactado) return false;
        }
        return algunoCompactado;
    }

    /** Recorre cada .idx completo: orden (cuenta, offset) y cada offset apunta a un registro activo de esa cuenta. */
    private static boolean referenciasCorrectas(Repositorio rep) throws IOException {
        for (String r : rep.regiones()) {
            try (RandomAccessFile idx = new RandomAccessFile(rep.indice(r).toFile(), "r");
                 RandomAccessFile dat = new RandomAccessFile(rep.datos(r).toFile(), "r")) {
                idx.seek(ArchivoIndice.TAM_CAB - 8);
                long n = idx.readLong();
                long pc = Long.MIN_VALUE, po = -1;
                for (long i = 0; i < n; i++) {
                    long c = idx.readLong(), o = idx.readLong();
                    if (c < pc || (c == pc && o <= po)) return false;
                    long pos = idx.getFilePointer();
                    byte[] raw = ArchivoDatos.leerEn(dat, o);
                    if (Registro.cuentaDe(raw) != c || !Registro.esActivo(raw)) return false;
                    idx.seek(pos);
                    pc = c;
                    po = o;
                }
            }
        }
        return true;
    }

    private static boolean mismosDat(Repositorio a, Repositorio b) throws IOException {
        for (String r : a.regiones()) if (!Utilidades.iguales(a.datos(r), b.datos(r))) return false;
        return true;
    }

    private static void chequear(String desc, boolean ok) {
        if (!ok) fallos++;
        log("  [" + (ok ? " OK " : "FALLA") + "] " + desc);
    }

    private static void log(String s) {
        System.out.println(s);
        salida.add(s);
    }
}

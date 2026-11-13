package lsdfs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Compara tiempos serial vs paralelo SIN modificar el repositorio principal:
 * en cada repeticion copia el repositorio a trabajo/serial y trabajo/paralelo,
 * ejecuta PACK + INDEX en cada copia y verifica que los archivos resultantes
 * (.dat e .idx) sean identicos byte a byte. Los tiempos se guardan en
 * resultados/tiempos.txt tal como se midieron.
 */
public final class Comparador {

    public record Medicion(double packSerialMs, double packParaleloMs, double indexSerialMs,
                           double indexParaleloMs, boolean equivalentes) { }

    private Comparador() { }

    public static List<Medicion> comparar(Repositorio repo, Path trabajo, int hilos, int repeticiones,
                                          Path informe, String etiqueta) throws Exception {
        Repositorio s = new Repositorio(trabajo.resolve("serial"));
        Repositorio p = new Repositorio(trabajo.resolve("paralelo"));
        List<Medicion> ms = new ArrayList<>();
        for (int i = 0; i < repeticiones; i++) {
            Utilidades.copiarDirectorio(repo.dir, s.dir);
            Utilidades.copiarDirectorio(repo.dir, p.dir);

            long t = System.nanoTime();
            Pack.serial(s);
            double ps = Utilidades.ms(System.nanoTime() - t);
            t = System.nanoTime();
            Pack.paralelo(p, hilos, null);
            double pp = Utilidades.ms(System.nanoTime() - t);
            t = System.nanoTime();
            Indexador.serial(s);
            double is = Utilidades.ms(System.nanoTime() - t);
            t = System.nanoTime();
            Indexador.paralelo(p, hilos, null);
            double ip = Utilidades.ms(System.nanoTime() - t);

            ms.add(new Medicion(ps, pp, is, ip, mismosArchivos(s, p)));
        }

        Repositorio.Resumen r = repo.resumen();
        List<String> lineas = new ArrayList<>();
        lineas.add("");
        lineas.add("=== " + etiqueta + " | " + LocalDateTime.now().withNano(0) + " ===");
        lineas.add(String.format("Repositorio: %d archivos, %,d registros (%,d activos, %,d marcados), %s | hilos=%d | nucleos=%d",
                r.archivos(), r.total(), r.activos(), r.marcados(), Utilidades.kb(r.bytes()), hilos,
                Runtime.getRuntime().availableProcessors()));
        lineas.add(String.format("%-4s %14s %14s %14s %14s %12s", "Rep", "PACK serial", "PACK paralelo",
                "INDEX serial", "INDEX paral.", "Equivalentes"));
        for (int i = 0; i < ms.size(); i++) {
            Medicion m = ms.get(i);
            lineas.add(String.format("%-4d %11.2f ms %11.2f ms %11.2f ms %11.2f ms %12s", i + 1, m.packSerialMs(),
                    m.packParaleloMs(), m.indexSerialMs(), m.indexParaleloMs(), m.equivalentes() ? "SI" : "NO"));
        }
        Files.createDirectories(informe.getParent());
        Files.write(informe, lineas, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        lineas.forEach(System.out::println);
        System.out.println("(Tiempos agregados a " + informe + ")");
        return ms;
    }

    /** true si todos los .dat e .idx de ambos repositorios son identicos byte a byte. */
    public static boolean mismosArchivos(Repositorio a, Repositorio b) throws IOException {
        if (!a.regiones().equals(b.regiones())) return false;
        for (String r : a.regiones()) {
            if (!Utilidades.iguales(a.datos(r), b.datos(r))) return false;
            if (!Utilidades.iguales(a.indice(r), b.indice(r))) return false;
        }
        return true;
    }
}

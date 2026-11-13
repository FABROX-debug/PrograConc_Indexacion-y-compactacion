package lsdfs;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;

/** Utilidades de archivos. */
public final class Utilidades {

    private Utilidades() { }

    /** Reemplaza 'destino' por 'origen' en un solo paso (movimiento atomico si el sistema lo permite). */
    public static void reemplazar(Path origen, Path destino) throws IOException {
        try {
            Files.move(origen, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(origen, destino, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** REUTILIZADA de pregunta2 (Reporte.iguales): compara dos archivos bloque a bloque. */
    public static boolean iguales(Path a, Path b) throws IOException {
        if (!Files.exists(a) || !Files.exists(b)) return false;
        try (RandomAccessFile fa = new RandomAccessFile(a.toFile(), "r");
             RandomAccessFile fb = new RandomAccessFile(b.toFile(), "r")) {
            if (fa.length() != fb.length()) return false;
            byte[] ba = new byte[65536], bb = new byte[65536];
            for (long pos = 0; pos < fa.length(); pos += ba.length) {
                int cant = (int) Math.min(ba.length, fa.length() - pos);
                fa.seek(pos);
                fa.readFully(ba, 0, cant);
                fb.seek(pos);
                fb.readFully(bb, 0, cant);
                if (!Arrays.equals(ba, 0, cant, bb, 0, cant)) return false;
            }
            return true;
        }
    }

    public static void borrarDirectorio(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    /** Copia los archivos (no subdirectorios) de un directorio a otro, vaciando antes el destino. */
    public static void copiarDirectorio(Path origen, Path destino) throws IOException {
        borrarDirectorio(destino);
        Files.createDirectories(destino);
        try (Stream<Path> s = Files.list(origen)) {
            for (Path p : s.filter(Files::isRegularFile).toList())
                Files.copy(p, destino.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static String soles(long centimos) {
        return String.format("%,.2f", centimos / 100.0);
    }

    public static String kb(long bytes) {
        return String.format("%,.1f KB", bytes / 1024.0);
    }

    public static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }
}

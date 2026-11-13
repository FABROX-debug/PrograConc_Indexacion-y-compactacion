package lsdfs;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Archivo de indice (I1, I2, ... IN del LSDFS), uno por archivo de datos.
 * <pre>
 * Cabecera (28 bytes): int MAGIC ("LIDX") | long version de datos | long bytes del .dat | long n entradas
 * Entrada  (16 bytes): long cuenta | long offset del registro en el .dat
 * </pre>
 * Las entradas estan ordenadas por (cuenta, offset). Como son de tamano fijo, la
 * entrada i esta en CAB + i*16 y se puede hacer busqueda binaria directamente
 * sobre el archivo con RandomAccessFile: O(log n) lecturas.
 */
public final class ArchivoIndice {

    public static final int MAGIC = 0x4C494458;   // "LIDX"
    public static final int TAM_CAB = 4 + 8 + 8 + 8;
    public static final int TAM_ENT = 16;

    public record Cabecera(long versionDatos, long bytesDatos, long entradas) { }

    /** Resultado de una busqueda: offsets encontrados y cuantas entradas se leyeron en la busqueda binaria. */
    public record Hallazgo(List<Long> offsets, int pasosBinarios) { }

    private ArchivoIndice() { }

    /** Escribe el indice (las entradas ya deben venir ordenadas). */
    public static void escribir(Path p, long versionDatos, long bytesDatos, long[] cuentas, long[] offsets) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(p), 1 << 16))) {
            out.writeInt(MAGIC);
            out.writeLong(versionDatos);
            out.writeLong(bytesDatos);
            out.writeLong(cuentas.length);
            for (int i = 0; i < cuentas.length; i++) {
                out.writeLong(cuentas[i]);
                out.writeLong(offsets[i]);
            }
        }
    }

    public static Cabecera leerCabecera(Path p) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "r")) {
            return leerCabecera(raf);
        }
    }

    private static Cabecera leerCabecera(RandomAccessFile raf) throws IOException {
        raf.seek(0);
        if (raf.readInt() != MAGIC) throw new IOException("No es un archivo de indice valido");
        return new Cabecera(raf.readLong(), raf.readLong(), raf.readLong());
    }

    /**
     * Busqueda binaria de la PRIMERA entrada con esa cuenta (lower bound) y luego
     * recorrido hacia adelante mientras la cuenta se repita: devuelve todos los offsets.
     */
    public static Hallazgo buscar(Path p, long cuenta) throws IOException {
        List<Long> offsets = new ArrayList<>();
        int pasos = 0;
        try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "r")) {
            long n = leerCabecera(raf).entradas();
            long lo = 0, hi = n;
            while (lo < hi) {
                long mid = (lo + hi) >>> 1;
                raf.seek(TAM_CAB + mid * TAM_ENT);
                pasos++;
                if (raf.readLong() < cuenta) lo = mid + 1;
                else hi = mid;
            }
            for (long i = lo; i < n; i++) {
                raf.seek(TAM_CAB + i * TAM_ENT);
                if (raf.readLong() != cuenta) break;
                offsets.add(raf.readLong());
            }
        }
        return new Hallazgo(offsets, pasos);
    }
}

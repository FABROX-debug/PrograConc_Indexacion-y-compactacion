package pcp.p2;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Arrays;

/** Utilidades para escribir reportes de texto y comparar archivos de resultados. */
public final class Reporte {

    private Reporte() { }

    /** Tabla de indicadores por region leida de un archivo de resultados (slots de Acumulador). */
    public static void tablaRegiones(RandomAccessFile txt, Path resultados) throws IOException {
        Formato.linea(txt, String.format("%-14s %10s %15s %15s %15s %16s %7s %8s %12s %16s",
                "Region", "Movim.", "Depositos S/", "Retiros S/", "Transfer. S/", "Flujo neto S/",
                "Sospech", "AltoRies", "Max S/", "Huella SHA-256"));
        Acumulador tot = new Acumulador();
        try (RandomAccessFile in = new RandomAccessFile(resultados.toFile(), "r")) {
            for (int r = 0; r < Regiones.TOTAL; r++) {
                Acumulador a = new Acumulador();
                a.leer(in, (long) r * Acumulador.TAM);
                tot.combinar(a);
                Formato.linea(txt, fila(Regiones.NOMBRE[r], a));
            }
        }
        Formato.linea(txt, fila("TOTAL", tot));
    }

    private static String fila(String nombre, Acumulador a) {
        return String.format("%-14s %,10d %,15.2f %,15.2f %,15.2f %,16.2f %7d %8d %,12.2f %016x",
                nombre, a.get(Acumulador.N),
                a.get(Acumulador.SUM) / 100.0, a.get(Acumulador.SUM + 1) / 100.0, a.get(Acumulador.SUM + 2) / 100.0,
                a.get(Acumulador.NETO) / 100.0, a.get(Acumulador.SOSP), a.get(Acumulador.RIESGO),
                a.get(Acumulador.MAX) / 100.0, a.get(Acumulador.HUELLA));
    }

    /** Compara dos archivos bloque a bloque con RandomAccessFile. */
    public static boolean iguales(Path a, Path b) throws IOException {
        if (!a.toFile().exists() || !b.toFile().exists()) return false;
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
}

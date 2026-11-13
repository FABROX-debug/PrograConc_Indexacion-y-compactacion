package pcp.p2;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.SplittableRandom;

/**
 * Genera el repositorio: un archivo de movimientos bancarios por region del Peru
 * (repositorio/LIMA.dat, ..., repositorio/TUMBES.dat). La cantidad de registros es
 * proporcional a la poblacion, por eso LIMA es enorme y TUMBES pequeno.
 */
public final class GeneradorRepositorio {

    private static final long INICIO_2025 = 1735707600000L;   // 2025-01-01 00:00 hora de Lima
    private static final long ANIO_MS = 365L * 86_400_000L;

    private GeneradorRepositorio() { }

    /** @param factor registros por cada mil habitantes */
    public static void generar(Path repo, int factor) throws IOException {
        long total = 0;
        for (int r = 0; r < Regiones.TOTAL; r++) {
            long n = (long) Regiones.POBLACION_MILES[r] * factor;
            generarRegion(repo.resolve(Regiones.NOMBRE[r] + ".dat"), r, n);
            total += n;
            System.out.printf("  %-14s %,10d movimientos  %7.2f MB%n", Regiones.NOMBRE[r], n, Formato.mb(n * Formato.TAM_REG));
        }
        System.out.printf("  TOTAL          %,10d movimientos  %7.2f MB%n", total, Formato.mb(total * Formato.TAM_REG));
    }

    private static void generarRegion(Path archivo, int region, long n) throws IOException {
        SplittableRandom rnd = new SplittableRandom(1000 + region);
        long cuentas = Math.max(1000, n / 15);
        byte[] buf = new byte[Formato.BLOQUE_IO * Formato.TAM_REG];
        ByteBuffer bb = ByteBuffer.wrap(buf);
        try (RandomAccessFile raf = Formato.abrirNuevo(archivo)) {
            for (long desde = 0; desde < n; desde += Formato.BLOQUE_IO) {
                int cant = (int) Math.min(Formato.BLOQUE_IO, n - desde);
                for (int k = 0; k < cant; k++) {
                    int off = k * Formato.TAM_REG;
                    double p = rnd.nextDouble();
                    int tipo = p < 0.50 ? Formato.DEPOSITO : p < 0.85 ? Formato.RETIRO : Formato.TRANSFERENCIA;
                    double q = rnd.nextDouble();
                    int canal = q < 0.25 ? 0 : q < 0.60 ? 1 : 2;
                    double monto = rnd.nextDouble() < 0.0015
                            ? 30_000 + rnd.nextDouble() * 120_000                       // operacion grande
                            : Math.exp(Math.log(250) + 1.1 * normal(rnd));             // log-normal
                    bb.putLong(off, (region + 1) * 10_000_000L + rnd.nextLong(cuentas));
                    bb.putLong(off + 8, INICIO_2025 + rnd.nextLong(ANIO_MS));
                    bb.putInt(off + 16, tipo);
                    bb.putInt(off + 20, canal);
                    bb.putDouble(off + 24, Math.round(monto * 100) / 100.0);
                    bb.putDouble(off + 32, Math.round(rnd.nextDouble() * 5_000_000) / 100.0);
                }
                raf.seek(desde * Formato.TAM_REG);
                raf.write(buf, 0, cant * Formato.TAM_REG);
            }
        }
    }

    private static double normal(SplittableRandom rnd) {
        double u1 = 1.0 - rnd.nextDouble(), u2 = rnd.nextDouble();
        return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }
}

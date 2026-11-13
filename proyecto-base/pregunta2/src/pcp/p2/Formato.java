package pcp.p2;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Registro de movimiento bancario (40 bytes, acceso directo con RandomAccessFile):
 *   0 long idCuenta | 8 long fechaEpochMs | 16 int tipo | 20 int canal | 24 double monto | 32 double saldo
 * Registro i de un archivo de region: posicion i * TAM_REG.
 */
public final class Formato {

    public static final int TAM_REG = 40;
    public static final int BLOQUE_IO = 1024;             // registros por lectura de disco

    public static final String[] TIPO = {"DEPOSITO", "RETIRO", "TRANSFERENCIA"};
    public static final String[] CANAL = {"AGENCIA", "ATM", "APP"};
    public static final int DEPOSITO = 0, RETIRO = 1, TRANSFERENCIA = 2;
    public static final int APP = 2;

    /** sospechosas.dat: int region | long registro | long idCuenta | long montoCentimos */
    public static final int TAM_SOSP = 4 + 8 + 8 + 8;

    private Formato() { }

    public static RandomAccessFile abrirNuevo(Path p) throws IOException {
        File f = p.toFile();
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        RandomAccessFile raf = new RandomAccessFile(f, "rw");
        raf.setLength(0);
        return raf;
    }

    public static long registros(Path p) {
        return p.toFile().length() / TAM_REG;
    }

    public static void linea(RandomAccessFile raf, String s) throws IOException {
        raf.write((s + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
    }

    public static double mb(long bytes) {
        return bytes / 1048576.0;
    }
}

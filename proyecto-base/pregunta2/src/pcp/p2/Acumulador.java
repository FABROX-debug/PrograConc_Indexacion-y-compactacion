package pcp.p2;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Indicadores de una region. Todos los montos se llevan en centimos (long) para
 * que el resultado no dependa del orden en que los nodos procesan los bloques.
 * Se guarda en disco como un "slot" de TAM bytes (una posicion por region).
 */
public final class Acumulador {

    public static final int N = 0, CNT = 1, SUM = 4, CANAL = 7, NETO = 10, SOSP = 11,
            RIESGO = 12, MAX = 13, MIN = 14, HUELLA = 15, CAMPOS = 16;
    public static final int TAM = CAMPOS * 8;

    private final long[] v = new long[CAMPOS];

    public Acumulador() {
        v[MAX] = Long.MIN_VALUE;
        v[MIN] = Long.MAX_VALUE;
    }

    public long get(int campo) {
        return v[campo];
    }

    /**
     * Procesa el registro que empieza en buf[off]. Calcula:
     *  - totales por tipo y canal, flujo neto, monto maximo/minimo
     *  - operacion sospechosa (monto >= umbral, Registro de Operaciones UIF)
     *  - score de riesgo (modelo logistico: monto, madrugada, canal, tipo)
     *  - huella de integridad SHA-256 (XOR, independiente del orden)
     * 'bb' envuelve a 'buf'. Devuelve true si la operacion es sospechosa.
     */
    public boolean agregar(ByteBuffer bb, byte[] buf, int off, long umbralCent, Huella huella) {
        long fecha = bb.getLong(off + 8);
        int tipo = bb.getInt(off + 16);
        int canal = bb.getInt(off + 20);
        double monto = bb.getDouble(off + 24);
        long c = Math.round(monto * 100);

        v[N]++;
        v[CNT + tipo]++;
        v[SUM + tipo] += c;
        v[CANAL + canal] += c;
        v[NETO] += tipo == Formato.DEPOSITO ? c : -c;
        if (c > v[MAX]) v[MAX] = c;
        if (c < v[MIN]) v[MIN] = c;

        int hora = (int) Math.floorMod(fecha / 3_600_000L - 5, 24);   // hora de Lima
        double x = 1.1 * Math.log(Math.max(monto, 1) / 300.0)
                + (hora < 5 ? 1.5 : 0)
                + (canal == Formato.APP ? 0.6 : 0)
                + (tipo == Formato.TRANSFERENCIA ? 0.8 : tipo == Formato.RETIRO ? 0.4 : 0)
                - 2.5;
        if (1.0 / (1.0 + Math.exp(-x)) > 0.8) v[RIESGO]++;

        v[HUELLA] ^= huella.de(buf, off);

        boolean sosp = c >= umbralCent;
        if (sosp) v[SOSP]++;
        return sosp;
    }

    public void combinar(Acumulador o) {
        for (int i = 0; i < MAX; i++) v[i] += o.v[i];
        v[MAX] = Math.max(v[MAX], o.v[MAX]);
        v[MIN] = Math.min(v[MIN], o.v[MIN]);
        v[HUELLA] ^= o.v[HUELLA];
    }

    public void leer(RandomAccessFile raf, long pos) throws IOException {
        raf.seek(pos);
        for (int i = 0; i < CAMPOS; i++) v[i] = raf.readLong();
    }

    public void escribir(RandomAccessFile raf, long pos) throws IOException {
        raf.seek(pos);
        for (int i = 0; i < CAMPOS; i++) raf.writeLong(v[i]);
    }

    /** Suma este acumulador al slot de disco 'pos' (leer - combinar - escribir). */
    public void acumularEn(RandomAccessFile raf, long pos) throws IOException {
        Acumulador enDisco = new Acumulador();
        enDisco.leer(raf, pos);
        enDisco.combinar(this);
        enDisco.escribir(raf, pos);
    }

    /** Crea un archivo de resultados con un slot vacio por region. */
    public static void inicializar(RandomAccessFile raf, int slots) throws IOException {
        raf.setLength(0);
        Acumulador vacio = new Acumulador();
        for (int r = 0; r < slots; r++) vacio.escribir(raf, (long) r * TAM);
    }

    /** SHA-256 de un registro; un objeto por hilo (MessageDigest no es thread-safe). */
    public static final class Huella {
        private final MessageDigest md;
        private final byte[] out = new byte[32];

        public Huella() {
            try {
                md = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        long de(byte[] buf, int off) {
            md.update(buf, off, Formato.TAM_REG);
            try {
                md.digest(out, 0, 32);
            } catch (java.security.DigestException e) {
                throw new IllegalStateException(e);
            }
            return ByteBuffer.wrap(out).getLong(0);
        }
    }
}

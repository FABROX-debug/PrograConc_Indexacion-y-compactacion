package lsdfs;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Movimiento bancario de LONGITUD VARIABLE.
 *
 * Formato en disco (big-endian, a partir del offset del registro):
 * <pre>
 *  +0   int    longitud total del registro L (incluye estos 4 bytes)
 *  +4   byte   estado: 1 = ACTIVO, 0 = MARCADO PARA ELIMINAR
 *  +5   long   numero de cuenta
 *  +13  long   fecha (epoch en milisegundos)
 *  +21  byte   tipo  (0 DEPOSITO, 1 RETIRO, 2 TRANSFERENCIA)
 *  +22  byte   canal (0 AGENCIA, 1 ATM, 2 APP)
 *  +23  long   monto en centimos
 *  +31  short  n1 + n1 bytes UTF-8 : titular  (campo variable)
 *       short  n2 + n2 bytes UTF-8 : concepto (campo variable)
 * </pre>
 * Como L esta al inicio, se puede recorrer el archivo saltando de registro en
 * registro, y como el estado esta en una posicion fija (+4), marcar un registro
 * para eliminar es escribir 1 byte con acceso directo (seek).
 */
public final class Registro {

    public static final byte ACTIVO = 1, ELIMINADO = 0;
    public static final int POS_ESTADO = 4;
    public static final int POS_CUENTA = 5;
    public static final int TAM_FIJO = 4 + 1 + 8 + 8 + 1 + 1 + 8 + 2 + 2;   // 35 bytes sin textos
    public static final int TAM_MAX = 4096;                                  // limite de seguridad al leer

    public static final String[] TIPO = {"DEPOSITO", "RETIRO", "TRANSFERENCIA"};
    public static final String[] CANAL = {"AGENCIA", "ATM", "APP"};

    public final byte estado;
    public final long cuenta;
    public final long fecha;
    public final byte tipo;
    public final byte canal;
    public final long montoCentimos;
    public final String titular;
    public final String concepto;

    public Registro(byte estado, long cuenta, long fecha, byte tipo, byte canal, long montoCentimos,
                    String titular, String concepto) {
        this.estado = estado;
        this.cuenta = cuenta;
        this.fecha = fecha;
        this.tipo = tipo;
        this.canal = canal;
        this.montoCentimos = montoCentimos;
        this.titular = titular;
        this.concepto = concepto;
    }

    /** Serializa el registro a su forma en disco. */
    public byte[] aBytes() {
        byte[] t = titular.getBytes(StandardCharsets.UTF_8);
        byte[] c = concepto.getBytes(StandardCharsets.UTF_8);
        int len = TAM_FIJO + t.length + c.length;
        if (len > TAM_MAX) throw new IllegalArgumentException("Registro demasiado grande: " + len);
        ByteBuffer bb = ByteBuffer.allocate(len);
        bb.putInt(len).put(estado).putLong(cuenta).putLong(fecha).put(tipo).put(canal).putLong(montoCentimos);
        bb.putShort((short) t.length).put(t);
        bb.putShort((short) c.length).put(c);
        return bb.array();
    }

    /** Reconstruye un registro a partir de sus bytes en disco. */
    public static Registro deBytes(byte[] raw) {
        ByteBuffer bb = ByteBuffer.wrap(raw);
        bb.getInt();   // longitud
        byte estado = bb.get();
        long cuenta = bb.getLong();
        long fecha = bb.getLong();
        byte tipo = bb.get();
        byte canal = bb.get();
        long monto = bb.getLong();
        String titular = texto(bb);
        String concepto = texto(bb);
        return new Registro(estado, cuenta, fecha, tipo, canal, monto, titular, concepto);
    }

    private static String texto(ByteBuffer bb) {
        int n = bb.getShort();
        String s = new String(bb.array(), bb.position(), n, StandardCharsets.UTF_8);
        bb.position(bb.position() + n);
        return s;
    }

    /** Lectura rapida del numero de cuenta sin decodificar todo el registro. */
    public static long cuentaDe(byte[] raw) {
        return ByteBuffer.wrap(raw).getLong(POS_CUENTA);
    }

    public static boolean esActivo(byte[] raw) {
        return raw[POS_ESTADO] == ACTIVO;
    }

    public boolean activo() {
        return estado == ACTIVO;
    }
}

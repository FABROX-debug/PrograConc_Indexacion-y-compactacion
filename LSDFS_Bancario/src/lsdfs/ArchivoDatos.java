package lsdfs;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Archivo de datos de una region (F1, F2, ... FN del LSDFS).
 * Cabecera de 8 bytes: int MAGIC ("LSDF") | int version de formato.
 * Despues vienen los registros de longitud variable uno tras otro.
 * La referencia a un registro es su OFFSET en bytes desde el inicio del archivo.
 */
public final class ArchivoDatos {

    public static final int MAGIC = 0x4C534446;   // "LSDF"
    public static final int VERSION_FORMATO = 1;
    public static final int TAM_CABECERA = 8;
    private static final int BUFFER = 1 << 16;

    private ArchivoDatos() { }

    /** Crea un archivo nuevo (vacio, solo cabecera) y devuelve un flujo para agregar registros. */
    public static DataOutputStream crear(Path p) throws IOException {
        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(p), BUFFER));
        out.writeInt(MAGIC);
        out.writeInt(VERSION_FORMATO);
        return out;
    }

    /** Lee el registro que empieza en 'offset' (acceso directo). */
    public static byte[] leerEn(RandomAccessFile raf, long offset) throws IOException {
        raf.seek(offset);
        int len = raf.readInt();
        validarLongitud(len, offset);
        byte[] raw = new byte[len];
        escribirInt(raw, len);
        raf.readFully(raw, 4, len - 4);
        return raw;
    }

    private static void validarLongitud(int len, long offset) throws IOException {
        if (len < Registro.TAM_FIJO || len > Registro.TAM_MAX)
            throw new IOException("Registro corrupto o referencia invalida en offset " + offset + " (longitud " + len + ")");
    }

    private static void escribirInt(byte[] b, int v) {
        b[0] = (byte) (v >>> 24);
        b[1] = (byte) (v >>> 16);
        b[2] = (byte) (v >>> 8);
        b[3] = (byte) v;
    }

    /** Recorrido secuencial del archivo: entrega cada registro con su offset. */
    public static final class Lector implements AutoCloseable {
        private final DataInputStream in;
        private long pos;
        /** Offset del registro actual. */
        public long offset;
        /** Bytes del registro actual (tal como estan en disco). */
        public byte[] raw;

        public Lector(Path p) throws IOException {
            in = new DataInputStream(new BufferedInputStream(Files.newInputStream(p), BUFFER));
            if (in.readInt() != MAGIC || in.readInt() != VERSION_FORMATO) {
                in.close();
                throw new IOException("No es un archivo LSDF valido: " + p);
            }
            pos = TAM_CABECERA;
        }

        /** Avanza al siguiente registro; false al llegar al final del archivo. */
        public boolean siguiente() throws IOException {
            int len;
            try {
                len = in.readInt();
            } catch (EOFException fin) {
                return false;
            }
            validarLongitud(len, pos);
            raw = new byte[len];
            escribirInt(raw, len);
            in.readFully(raw, 4, len - 4);
            offset = pos;
            pos += len;
            return true;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}

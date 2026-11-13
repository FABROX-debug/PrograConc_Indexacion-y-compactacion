package pcp.p2;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.List;

/**
 * Cola de bloques de trabajo guardada en disco (cola_bloques.dat, acceso directo).
 * Entrada (40 bytes): int propietario | int region | long iniParticion | long desde | long hasta | int estado | int ejecutor
 *
 * Las entradas de cada nodo son contiguas. El propio nodo consume las suyas desde
 * el inicio; en modo DINAMICO un nodo ocioso roba desde el final de la cola del
 * nodo con mas bloques pendientes (work stealing). El acceso esta sincronizado.
 */
public final class ColaBloques implements AutoCloseable {

    public record Bloque(int indice, int propietario, int region, long iniParticion, long desde, long hasta) {
        public long bytes() {
            return (hasta - desde) * Formato.TAM_REG;
        }
    }

    public static final int TAM_ENT = 4 + 4 + 8 + 8 + 8 + 4 + 4;
    private static final int PENDIENTE = 0, TOMADO = 1;

    private final RandomAccessFile raf;
    private final boolean robo;
    private final int[] inicio, fin, cursor;
    private final boolean[] listo;

    public ColaBloques(Path archivo, List<List<Balanceador.Particion>> plan, int bloqueRegs, boolean robo) throws IOException {
        this.raf = Formato.abrirNuevo(archivo);
        this.robo = robo;
        int m = plan.size();
        inicio = new int[m];
        fin = new int[m];
        cursor = new int[m];
        listo = new boolean[m];
        int n = 0;
        for (int k = 0; k < m; k++) {
            inicio[k] = n;
            for (Balanceador.Particion p : plan.get(k))
                for (long d = p.ini(); d < p.fin(); d += bloqueRegs) {
                    raf.seek((long) n * TAM_ENT);
                    raf.writeInt(k);
                    raf.writeInt(p.region());
                    raf.writeLong(p.ini());
                    raf.writeLong(d);
                    raf.writeLong(Math.min(d + bloqueRegs, p.fin()));
                    raf.writeInt(PENDIENTE);
                    raf.writeInt(-1);
                    n++;
                }
            fin[k] = n;
            cursor[k] = inicio[k];
        }
    }

    /** El nodo termino de recibir sus datos: sus bloques ya pueden procesarse (o robarse). */
    public synchronized void marcarListo(int nodo) {
        listo[nodo] = true;
        notifyAll();
    }

    /** Siguiente bloque para un hilo del nodo; null cuando ya no queda trabajo para el. */
    public synchronized Bloque tomar(int nodo) throws IOException, InterruptedException {
        while (true) {
            if (cursor[nodo] < fin[nodo])
                return marcar(cursor[nodo]++, nodo);
            if (!robo) return null;

            int victima = -1, maxPend = 0;
            boolean esperando = false;
            for (int k = 0; k < listo.length; k++) {
                if (k == nodo) continue;
                if (!listo[k]) {
                    esperando |= fin[k] > cursor[k];
                    continue;
                }
                if (fin[k] - cursor[k] > maxPend) {
                    maxPend = fin[k] - cursor[k];
                    victima = k;
                }
            }
            if (victima >= 0) return marcar(--fin[victima], nodo);   // roba del final
            if (!esperando) return null;
            wait();   // otro nodo aun esta recibiendo datos; quizas haya algo que robar
        }
    }

    private Bloque marcar(int idx, int ejecutor) throws IOException {
        long pos = (long) idx * TAM_ENT;
        raf.seek(pos);
        Bloque b = new Bloque(idx, raf.readInt(), raf.readInt(), raf.readLong(), raf.readLong(), raf.readLong());
        raf.seek(pos + 32);
        raf.writeInt(TOMADO);
        raf.writeInt(ejecutor);
        return b;
    }

    @Override
    public synchronized void close() throws IOException {
        raf.close();
    }
}

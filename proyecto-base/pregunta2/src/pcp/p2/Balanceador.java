package pcp.p2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Decide que parte del repositorio procesa cada nodo, segun su capacidad de
 * procesamiento (hilos) y su espacio de almacenamiento (disco).
 */
public final class Balanceador {

    /** Rango de registros [ini, fin) de una region asignado a un nodo. */
    public record Particion(int region, long ini, long fin) {
        public long registros() {
            return fin - ini;
        }

        public long bytes() {
            return registros() * Formato.TAM_REG;
        }
    }

    private Balanceador() { }

    /** Devuelve, para cada nodo (indice 0..m-1), la lista de particiones que le tocan. */
    public static List<List<Particion>> planificar(ConfigCluster cfg, Estrategia e) {
        int m = cfg.nodos.size();
        long[] libre = new long[m], carga = new long[m];
        List<List<Particion>> plan = new ArrayList<>();
        for (int k = 0; k < m; k++) {
            libre[k] = cfg.nodos.get(k).discoBytes();
            plan.add(new ArrayList<>());
        }

        long[] regs = new long[Regiones.TOTAL];
        Integer[] orden = new Integer[Regiones.TOTAL];
        for (int r = 0; r < Regiones.TOTAL; r++) {
            regs[r] = Formato.registros(cfg.repositorio.resolve(Regiones.NOMBRE[r] + ".dat"));
            orden[r] = r;
        }
        if (e == Estrategia.ROUND_ROBIN)
            Arrays.sort(orden, Comparator.comparing(r -> Regiones.NOMBRE[r]));   // orden del directorio
        else
            Arrays.sort(orden, (a, b) -> Long.compare(regs[b], regs[a]));       // LPT: mayor primero

        int turno = 0;
        for (int r : orden) {
            long ini = 0, restante = regs[r];
            int ultimo = turno;
            while (restante > 0) {
                long bytes = restante * Formato.TAM_REG;
                int nodo = e == Estrategia.ROUND_ROBIN
                        ? siguienteConEspacio(libre, bytes, turno)
                        : menorCargaPonderada(cfg, libre, carga, bytes);
                long cant = restante;
                if (nodo < 0) {               // ningun nodo tiene espacio para toda la region: se parte
                    nodo = masEspacioLibre(libre);
                    cant = libre[nodo] / Formato.TAM_REG;
                    if (cant == 0)
                        throw new IllegalStateException("El cluster no tiene almacenamiento suficiente para " + Regiones.NOMBRE[r]);
                }
                plan.get(nodo).add(new Particion(r, ini, ini + cant));
                libre[nodo] -= cant * Formato.TAM_REG;
                carga[nodo] += cant * Formato.TAM_REG;
                ini += cant;
                restante -= cant;
                ultimo = nodo;
            }
            turno = (ultimo + 1) % m;
        }
        return plan;
    }

    private static int siguienteConEspacio(long[] libre, long bytes, int turno) {
        for (int k = 0; k < libre.length; k++) {
            int c = (turno + k) % libre.length;
            if (libre[c] >= bytes) return c;
        }
        return -1;
    }

    private static int menorCargaPonderada(ConfigCluster cfg, long[] libre, long[] carga, long bytes) {
        int mejor = -1;
        double vMejor = Double.MAX_VALUE;
        for (int k = 0; k < libre.length; k++) {
            if (libre[k] < bytes) continue;
            double v = (double) (carga[k] + bytes) / cfg.nodos.get(k).hilos();
            if (v < vMejor) {
                vMejor = v;
                mejor = k;
            }
        }
        return mejor;
    }

    private static int masEspacioLibre(long[] libre) {
        int mejor = 0;
        for (int k = 1; k < libre.length; k++)
            if (libre[k] > libre[mejor]) mejor = k;
        return mejor;
    }
}

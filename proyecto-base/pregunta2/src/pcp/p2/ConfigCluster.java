package pcp.p2;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Lee y valida el archivo de configuracion del cluster (cluster.properties). */
public final class ConfigCluster {

    /** Un nodo (computadora) del cluster con su capacidad de proceso y almacenamiento. */
    public record NodoConfig(int id, String nombre, int hilos, long discoBytes, Path dir) { }

    public final String nombre;
    public final Path repositorio;
    public final Path dirCluster;
    public final Estrategia estrategia;
    public final int bloqueRegistros;
    public final long umbralCentimos;
    public final List<NodoConfig> nodos = new ArrayList<>();

    public ConfigCluster(Path archivo) throws IOException {
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(archivo.toFile())) {
            p.load(in);
        }
        Path base = archivo.toAbsolutePath().getParent();
        nombre = p.getProperty("cluster.nombre", "Cluster");
        repositorio = base.resolve(req(p, "repositorio.dir"));
        dirCluster = base.resolve(p.getProperty("cluster.dir", "cluster"));
        estrategia = Estrategia.valueOf(p.getProperty("balanceo.estrategia", "PONDERADO").trim().toUpperCase());
        bloqueRegistros = Integer.parseInt(p.getProperty("bloque.registros", "10000").trim());
        umbralCentimos = Math.round(Double.parseDouble(p.getProperty("umbral.sospechoso", "30000").trim()) * 100);

        int n = Integer.parseInt(req(p, "cluster.nodos"));
        for (int i = 1; i <= n; i++) {
            String k = "nodo" + i + ".";
            int hilos = Integer.parseInt(req(p, k + "hilos"));
            long disco = Long.parseLong(req(p, k + "disco.mb")) * 1024 * 1024;
            if (hilos < 1 || disco < 1) throw new IllegalArgumentException(k + " capacidad invalida");
            nodos.add(new NodoConfig(i, req(p, k + "nombre"), hilos, disco,
                    base.resolve(p.getProperty(k + "dir", "cluster/nodo" + i))));
        }
    }

    private static String req(Properties p, String clave) {
        String v = p.getProperty(clave);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Falta '" + clave + "' en la configuracion");
        return v.trim();
    }

    public int totalHilos() {
        return nodos.stream().mapToInt(NodoConfig::hilos).sum();
    }
}

package lsdfs;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Repositorio de metadata persistente (archivo de texto clave=valor).
 * Hay un archivo .meta por cada archivo de datos y uno global (repositorio.meta).
 * Se guarda de forma atomica: primero a un .tmp y luego se reemplaza.
 */
public final class Metadata {

    // claves usadas por los archivos de region
    public static final String REGION = "region";
    public static final String ARCHIVO = "archivo";
    public static final String TOTAL = "registros.total";
    public static final String ACTIVOS = "registros.activos";
    public static final String ELIMINADOS = "registros.marcados";
    public static final String BYTES = "archivo.bytes";
    public static final String VERSION = "datos.version";
    public static final String IDX_VERSION = "indice.version";
    public static final String IDX_ENTRADAS = "indice.entradas";
    public static final String IDX_FECHA = "indice.fecha";
    public static final String PACK_FECHA = "pack.fecha";
    public static final String PACK_ELIMINADOS = "pack.eliminados";
    public static final String PACK_CRC = "pack.crc32";

    private final Path archivo;
    private final TreeMap<String, String> valores = new TreeMap<>();

    private Metadata(Path archivo) {
        this.archivo = archivo;
    }

    public static Metadata nueva(Path archivo) {
        return new Metadata(archivo);
    }

    public static Metadata cargar(Path archivo) throws IOException {
        Metadata m = new Metadata(archivo);
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(archivo, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        for (String k : p.stringPropertyNames()) m.valores.put(k, p.getProperty(k));
        return m;
    }

    public String get(String clave, String def) {
        return valores.getOrDefault(clave, def);
    }

    public long getLong(String clave, long def) {
        String v = valores.get(clave);
        return v == null ? def : Long.parseLong(v.trim());
    }

    public Metadata set(String clave, Object valor) {
        valores.put(clave, String.valueOf(valor));
        return this;
    }

    public void guardar() throws IOException {
        List<String> lineas = new ArrayList<>();
        lineas.add("# Metadata LSDFS_Bancario - " + archivo.getFileName());
        for (Map.Entry<String, String> e : valores.entrySet()) lineas.add(e.getKey() + "=" + e.getValue());
        Path tmp = archivo.resolveSibling(archivo.getFileName() + ".tmp");
        Files.write(tmp, lineas, StandardCharsets.UTF_8);
        Utilidades.reemplazar(tmp, archivo);
    }
}

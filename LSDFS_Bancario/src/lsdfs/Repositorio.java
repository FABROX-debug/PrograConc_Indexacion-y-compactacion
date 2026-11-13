package lsdfs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * El LSDFS: un directorio con, por cada region,
 *   REGION.dat  (datos, registros de longitud variable)
 *   REGION.idx  (indice ordenado por cuenta, se crea con INDEX)
 *   REGION.meta (metadata del archivo)
 * y un archivo global repositorio.meta.
 */
public final class Repositorio {

    public final Path dir;

    public Repositorio(Path dir) {
        this.dir = dir;
    }

    public Path datos(String region) {
        return dir.resolve(region + ".dat");
    }

    public Path indice(String region) {
        return dir.resolve(region + ".idx");
    }

    public Path meta(String region) {
        return dir.resolve(region + ".meta");
    }

    public Path metaGlobal() {
        return dir.resolve("repositorio.meta");
    }

    public boolean existe() {
        return Files.exists(metaGlobal());
    }

    public Metadata cargarMeta(String region) throws IOException {
        return Metadata.cargar(meta(region));
    }

    /** Regiones que tienen archivo de datos, en el orden de Regiones.NOMBRE. */
    public List<String> regiones() {
        List<String> r = new ArrayList<>();
        for (String n : Regiones.NOMBRE)
            if (Files.exists(datos(n)) && Files.exists(meta(n))) r.add(n);
        return r;
    }

    /** true si el indice de la region existe y corresponde a la version actual de los datos. */
    public boolean indiceVigente(String region) throws IOException {
        Metadata m = cargarMeta(region);
        if (!Files.exists(indice(region))) return false;
        if (m.getLong(Metadata.IDX_VERSION, -1) != m.getLong(Metadata.VERSION, 0)) return false;
        ArchivoIndice.Cabecera c = ArchivoIndice.leerCabecera(indice(region));
        return c.versionDatos() == m.getLong(Metadata.VERSION, 0) && c.bytesDatos() == Files.size(datos(region));
    }

    /** Resumen global del repositorio (se recalcula a partir de la metadata de cada archivo). */
    public record Resumen(int archivos, long total, long activos, long marcados, long bytes, int indicesVigentes) { }

    public Resumen resumen() throws IOException {
        int archivos = 0, vigentes = 0;
        long total = 0, activos = 0, marcados = 0, bytes = 0;
        for (String r : regiones()) {
            Metadata m = cargarMeta(r);
            archivos++;
            total += m.getLong(Metadata.TOTAL, 0);
            activos += m.getLong(Metadata.ACTIVOS, 0);
            marcados += m.getLong(Metadata.ELIMINADOS, 0);
            bytes += m.getLong(Metadata.BYTES, 0);
            if (indiceVigente(r)) vigentes++;
        }
        return new Resumen(archivos, total, activos, marcados, bytes, vigentes);
    }

    /** Actualiza repositorio.meta con el resumen actual (lo llama solo el hilo principal). */
    public void actualizarMetaGlobal(String operacion) throws IOException {
        Metadata g = Metadata.cargar(metaGlobal());
        Resumen r = resumen();
        g.set("archivos", r.archivos()).set("registros.total", r.total()).set("registros.activos", r.activos())
         .set("registros.marcados", r.marcados()).set("bytes.total", r.bytes())
         .set("indices.vigentes", r.indicesVigentes())
         .set("ultima.operacion", operacion).set("ultima.operacion.fecha", LocalDateTime.now().withNano(0));
        g.guardar();
    }
}

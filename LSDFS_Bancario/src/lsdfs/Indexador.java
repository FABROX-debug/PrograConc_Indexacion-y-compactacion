package lsdfs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * INDEX(): indexacion del LSDFS.
 * Por cada archivo de datos recorre los registros, toma (cuenta, offset) de los ACTIVOS,
 * los ordena por cuenta (y por offset para cuentas repetidas) y escribe REGION.idx.
 * La metadata registra para que version de los datos se construyo el indice.
 */
public final class Indexador {

    public record Resultado(String region, long registrosLeidos, long entradas, long bytesIndice) { }

    private Indexador() { }

    /** INDEX de un solo archivo: unidad de trabajo de las versiones serial y paralela. */
    public static Resultado indexarArchivo(Repositorio repo, String region) throws IOException {
        Path dat = repo.datos(region);
        Metadata m = repo.cargarMeta(region);
        long version = m.getLong(Metadata.VERSION, 0);

        // 1) recolectar (cuenta, offset) de los registros activos
        long leidos = 0;
        int n = 0;
        long[] claves = new long[1024], offs = new long[1024];
        try (ArchivoDatos.Lector in = new ArchivoDatos.Lector(dat)) {
            while (in.siguiente()) {
                leidos++;
                if (!Registro.esActivo(in.raw)) continue;
                if (n == claves.length) {
                    claves = Arrays.copyOf(claves, n * 2);
                    offs = Arrays.copyOf(offs, n * 2);
                }
                claves[n] = Registro.cuentaDe(in.raw);
                offs[n] = in.offset;
                n++;
            }
        }

        // 2) ordenar por (cuenta, offset)
        Integer[] orden = new Integer[n];
        for (int i = 0; i < n; i++) orden[i] = i;
        final long[] c = claves, o = offs;
        Arrays.sort(orden, (a, b) -> c[a] != c[b] ? Long.compare(c[a], c[b]) : Long.compare(o[a], o[b]));
        long[] cuentas = new long[n], offsets = new long[n];
        for (int i = 0; i < n; i++) {
            cuentas[i] = c[orden[i]];
            offsets[i] = o[orden[i]];
        }

        // 3) escribir a temporal y reemplazar
        Path idx = repo.indice(region);
        Path tmp = idx.resolveSibling(idx.getFileName() + ".tmp");
        ArchivoIndice.escribir(tmp, version, Files.size(dat), cuentas, offsets);
        Utilidades.reemplazar(tmp, idx);

        m.set(Metadata.IDX_VERSION, version).set(Metadata.IDX_ENTRADAS, n)
         .set(Metadata.IDX_FECHA, LocalDateTime.now().withNano(0));
        m.guardar();
        return new Resultado(region, leidos, n, Files.size(idx));
    }

    public static List<Resultado> serial(Repositorio repo) throws IOException {
        List<Resultado> rs = new ArrayList<>();
        for (String region : repo.regiones()) rs.add(indexarArchivo(repo, region));
        repo.actualizarMetaGlobal("INDEX serial");
        return rs;
    }

    public static List<Resultado> paralelo(Repositorio repo, int hilos, List<String> detalle) throws Exception {
        List<Resultado> rs = EjecutorParalelo.ejecutar(repo, hilos, region -> indexarArchivo(repo, region), detalle);
        repo.actualizarMetaGlobal("INDEX paralelo (" + hilos + " hilos)");
        return rs;
    }
}

package lsdfs;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * PACK(): compactacion del LSDFS.
 * Por cada archivo de datos:
 *   1. Lee los registros secuencialmente.
 *   2. Copia SOLO los activos (bytes exactos) a REGION.dat.tmp.
 *   3. Valida el temporal: cantidad, tamano, todos activos y CRC32 identico al de los activos leidos.
 *   4. Solo si la validacion es correcta reemplaza el original (movimiento atomico).
 *   5. Actualiza la metadata: nueva version de datos => el indice queda DESACTUALIZADO.
 * Si algo falla antes del paso 4, el archivo original queda intacto.
 */
public final class Pack {

    public record Resultado(String region, long leidos, long activos, long eliminados,
                            long bytesAntes, long bytesDespues, boolean reemplazado) { }

    private Pack() { }

    /** PACK de un solo archivo. Es la unidad de trabajo tanto de la version serial como de la paralela. */
    public static Resultado packArchivo(Repositorio repo, String region) throws IOException {
        Path dat = repo.datos(region);
        Path tmp = dat.resolveSibling(dat.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);   // restos de una ejecucion interrumpida
        long bytesAntes = Files.size(dat);

        long leidos = 0, activos = 0, bytesNuevos = ArchivoDatos.TAM_CABECERA;
        CRC32 crc = new CRC32();
        try (ArchivoDatos.Lector in = new ArchivoDatos.Lector(dat);
             DataOutputStream out = ArchivoDatos.crear(tmp)) {
            while (in.siguiente()) {
                leidos++;
                if (Registro.esActivo(in.raw)) {
                    out.write(in.raw);
                    crc.update(in.raw);
                    activos++;
                    bytesNuevos += in.raw.length;
                }
            }
        }
        long eliminados = leidos - activos;

        try {
            validarTemporal(tmp, activos, bytesNuevos, crc.getValue());
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw new IOException("PACK " + region + " cancelado, el original no se modifico: " + e.getMessage(), e);
        }

        if (eliminados == 0) {   // nada que compactar: el original se conserva y el indice sigue vigente
            Files.delete(tmp);
            return new Resultado(region, leidos, activos, 0, bytesAntes, bytesAntes, false);
        }

        Utilidades.reemplazar(tmp, dat);

        Metadata m = repo.cargarMeta(region);
        m.set(Metadata.TOTAL, activos).set(Metadata.ACTIVOS, activos).set(Metadata.ELIMINADOS, 0)
         .set(Metadata.BYTES, bytesNuevos)
         .set(Metadata.VERSION, m.getLong(Metadata.VERSION, 0) + 1)          // los offsets cambiaron
         .set(Metadata.PACK_FECHA, LocalDateTime.now().withNano(0))
         .set(Metadata.PACK_ELIMINADOS, eliminados)
         .set(Metadata.PACK_CRC, Long.toHexString(crc.getValue()));
        m.guardar();
        return new Resultado(region, leidos, activos, eliminados, bytesAntes, bytesNuevos, true);
    }

    /** Relee el temporal y comprueba que contiene exactamente los registros activos esperados. */
    private static void validarTemporal(Path tmp, long activos, long bytes, long crcEsperado) throws IOException {
        long n = 0;
        CRC32 crc = new CRC32();
        try (ArchivoDatos.Lector in = new ArchivoDatos.Lector(tmp)) {
            while (in.siguiente()) {
                if (!Registro.esActivo(in.raw)) throw new IOException("el temporal contiene un registro marcado");
                crc.update(in.raw);
                n++;
            }
        }
        if (n != activos) throw new IOException("cantidad distinta: " + n + " vs " + activos);
        if (Files.size(tmp) != bytes) throw new IOException("tamano distinto: " + Files.size(tmp) + " vs " + bytes);
        if (crc.getValue() != crcEsperado) throw new IOException("CRC32 distinto");
    }

    /** PACK serial: un solo hilo recorre los archivos uno tras otro. */
    public static List<Resultado> serial(Repositorio repo) throws IOException {
        List<Resultado> rs = new ArrayList<>();
        for (String region : repo.regiones()) rs.add(packArchivo(repo, region));
        repo.actualizarMetaGlobal("PACK serial");
        return rs;
    }

    /** PACK paralelo: varios hilos compactan archivos regionales distintos al mismo tiempo. */
    public static List<Resultado> paralelo(Repositorio repo, int hilos, List<String> detalle) throws Exception {
        List<Resultado> rs = EjecutorParalelo.ejecutar(repo, hilos, region -> packArchivo(repo, region), detalle);
        repo.actualizarMetaGlobal("PACK paralelo (" + hilos + " hilos)");
        return rs;
    }
}

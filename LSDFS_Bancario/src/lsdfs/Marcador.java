package lsdfs;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.LongPredicate;

/**
 * Marca registros para eliminacion (borrado logico): escribe ELIMINADO en el byte de
 * estado del registro con acceso directo (seek a offset + 4). El registro sigue ocupando
 * espacio hasta que se ejecute PACK(). Los offsets no cambian, asi que el indice sigue
 * siendo valido (la busqueda omite los registros marcados).
 */
public final class Marcador {

    private Marcador() { }

    /** Marca aproximadamente 'porcentaje' % de los registros activos de cada archivo. */
    public static long marcarPorcentaje(Repositorio repo, double porcentaje, long semilla) throws IOException {
        long total = 0;
        for (String region : repo.regiones()) {
            SplittableRandom rnd = new SplittableRandom(semilla * 31 + Regiones.indice(region));
            total += marcar(repo, region, cuenta -> rnd.nextDouble() * 100 < porcentaje);
        }
        repo.actualizarMetaGlobal("MARCAR " + porcentaje + "%");
        return total;
    }

    /** Marca todos los movimientos activos de una cuenta. */
    public static long marcarCuenta(Repositorio repo, long cuenta) throws IOException {
        long total = 0;
        for (String region : repo.regiones())
            total += marcar(repo, region, c -> c == cuenta);
        repo.actualizarMetaGlobal("MARCAR cuenta " + cuenta);
        return total;
    }

    private static long marcar(Repositorio repo, String region, LongPredicate criterio) throws IOException {
        List<Long> offsets = new ArrayList<>();
        long vistos = 0, activosAntes = 0;
        try (ArchivoDatos.Lector in = new ArchivoDatos.Lector(repo.datos(region))) {
            while (in.siguiente()) {
                vistos++;
                if (!Registro.esActivo(in.raw)) continue;
                activosAntes++;
                if (criterio.test(Registro.cuentaDe(in.raw))) offsets.add(in.offset);
            }
        }
        if (!offsets.isEmpty()) {
            try (RandomAccessFile raf = new RandomAccessFile(repo.datos(region).toFile(), "rw")) {
                for (long off : offsets) {
                    raf.seek(off + Registro.POS_ESTADO);
                    raf.writeByte(Registro.ELIMINADO);
                }
            }
        }
        Metadata m = repo.cargarMeta(region);
        long activos = activosAntes - offsets.size();
        m.set(Metadata.TOTAL, vistos).set(Metadata.ACTIVOS, activos).set(Metadata.ELIMINADOS, vistos - activos);
        m.guardar();
        return offsets.size();
    }
}

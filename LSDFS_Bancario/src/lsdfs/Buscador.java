package lsdfs;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Busqueda de todos los movimientos de una cuenta usando el indice.
 *  - La cuenta indica su region, asi que solo se consulta ese indice.
 *  - Si el indice no existe o quedo desactualizado (por ejemplo despues de un PACK),
 *    se reconstruye automaticamente antes de buscar.
 *  - Cada offset se lee con acceso directo y se verifica que el registro sea de esa cuenta.
 */
public final class Buscador {

    public record Movimiento(String region, long offset, Registro registro) { }

    public record Resultado(long cuenta, List<Movimiento> movimientos, int marcadosOmitidos,
                            int pasosBinarios, List<String> indicesReconstruidos, long nanos) { }

    private Buscador() { }

    public static Resultado buscar(Repositorio repo, long cuenta) throws IOException {
        long t0 = System.nanoTime();
        int r = Regiones.deCuenta(cuenta);
        List<String> regiones = r >= 0 ? List.of(Regiones.NOMBRE[r]) : repo.regiones();
        List<Movimiento> movs = new ArrayList<>();
        List<String> reconstruidos = new ArrayList<>();
        int marcados = 0, pasos = 0;
        for (String region : regiones) {
            if (!repo.regiones().contains(region)) continue;
            if (!repo.indiceVigente(region)) {
                Indexador.indexarArchivo(repo, region);
                reconstruidos.add(region);
            }
            ArchivoIndice.Hallazgo h = ArchivoIndice.buscar(repo.indice(region), cuenta);
            pasos += h.pasosBinarios();
            try (RandomAccessFile raf = new RandomAccessFile(repo.datos(region).toFile(), "r")) {
                for (long off : h.offsets()) {
                    Registro reg = Registro.deBytes(ArchivoDatos.leerEn(raf, off));
                    if (reg.cuenta != cuenta)
                        throw new IOException("Referencia invalida en " + region + ".idx: offset " + off
                                + " apunta a la cuenta " + reg.cuenta + " y no a " + cuenta);
                    if (reg.activo()) movs.add(new Movimiento(region, off, reg));
                    else marcados++;
                }
            }
        }
        return new Resultado(cuenta, movs, marcados, pasos, reconstruidos, System.nanoTime() - t0);
    }

    /** Busqueda lineal (sin indice) de los offsets ACTIVOS de una cuenta; se usa para validar. */
    public static List<Long> busquedaLineal(Repositorio repo, String region, long cuenta) throws IOException {
        List<Long> r = new ArrayList<>();
        try (ArchivoDatos.Lector in = new ArchivoDatos.Lector(repo.datos(region))) {
            while (in.siguiente())
                if (Registro.esActivo(in.raw) && Registro.cuentaDe(in.raw) == cuenta) r.add(in.offset);
        }
        return r;
    }
}

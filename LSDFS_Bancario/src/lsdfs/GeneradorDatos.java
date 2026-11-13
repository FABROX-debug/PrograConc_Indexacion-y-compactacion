package lsdfs;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.SplittableRandom;
import java.util.stream.Stream;

/**
 * Genera un repositorio pequeno de prueba (adaptado de pregunta2.GeneradorRepositorio):
 * el total pedido se reparte entre las 25 regiones de forma proporcional a su poblacion,
 * con la misma idea de cuentas por region y montos log-normales. A diferencia de
 * pregunta2, los registros son de longitud variable (titular y concepto de texto).
 *
 * Solo se ejecuta cuando el usuario lo pide: los datos se conservan entre ejecuciones.
 */
public final class GeneradorDatos {

    private static final long INICIO_2025 = 1735707600000L;   // 2025-01-01 00:00 hora de Lima
    private static final long ANIO_MS = 365L * 86_400_000L;

    private static final String[] NOMBRES = {"Ana", "Luis", "Rosa", "Jose", "Maria", "Carlos", "Lucia", "Jorge",
            "Carmen", "Miguel", "Sofia", "Pedro", "Valeria", "Juan Carlos", "Maria Fernanda", "Teodoro"};
    private static final String[] APELLIDOS = {"Quispe", "Flores", "Huaman", "Mamani", "Rojas", "Garcia",
            "Torres", "Chavez", "Vargas", "Ramirez Cardenas", "Condori", "Mendoza Villanueva", "Diaz"};
    private static final String[] CONCEPTOS = {
            "Deposito en ventanilla", "Retiro ATM", "Transferencia interbancaria CCI",
            "Pago de servicios - luz", "Pago de servicios - agua", "Abono de haberes",
            "Pago tarjeta de credito", "Yape/Plin", "Compra POS", "Pago de prestamo cuota"};

    private GeneradorDatos() { }

    /** Borra el repositorio anterior y genera 'total' registros. Devuelve el tiempo en ns. */
    public static long generar(Repositorio repo, long total, long semilla) throws IOException {
        long t0 = System.nanoTime();
        Files.createDirectories(repo.dir);
        try (Stream<Path> s = Files.list(repo.dir)) {
            for (Path p : s.toList()) {
                String n = p.getFileName().toString();
                if (n.endsWith(".dat") || n.endsWith(".idx") || n.endsWith(".meta") || n.endsWith(".tmp"))
                    Files.delete(p);
            }
        }

        long[] porRegion = repartir(total);
        for (int r = 0; r < Regiones.TOTAL; r++)
            generarRegion(repo, r, porRegion[r], semilla);

        Metadata g = Metadata.nueva(repo.metaGlobal());
        g.set("formato", "LSDF v" + ArchivoDatos.VERSION_FORMATO + " (registros de longitud variable)")
         .set("generacion.registros", total).set("generacion.semilla", semilla)
         .set("generacion.fecha", LocalDateTime.now().withNano(0));
        g.guardar();
        repo.actualizarMetaGlobal("GENERAR " + total);
        return System.nanoTime() - t0;
    }

    /** Reparto proporcional a la poblacion por el metodo del mayor residuo (la suma es exacta). */
    static long[] repartir(long total) {
        long pob = 0;
        for (int p : Regiones.POBLACION_MILES) pob += p;
        long[] n = new long[Regiones.TOTAL];
        double[] resto = new double[Regiones.TOTAL];
        long asignado = 0;
        for (int r = 0; r < Regiones.TOTAL; r++) {
            double exacto = (double) total * Regiones.POBLACION_MILES[r] / pob;
            n[r] = (long) Math.floor(exacto);
            resto[r] = exacto - n[r];
            asignado += n[r];
        }
        for (long falta = total - asignado; falta > 0; falta--) {
            int mejor = 0;
            for (int r = 1; r < Regiones.TOTAL; r++) if (resto[r] > resto[mejor]) mejor = r;
            n[mejor]++;
            resto[mejor] = -1;
        }
        return n;
    }

    private static void generarRegion(Repositorio repo, int region, long n, long semilla) throws IOException {
        SplittableRandom rnd = new SplittableRandom(semilla * 1000 + region);
        int cuentas = (int) Math.max(3, n / 8);            // ~8 movimientos por cuenta: hay cuentas repetidas
        String nombre = Regiones.NOMBRE[region];
        long bytes = ArchivoDatos.TAM_CABECERA;
        try (DataOutputStream out = ArchivoDatos.crear(repo.datos(nombre))) {
            for (long i = 0; i < n; i++) {
                long cuenta = Regiones.primeraCuenta(region) + rnd.nextInt(cuentas);
                double p = rnd.nextDouble();
                byte tipo = (byte) (p < 0.50 ? 0 : p < 0.85 ? 1 : 2);
                double q = rnd.nextDouble();
                byte canal = (byte) (q < 0.25 ? 0 : q < 0.60 ? 1 : 2);
                double monto = rnd.nextDouble() < 0.0015
                        ? 30_000 + rnd.nextDouble() * 120_000
                        : Math.exp(Math.log(250) + 1.1 * normal(rnd));
                String concepto = CONCEPTOS[rnd.nextInt(CONCEPTOS.length)];
                if (rnd.nextBoolean()) concepto += " Ref." + rnd.nextInt(1_000_000);   // longitud variable
                Registro reg = new Registro(Registro.ACTIVO, cuenta, INICIO_2025 + rnd.nextLong(ANIO_MS),
                        tipo, canal, Math.round(monto * 100), titularDe(cuenta), concepto);
                byte[] raw = reg.aBytes();
                out.write(raw);
                bytes += raw.length;
            }
        }
        Metadata m = Metadata.nueva(repo.meta(nombre));
        m.set(Metadata.REGION, nombre).set(Metadata.ARCHIVO, nombre + ".dat")
         .set(Metadata.TOTAL, n).set(Metadata.ACTIVOS, n).set(Metadata.ELIMINADOS, 0)
         .set(Metadata.BYTES, bytes).set(Metadata.VERSION, 1).set(Metadata.IDX_VERSION, -1);
        m.guardar();
    }

    /** El titular depende solo de la cuenta: todos los movimientos de una cuenta tienen el mismo titular. */
    static String titularDe(long cuenta) {
        return NOMBRES[(int) (cuenta % NOMBRES.length)] + " " + APELLIDOS[(int) ((cuenta / 7) % APELLIDOS.length)];
    }

    private static double normal(SplittableRandom rnd) {
        double u1 = 1.0 - rnd.nextDouble(), u2 = rnd.nextDouble();
        return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }
}

package lsdfs;

/**
 * REUTILIZADA de pregunta2 (pcp.p2.Regiones): regiones del Peru y su poblacion
 * (Censo INEI 2017, miles). Se agrega la relacion cuenta -> region: el numero de
 * cuenta se genera como (region + 1) * 10_000_000 + correlativo, igual que en pregunta2.
 */
public final class Regiones {

    public static final String[] NOMBRE = {
        "LIMA", "PIURA", "LA_LIBERTAD", "AREQUIPA", "CAJAMARCA", "JUNIN", "CUSCO",
        "LAMBAYEQUE", "PUNO", "ANCASH", "CALLAO", "LORETO", "ICA", "SAN_MARTIN",
        "HUANUCO", "AYACUCHO", "UCAYALI", "APURIMAC", "AMAZONAS", "HUANCAVELICA",
        "TACNA", "PASCO", "TUMBES", "MOQUEGUA", "MADRE_DE_DIOS"
    };

    public static final int[] POBLACION_MILES = {
        9485, 1857, 1778, 1383, 1341, 1247, 1206,
        1198, 1173, 1084, 994, 883, 851, 814,
        722, 617, 497, 406, 380, 348,
        330, 255, 225, 175, 141
    };

    public static final int TOTAL = NOMBRE.length;
    public static final long BASE_CUENTA = 10_000_000L;

    private Regiones() { }

    public static long primeraCuenta(int region) {
        return (region + 1) * BASE_CUENTA;
    }

    /** Region a la que pertenece una cuenta, o -1 si el numero no corresponde a ninguna. */
    public static int deCuenta(long cuenta) {
        long r = cuenta / BASE_CUENTA - 1;
        return r >= 0 && r < TOTAL ? (int) r : -1;
    }

    public static int indice(String nombre) {
        for (int i = 0; i < TOTAL; i++)
            if (NOMBRE[i].equals(nombre)) return i;
        return -1;
    }
}

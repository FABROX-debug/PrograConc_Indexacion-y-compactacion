package pcp.p2;

/** Regiones del Peru (24 departamentos + Callao) y su poblacion (Censo INEI 2017, miles). */
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

    private Regiones() { }
}

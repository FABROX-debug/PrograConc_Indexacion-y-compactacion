package pcp.p2;

/** Estrategias de balanceo de carga entre los nodos del cluster. */
public enum Estrategia {
    /** Reparte las regiones por turnos sin considerar la capacidad (linea base). */
    ROUND_ROBIN,
    /** LPT ponderado: cada region al nodo con menor (carga+tamano)/capacidad con disco libre. */
    PONDERADO,
    /** PONDERADO + robo de bloques: el nodo que termina antes ayuda a los demas. */
    DINAMICO
}

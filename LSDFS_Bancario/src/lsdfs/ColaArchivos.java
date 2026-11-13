package lsdfs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * Cola compartida de archivos regionales pendientes.
 * ADAPTADA de pregunta2:
 *  - de Balanceador (estrategia PONDERADO/LPT): los archivos se ordenan de MAYOR a MENOR
 *    tamano, para que el archivo grande (LIMA) empiece primero y no quede al final.
 *  - de ColaBloques: el acceso a la cola esta sincronizado y cada hilo, al terminar un
 *    archivo, toma el siguiente (reparto dinamico: el hilo libre siempre trabaja).
 * La unidad de trabajo es un archivo completo: dos hilos nunca tocan el mismo archivo.
 */
public final class ColaArchivos {

    private final Deque<String> pendientes = new ArrayDeque<>();

    public ColaArchivos(Repositorio repo, List<String> regiones) {
        List<String> orden = new ArrayList<>(regiones);
        orden.sort(Comparator.comparingLong((String r) -> tamano(repo, r)).reversed());
        pendientes.addAll(orden);
    }

    private static long tamano(Repositorio repo, String region) {
        try {
            return Files.size(repo.datos(region));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Siguiente archivo a procesar, o null si ya no queda trabajo. */
    public synchronized String tomar() {
        return pendientes.pollFirst();
    }
}

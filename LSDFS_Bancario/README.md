# LSDFS_Bancario: PACK() e INDEX() (primera versión)

Repositorio de movimientos bancarios regionales (25 archivos, uno por región del Perú) con
registros de **longitud variable**, **metadata persistente**, compactación (**PACK**) e
indexación (**INDEX**) en versión serial y paralela. Usa solo Java estándar (probado con JDK 23).
No modifica `pregunta1` ni `pregunta2`.

## Compilar y ejecutar (desde esta carpeta)

```
javac -encoding UTF-8 -d out src/lsdfs/*.java
java -cp out lsdfs.Main                      # menú interactivo
java -cp out lsdfs.Pruebas                   # validación con 100, 1000 y 10000 registros
java -cp out lsdfs.Pruebas 500 20000         # validación con otros tamaños
```

También se pueden usar comandos sin el menú:

```
java -cp out lsdfs.Main generar 1000         # no reemplaza un repositorio existente (usar --forzar)
java -cp out lsdfs.Main marcar 20            # marca el 20 % de los activos
java -cp out lsdfs.Main marcar-cuenta 10000005
java -cp out lsdfs.Main pack-serial | pack-paralelo 4 | index-serial | index-paralelo 4
java -cp out lsdfs.Main buscar 10000005
java -cp out lsdfs.Main comparar 4 3         # 4 hilos, 3 repeticiones (sobre copias)
java -cp out lsdfs.Main estado
```

## Archivos que se generan

| Ruta | Contenido |
|------|-----------|
| `datos/REGION.dat` | Datos: cabecera de 8 bytes + registros de longitud variable |
| `datos/REGION.idx` | Índice: entradas (cuenta, offset) ordenadas por cuenta |
| `datos/REGION.meta` | Metadata del archivo: totales, bytes, versión de datos, versión del índice, último PACK |
| `datos/repositorio.meta` | Metadata global |
| `trabajo/serial`, `trabajo/paralelo` | Copias usadas por "Comparar tiempos" |
| `pruebas/nN/...` | Repositorios de la validación automática |
| `resultados/validacion.txt`, `resultados/tiempos.txt` | Resultados medidos |

Formato del registro (big-endian): `int longitud | byte estado (1 activo, 0 marcado) | long cuenta |
long fecha | byte tipo | byte canal | long monto en céntimos | short+UTF-8 titular | short+UTF-8 concepto`.

Formato del índice: cabecera `int "LIDX" | long versión de datos | long bytes del .dat | long n`,
después n entradas de 16 bytes `long cuenta | long offset`. La búsqueda binaria se hace
directamente sobre el archivo con `RandomAccessFile`.

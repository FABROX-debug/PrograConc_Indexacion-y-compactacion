# Indexación y Compactación de Datos a Gran Escala

**Curso:** Programación Concurrente y Paralela
**Entrega:** · propuesta de diseño

> **Estado:** hay una **primera versión funcional de PACK() e INDEX()** (serial y paralela) en
> [`LSDFS_Bancario`](LSDFS_Bancario), validada con 100, 1 000 y 10 000 registros (ver sección 8).
> El proyecto anterior se conserva sin cambios en `proyecto-base/pregunta2`.

---

## 1. Problema

Un repositorio de datos a gran escala es un conjunto de archivos F1 … FN de acceso directo, gestionados con índices y metadata. En este tipo de repositorios aparecen dos problemas:

| Problema | Descripción | Operación propuesta |
|---|---|---|
| Almacenamiento | Los registros marcados para eliminación siguen ocupando espacio en el archivo. | **PACK()**: compactación |
| Búsqueda | Sin índice, para encontrar un registro hay que revisar el archivo desde el inicio. | **INDEX()**: indexación |

**Objetivo:** proponer e implementar PACK() e INDEX() con programación paralela, con una versión serial para comparar.

## 2. Proyecto base: procesamiento bancario paralelo

Usamos la **pregunta 2** de nuestro proyecto anterior ([`proyecto-base/pregunta2`](proyecto-base/pregunta2)). El programa:

- Genera **25 archivos regionales** (`LIMA.dat` … `TUMBES.dat`) con unos **8,8 millones de movimientos bancarios de prueba**. Son datos sintéticos, no transacciones reales.
- Guarda cada movimiento como un registro binario de 40 bytes y lo lee **por bloques** con `RandomAccessFile`.
- Tiene una **versión serial** y una **versión paralela**. En la paralela, hilos (`Thread` + `ExecutorService`) simulan un clúster de 4 nodos dentro de una misma máquina.
- Reparte el trabajo con tres estrategias de **balanceo de carga**: `ROUND_ROBIN`, `PONDERADO` y `DINAMICO`.
- Calcula speedup y eficiencia, y verifica que el resultado paralelo sea igual al serial.

### Componentes que reutilizaremos

| Componente (`pcp.p2`) | Qué hace hoy | Uso propuesto |
|---|---|---|
| `GeneradorRepositorio` | Crea los archivos de cada región | Generar datos con registros marcados para eliminar |
| `Balanceador` | Reparte las regiones según la capacidad de cada nodo | Repartir los archivos en PACK e INDEX |
| `NodoCluster` | Procesa bloques de datos con varios hilos | Ejecutar PACK e INDEX en paralelo |
| `ProcesadorSerial` | Procesa todo el repositorio con un solo hilo | Comparar la versión serial con la paralela |

## 3. Propuesta de compactación: PACK()

1. Cada registro tendrá un campo de **estado** (activo o eliminado). Marcar un registro no lo borra del archivo.
2. PACK() revisa el estado de cada registro y copia solo los registros activos a un archivo nuevo.
3. Luego reemplaza el archivo original y actualiza la metadata.
4. **En paralelo:** los archivos regionales se reparten entre varios hilos.

*Ejemplo ilustrativo:* de 100 registros con 20 marcados quedarían 80. No es un resultado experimental.

Como los registros cambian de posición, después de PACK() hay que actualizar los índices.

## 4. Propuesta de indexación: INDEX()

Un índice guarda la clave (por ejemplo, el número de cuenta) y la posición del registro en el archivo, ordenados por la clave.

| Cuenta (ordenada) | Posición en el archivo |
|---|---|
| 10 001 873 | 1 |
| 10 007 391 | 3 |
| **10 045 210** | **0** |
| 10 090 006 | 4 |
| 10 120 554 | 2 |

Para buscar la cuenta `10 045 210`, se aplica **búsqueda binaria** sobre el índice, se obtiene la posición 0 y se lee directamente ese registro.
**En paralelo:** se crearán los índices de varios archivos regionales al mismo tiempo, y se volverán a crear después de PACK().

## 5. Estado actual del avance

| Terminado (LSDFS_Bancario) | Pendiente |
|---|---|
| Registro de longitud variable con campo de estado y textos variables | Paralelizar dentro de un mismo archivo (hoy LIMA lo procesa un solo hilo) |
| Metadata persistente por archivo y global | Medir con volúmenes grandes y repeticiones para obtener speedup confiable |
| Generador con cantidad elegida y datos que se conservan entre ejecuciones | Recuperar al iniciar los `.tmp` de un PACK interrumpido |
| PACK() serial y paralelo, con validación antes de reemplazar el archivo | Índices por otros campos (fecha, monto) |
| INDEX() serial y paralelo, búsqueda binaria y reconstrucción del índice desactualizado | |
| Pruebas automáticas y medición de tiempos | |

## 6. Próximas actividades

1. Paralelizar PACK e INDEX dentro de los archivos grandes.
2. Probar con volúmenes mayores, hasta los 8,8 millones de registros.
3. Repetir las mediciones y calcular speedup y eficiencia.

## 7. Cómo ejecutar el proyecto anterior

**Requisitos:** JDK 17 o superior (se desarrolló con JDK 23) y unos **700 MB libres** en disco.
Al iniciar, el programa genera `repositorio/` (unos 340 MB) y copia los datos a `cluster/`. Estas carpetas no se suben al repositorio.

**Desde la terminal** (dentro de `proyecto-base/pregunta2`):

```bash
javac -d out src/pcp/p2/*.java
java -cp out pcp.p2.Main            # menú interactivo
java -cp out pcp.p2.Main serial     # solo versión serial
java -cp out pcp.p2.Main comparar   # serial + las 3 estrategias de balanceo
```

**Desde IntelliJ IDEA:** abrir la carpeta `proyecto-base/pregunta2` y ejecutar la clase `pcp.p2.Main`. El directorio de trabajo debe ser `proyecto-base/pregunta2`, porque ahí está `cluster.properties`.

La configuración del clúster simulado (hilos y disco de cada nodo, estrategia y tamaño de bloque) está en [`cluster.properties`](proyecto-base/pregunta2/cluster.properties).

## 8. Implementación: LSDFS_Bancario

Proyecto independiente en Java estándar con un menú de consola para generar datos, marcar registros, ejecutar PACK e INDEX en versión serial y paralela, buscar movimientos por cuenta y comparar tiempos. Los detalles de formato están en [`LSDFS_Bancario/README.md`](LSDFS_Bancario/README.md).

```bash
cd LSDFS_Bancario
javac -encoding UTF-8 -d out src/lsdfs/*.java
java -cp out lsdfs.Main       # menú interactivo
java -cp out lsdfs.Pruebas    # validación con 100, 1000 y 10000 registros
```

Los resultados de la validación y los tiempos medidos están en [`LSDFS_Bancario/resultados`](LSDFS_Bancario/resultados). Con estos tamaños, los tiempos dependen sobre todo del costo fijo por archivo y no de la cantidad de registros, así que todavía no sirven como medida de speedup.

## Estructura

```
.
├── README.md
├── LSDFS_Bancario/                   # PACK() e INDEX() serial y paralelo
│   ├── src/lsdfs/*.java
│   └── resultados/                   # validación y tiempos medidos
├── docs/
│   ├── Avance_50_Simplificado.pptx   # presentación del avance
│   ├── Avance_50_Indexacion_Compactacion.pptx
│   └── TRABAJO DE APLICACIÓN - ... .pdf  # enunciado del trabajo
└── proyecto-base/
    └── pregunta2/                    # proyecto anterior (sin cambios)
        ├── cluster.properties
        ├── pregunta2.iml
        └── src/pcp/p2/*.java
```

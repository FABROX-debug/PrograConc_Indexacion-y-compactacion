# Indexación y Compactación de Datos a Gran Escala

**Curso:** Programación Concurrente y Paralela
**Entrega:** · propuesta de diseño

> **Estado:** este avance es teórico y de diseño. **PACK() e INDEX() todavía no están implementados.**
> El código de este repositorio es el proyecto anterior, que usaremos como base.

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

| Ya existe (proyecto anterior) | Por adaptar | Por implementar |
|---|---|---|
| 25 archivos regionales de acceso directo | Formato del registro (campo de estado y longitud variable) | Metadata |
| Lectura por bloques | Generador de datos con registros marcados | PACK() serial y paralelo |
| Versión serial y paralela con hilos | Reparto de trabajo para PACK e INDEX | INDEX() serial y paralelo |
| Balanceo de carga y métricas | | Pruebas y medición de tiempos |

## 6. Próximas actividades

1. Definir el formato del registro con campo de estado y la estructura de la metadata.
2. Adaptar el generador para crear registros marcados para eliminar.
3. Implementar PACK() serial y luego la versión paralela.
4. Implementar INDEX() serial, la búsqueda binaria y luego la versión paralela.
5. Comparar tiempos serial y paralelo, y documentar los resultados.

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

## Estructura

```
.
├── README.md
├── docs/
│   └── Avance_50_Simplificado.pptx   # presentación del avance
└── proyecto-base/
    └── pregunta2/                    # proyecto anterior (sin cambios)
        ├── cluster.properties
        ├── pregunta2.iml
        └── src/pcp/p2/*.java
```

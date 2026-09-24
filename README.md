# MakerOnline Uploader

Automatiza la subida de modelos 3D a [MakerOnline](https://www.makeronline.com). Preparas una carpeta por modelo (archivo del modelo, portada y un `info.json` con los textos) y el programa rellena el formulario de subida y lo guarda como **borrador**. Después tú lo revisas y lo publicas desde la web.

Usa [Playwright](https://playwright.dev/java/) para controlar tu Google Chrome real.

## Requisitos

- **Java 21** o superior
- **Google Chrome** instalado (se usa el Chrome real, no el Chromium de Playwright)
- **Maven**, o un IDE que lo incluya (IntelliJ IDEA lo trae integrado)
- Una cuenta de MakerOnline (el inicio de sesión se hace con Google)

La primera vez que se ejecuta, Playwright descarga sus drivers, así que puede tardar un poco.

## Cómo ejecutar

Hay tres programas (clases con `main`). Puedes lanzarlos de dos formas.

**Desde un IDE (IntelliJ IDEA):** abre la carpeta del proyecto, deja que importe el `pom.xml` y ejecuta la clase deseada con el botón de *Run* junto al `main`. Los argumentos se ponen en *Edit Configurations → Program arguments*.

**Desde la terminal con Maven**, en la carpeta del proyecto:

```bash
mvn compile exec:java -Dexec.mainClass=dev.jefe.makeronline.<Clase>
```

Para pasar argumentos se añade `-Dexec.args="..."`. En PowerShell hay que poner las opciones `-D` entre comillas: `"-Dexec.mainClass=..."`.

## Uso

### 1. Guardar tu sesión (solo la primera vez)

Ejecuta `SaveSession`:

```bash
mvn compile exec:java -Dexec.mainClass=dev.jefe.makeronline.SaveSession
```

Se abre Chrome en MakerOnline. Pulsa **Iniciar sesión con Google**, elige tu cuenta y, cuando ya estés dentro, vuelve a la terminal y pulsa **ENTER**.

La sesión se guarda en la carpeta `chrome-profile/`. Es un perfil de Chrome aparte, no toca el tuyo de diario. Si la sesión caduca, repite este paso.

> ⚠️ `chrome-profile/` contiene tu sesión: quien la tenga puede entrar en tu cuenta. Está en el `.gitignore`; no la subas a git ni la compartas.

### 2. Preparar los modelos

Cada modelo es una carpeta dentro de `subidas/pendientes/`. El nombre de la carpeta **no puede tener espacios** y los archivos de dentro tienen que llamarse igual que la carpeta:

```
subidas/pendientes/
└── llavero-gato/
    ├── llavero-gato.stl      ← el modelo: .stl o .3mf (solo uno)
    ├── llavero-gato.jpg      ← la portada: .jpg, .jpeg o .png (solo una)
    └── info.json             ← los textos
```

Si el modelo es un `.3mf`, también se rellena el perfil de impresión (usando el mismo título y la misma imagen de portada).

#### `info.json`

```json
{
  "title": "Llavero gato",
  "description": "Llavero con forma de gato.\nSe imprime sin soportes.",
  "tags": ["gato", "llavero", "animales"],
  "category": ["Toys&Games", "Action Figures"],
  "license": "CC0 (aka CC Zero)",
  "printType": "FDM"
}
```

| Campo | Obligatorio | Descripción |
|---|---|---|
| `title` | Sí | Título del modelo |
| `description` | Sí | Descripción. Los saltos de línea (`\n`) se respetan |
| `tags` | Sí | Lista de etiquetas, al menos una |
| `category` | Sí | Exactamente 2 niveles: categoría y subcategoría, tal como aparecen en la web |
| `license` | No | Por defecto `CC0 (aka CC Zero)`. Es el principio del texto de la opción de licencia |
| `printType` | No | Por defecto `FDM` |

### 3. Subir

Ejecuta `Uploader`:

```bash
mvn compile exec:java -Dexec.mainClass=dev.jefe.makeronline.Uploader
```

Primero valida todas las carpetas de `pendientes/` y después sube las que estén correctas, abriendo Chrome y rellenando el formulario. Verás el progreso paso a paso en la terminal.

Argumentos opcionales:

| Argumento | Efecto |
|---|---|
| `--revisar` | Se detiene justo antes de guardar el borrador para que mires el formulario. Pulsa **Resume** en el Playwright Inspector para continuar |
| `nombre-carpeta ...` | Procesa solo esas carpetas de `pendientes/` (sin nombres, procesa todas) |

Ejemplo: `-Dexec.args="--revisar llavero-gato"`

**Recomendado la primera vez:** usa `--revisar` para comprobar que todo se rellena bien antes de guardar.

### 4. Resultado

Al terminar, cada carpeta se mueve según lo ocurrido, con la fecha delante:

| Carpeta | Contenido |
|---|---|
| `subidas/subidos/` | Modelos guardados como borrador. Incluye `resultado.png`, una captura del formulario |
| `subidas/errores/` | Modelos que fallaron. Incluye `error.txt` con el motivo y, si falló la subida, `error.png` con una captura del momento del fallo |

Un modelo puede acabar en `errores/` por un problema de validación (falta un archivo, `info.json` mal formado, nombre con espacios...) o porque falló algo durante la subida. Corrige el problema, devuelve la carpeta a `pendientes/` (quita el prefijo de fecha si quieres) y vuelve a ejecutar.

Recuerda que lo subido queda como **borrador**: entra en tu cuenta de MakerOnline para revisarlo y publicarlo.

## Otros programas

- **`Codegen`**: abre Chrome con tu sesión y el Playwright Inspector para grabar clics. Sirve para obtener los selectores nuevos si MakerOnline cambia su formulario.

## Estructura del proyecto

```
src/main/java/dev/jefe/makeronline/
├── SaveSession.java   Guarda tu sesión de Google/MakerOnline
├── Uploader.java      Recorre pendientes/ y sube los modelos
├── ModelFolder.java   Valida la carpeta de un modelo
├── ModelMeta.java     Contenido de info.json
├── Browsers.java      Abre Chrome con el perfil persistente
└── Codegen.java       Grabador de selectores
subidas/
├── pendientes/        Modelos por subir
├── subidos/           Modelos ya guardados como borrador
└── errores/           Modelos que fallaron
chrome-profile/        Tu sesión (se crea sola, ignorada por git)
```

El contenido de `subidas/` no se sube al repositorio (ver `.gitignore`).

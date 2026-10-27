# MakerOnline Uploader & Downloader

Dos herramientas en Java para [MakerOnline](https://www.makeronline.com), la web de modelos 3D de Anycubic:

- **Uploader**: sube tus modelos 3D. Preparas una carpeta por modelo (archivo, portada y un `info.json` con los textos) y el programa rellena el formulario y lo guarda como **borrador** para que lo revises y publiques tú.
- **Downloader**: repasa una lista de usuarios y descarga sus modelos nuevos, los que aún no tienen tu "me gusta". Al terminar cada descarga les da like, así la próxima vez no los repite.

Usa [Playwright](https://playwright.dev/java/) para manejar tu Google Chrome real con tu propia cuenta.

> **Aviso.** Proyecto personal, no oficial y sin relación con Anycubic ni MakerOnline. Úsalo con tu propia cuenta, a un ritmo razonable y respetando los términos de MakerOnline y la **licencia de cada modelo** (muchas no permiten volver a publicarlo ni venderlo). El programa **no se salta captchas**: si MakerOnline pide uno, lo resuelves tú o el programa se para.

---

## Inicio rápido

### 1. Requisitos

- **Java 21** o superior (`java -version`)
- **Maven** (`mvn -version`), o un IDE que lo incluya, como IntelliJ IDEA
- **Google Chrome** instalado: se usa el Chrome real, no el Chromium de Playwright
- Una cuenta de MakerOnline (el inicio de sesión se hace con Google)

Está probado en Windows. En macOS y Linux debería funcionar igual, pero no está probado.

### 2. Descargar y compilar

```bash
git clone https://github.com/jesuscarrascofdzdev/makerOnlineUploader.git
cd makerOnlineUploader
mvn -q compile
```

La primera vez, Maven descarga las dependencias y Playwright sus drivers, así que tarda un poco.

### 3. Guardar tu sesión (solo la primera vez; **lo tiene que hacer una persona**)

```bash
mvn -q compile exec:java -Dexec.mainClass=dev.jefe.makeronline.SaveSession
```

Se abre Chrome en MakerOnline. Pulsa **Iniciar sesión con Google**, entra con tu cuenta y, cuando ya estés dentro, vuelve a la terminal y pulsa **ENTER**.

La sesión se guarda en `chrome-profile/`, un perfil de Chrome aparte que no toca el tuyo de diario. Si la sesión caduca, repite este paso.

> ⚠️ `chrome-profile/` es tu sesión: quien la tenga puede entrar en tu cuenta. Está en el `.gitignore`. No la subas a ningún sitio ni la compartas.

### 4. Usarlo

| Quiero... | Comando |
|---|---|
| Subir los modelos de `subidas/pendientes/` | `mvn -q compile exec:java -Dexec.mainClass=dev.jefe.makeronline.Uploader` |
| Ver qué descargaría el Downloader, sin descargar nada | `mvn -q compile exec:java -Dexec.mainClass=dev.jefe.makeronline.Downloader -Dexec.args="--simular"` |
| Descargar | `mvn -q compile exec:java -Dexec.mainClass=dev.jefe.makeronline.Downloader` |

**En PowerShell** hay que poner entre comillas cada opción que empieza por `-D`:

```powershell
mvn -q compile exec:java "-Dexec.mainClass=dev.jefe.makeronline.Downloader" "-Dexec.args=--simular"
```

**Desde IntelliJ IDEA:** abre la carpeta, deja que importe el `pom.xml` y pulsa *Run* junto al `main` de la clase que quieras. Los argumentos van en *Edit Configurations → Program arguments*.

---

## Uploader: subir modelos

### Preparar los modelos

Cada modelo es una carpeta dentro de `subidas/pendientes/`. El nombre de la carpeta **no puede tener espacios** y los archivos de dentro se llaman igual que la carpeta:

```
subidas/pendientes/
└── llavero-gato/
    ├── llavero-gato.stl      ← el modelo: .stl o .3mf (solo uno)
    ├── llavero-gato.jpg      ← la portada: .jpg, .jpeg o .png (solo una)
    └── info.json             ← los textos
```

Si el modelo es un `.3mf`, también se rellena el perfil de impresión, con el mismo título y la misma portada.

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
| `category` | Sí | Exactamente 2 niveles, categoría y subcategoría, tal como aparecen en la web |
| `license` | No | Por defecto `CC0 (aka CC Zero)`. Es el principio del texto de la opción de licencia |
| `printType` | No | Por defecto `FDM` |

### Subir

Primero valida todas las carpetas de `pendientes/` y después sube las correctas, con Chrome a la vista. El progreso sale paso a paso en la terminal.

| Argumento | Efecto |
|---|---|
| `--revisar` | Se para justo antes de guardar el borrador para que mires el formulario. Pulsa **Resume** en el Playwright Inspector para seguir. **Recomendado la primera vez** |
| `--esperar` | Si el Downloader está en marcha, espera a que termine en vez de salir |
| `nombre-carpeta ...` | Procesa solo esas carpetas de `pendientes/` (sin nombres, todas) |

Ejemplo: `-Dexec.args="--revisar llavero-gato"`

### Resultado

Cada carpeta se mueve según lo ocurrido, con la fecha delante:

| Carpeta | Contenido |
|---|---|
| `subidas/subidos/` | Guardados como borrador. Incluye `resultado.png`, una captura del formulario |
| `subidas/errores/` | Fallaron. Incluye `error.txt` con el motivo y, si falló la subida, `error.png` con una captura del momento |

Para reintentar uno que falló, corrige el problema, devuelve la carpeta a `pendientes/` (quitando el prefijo de fecha si quieres) y vuelve a ejecutar. Lo subido queda como **borrador**: entra en MakerOnline para revisarlo y publicarlo.

---

## Downloader: descargar modelos de otros usuarios

Repasa los usuarios de tu lista y descarga sus modelos que **todavía no tienen tu "me gusta"**. Al acabar cada descarga les da like, así que el like hace de marca de "ya descargado".

Chrome trabaja **fuera de la pantalla**: no ocupa sitio (solo se ve en la barra de tareas) y **solo aparece delante si MakerOnline pide un captcha**, para que lo resuelvas tú. Luego se vuelve a apartar solo. No se minimiza porque Chrome minimizado puede dejar de pintar la página y el programa se colgaría.

### La lista: `usuarios.json`

Está en la raíz del proyecto. Si no existe, el Downloader la crea vacía la primera vez y te avisa. Es un array con un usuario por línea:

```json
[
  "@NombreUsuario_1234567 / NombreVisible",
  "@OtroUsuario_7654321 / Otro",
  // "@UnoQueNoQuieroAhora_1111111 / Pausado",   ← las líneas con // se ignoran
]
```

**Lo más seguro es poner el @ y el nombre visible**, separados por `/`. Los dos datos se ven en el perfil del usuario. El buscador de MakerOnline busca por el nombre visible, no por el @, y el @ conserva el nombre de cuenta original: alguien puede tener el @ `@User_1234567` y llamarse "Pepe". Con los dos datos, el programa busca por el nombre y se queda con la cuenta cuyo @ coincide exactamente, aunque haya varios "Pepe".

También valen solo el @, solo el nombre o la URL del perfil (`https://www.makeronline.com/en/user/personalInfo/<id>.html`). Si no encuentra a alguien, te dice cómo escribirlo; si hay varios con el mismo nombre, te los lista. Se admiten comentarios `//` y una coma de más al final.

### Opciones

Sin argumentos repasa **todos** los usuarios de la lista y de cada uno mira sus **10 últimos modelos**. Si pasas un usuario como argumento (`-Dexec.args="@NombreUsuario_1234567 / NombreVisible"`), solo revisa ese.

| Argumento | Efecto |
|---|---|
| `--simular` | Solo lista lo que descargaría. No descarga ni da like. **Recomendado la primera vez** |
| `--max=N` | Como mucho N descargas en esta ejecución, sumando todos los usuarios. Para probar: `--max=1` |
| `--ultimos=N` | Cuántos de los últimos modelos de cada usuario se miran. Por defecto 10 |
| `--todos` | Mira todos los modelos de cada usuario (más lento; útil al añadir a alguien nuevo) |
| `--espera=S` | Pausa entre descargas: al azar entre S y S+20 s. Por defecto 10, o sea **10-30 s**. `--espera=0` la quita |
| `--perfil` | Descarga también el perfil de impresión (`.3mf`) además de los archivos del modelo |
| `--visible` | Chrome a la vista todo el rato, para ver qué hace |
| `--sin-ventana` | Chrome sin ventana (headless). Si sale un captcha no se puede resolver, así que se para |
| `--esperar` | Si el Uploader está en marcha, espera a que termine en vez de salir |

### Qué hace con cada modelo

1. Abre su página y comprueba que no tiene tu like.
2. En **Raw Model Files** pulsa **Download** (o **Download ▾ → Download All** si hay varios archivos). Si el modelo solo tiene perfil de impresión, descarga el perfil.
3. Guarda la portada y un `info.json` con título, autor, URL, licencia, tags y descripción.
4. Le da like y lo apunta en `descargados.txt`.
5. Hace la pausa antes del siguiente.

**La pausa** es de 10 a 30 s al azar y se cuenta desde que **empezó** el modelo anterior, así que lo que tarda en bajar ya cuenta. Es siempre la misma, aunque salgan captchas. Si un modelo ya tenía like, no hay pausa.

**Captcha:** la ventana de Chrome aparece y la terminal avisa. Tienes 5 minutos para resolverlo y el programa sigue solo. Si no lo resuelves a tiempo (o usas `--sin-ventana`), se para con todos los usuarios.

**No repite descargas.** Cada descarga queda apuntada en `descargados.txt`, en la raíz del proyecto (id, usuario, título y fecha), y lo que está ahí nunca se vuelve a bajar, aunque el like falle o vacíes `modelos-descargados/`. Para volver a bajar un modelo, borra su línea. Si un modelo se descargó pero se quedó sin like, la siguiente vez solo le da el like.

### Dónde queda todo

```
modelos-descargados/
├── NombreVisible/
│   ├── Título del modelo_123456/
│   │   ├── archivo.3mf        (o .stl / .zip, según lo que suba el autor)
│   │   ├── portada.jpg
│   │   └── info.json
│   └── errores.txt            (solo si algo falló, junto con error_<id>.png)
└── Otro/
    └── ...
```

---

## Uno a la vez

`Uploader`, `Downloader`, `SaveSession` y `Codegen` usan el mismo perfil de Chrome, así que **no pueden funcionar a la vez**. El primero que arranca bloquea `.agente.lock`. Si lanzas otro, te dice cuál está en marcha y sale con código 2. Con `--esperar` (en `Uploader` y `Downloader`) espera a que quede libre y arranca solo.

El bloqueo lo libera el sistema operativo al terminar el programa, aunque se cierre a la fuerza, así que nunca se queda pillado. No borres `.agente.lock` mientras algo esté en marcha.

## Problemas frecuentes

| Mensaje o síntoma | Qué pasa | Qué hacer |
|---|---|---|
| `No hay sesión guardada` / `No hay sesión iniciada... o ha caducado` | Falta `chrome-profile/` o la sesión caducó | Ejecuta `SaveSession` (paso 3) |
| `Ya hay otro programa del agente en marcha` | Otro programa del proyecto está usando Chrome | Espera a que termine o lanza con `--esperar` |
| `No encuentro al usuario '...'` | El @ no coincide con el nombre visible | Escríbelo como `"@usuario_1234567 / NombreVisible"` o pon la URL del perfil |
| `MakerOnline pide captcha...` | La web quiere comprobar que eres una persona | Resuélvelo en la ventana que aparece; si no hay ventana, quita `--sin-ventana` |
| `No encuentro el botón de...` | MakerOnline ha cambiado su web | Mira la captura `error_<id>.png` y actualiza el selector (ver `Codegen`) |
| Al parar el programa a mano sale `Cannot find module './../../../package.json'` | Java borra los archivos temporales de Playwright mientras su proceso de Node aún se cierra | Nada, es inofensivo |
| Parece que se ejecuta código antiguo | El IDE no ha recompilado | El Downloader muestra su versión en la primera línea. Si no es la esperada: `mvn -q compile` o *Build → Rebuild Project* |
| `-Dexec...` falla en PowerShell | PowerShell parte los argumentos | Pon cada opción `-D...` entre comillas (ver "Usarlo") |

## Otros programas

- **`Codegen`**: abre Chrome con tu sesión y el Playwright Inspector para grabar clics. Sirve para sacar los selectores nuevos si MakerOnline cambia su web.

## Estructura del proyecto

```
src/main/java/dev/jefe/makeronline/
├── SaveSession.java   Guarda tu sesión de Google/MakerOnline
├── Uploader.java      Recorre subidas/pendientes/ y sube los modelos
├── Downloader.java    Descarga los modelos nuevos de los usuarios de usuarios.json
├── AgentLock.java     Impide lanzar dos programas a la vez
├── ModelFolder.java   Valida la carpeta de un modelo
├── ModelMeta.java     Contenido de info.json del Uploader
├── Browsers.java      Abre Chrome con el perfil persistente
└── Codegen.java       Grabador de selectores
```

Archivos y carpetas que se crean al usarlo. Ninguno con datos tuyos se sube a git:

| Ruta | Qué es | ¿En git? |
|---|---|---|
| `chrome-profile/` | Tu sesión de Chrome/MakerOnline | ❌ Nunca |
| `subidas/pendientes/`, `subidos/`, `errores/` | Modelos del Uploader | Solo la carpeta vacía (`.gitkeep`) |
| `modelos-descargados/` | Lo que baja el Downloader | Solo la carpeta vacía (`.gitkeep`) |
| `usuarios.json` | Tu lista de usuarios | ❌ |
| `descargados.txt` | Registro de lo ya descargado | ❌ |
| `.agente.lock` | Candado de "uno a la vez" | ❌ |

---

## Para asistentes de IA en terminal

Si usas este proyecto con un asistente en la terminal (Claude Code, Codex, Gemini CLI…), estas son las reglas y datos que necesita:

- **Stack:** Java 21, Maven, Playwright para Java 1.52 (usa el canal `chrome`, el Chrome instalado) y Jackson. Paquete `dev.jefe.makeronline`. Programas con `main`: `SaveSession`, `Uploader`, `Downloader`, `Codegen`. No hay tests: para comprobar que compila, `mvn -q compile`.
- **Cómo lanzarlos:** `mvn -q compile exec:java -Dexec.mainClass=dev.jefe.makeronline.<Clase> -Dexec.args="..."`. En PowerShell, cada `-D...` va entre comillas.
- **El inicio de sesión lo hace siempre la persona.** `SaveSession` abre Chrome, la persona entra con Google y pulsa ENTER en la terminal. El asistente no debe pedir, escribir ni manejar contraseñas: que se lo pida a la persona.
- **`chrome-profile/` es secreto.** Contiene las cookies de la sesión (la de MakerOnline va en la cookie `mo_access_token`). No leerla, mostrarla, copiarla ni subirla a git.
- **Captchas:** no intentar resolverlos ni saltárselos. El programa saca la ventana para que los resuelva la persona, o se para.
- **Primera prueba siempre en seco:** `Downloader` con `--simular` y luego con `--max=1`. `Uploader` con `--revisar`.
- **Uno a la vez:** si un programa sale con código 2 y el mensaje "Ya hay otro programa del agente en marcha", hay otro en ejecución. No borrar `.agente.lock`: esperar o usar `--esperar`.
- **Tardan:** el Downloader hace pausas de 10-30 s entre descargas, así que una ejecución dura varios minutos. No cortarlo antes de tiempo. Si se corta, sale un error de Node `Cannot find module ... package.json`, y es inofensivo.
- **Qué edita la persona:** `usuarios.json` (Downloader, formato en su sección) y `subidas/pendientes/<modelo>/` (Uploader, formato en su sección).
- **Dónde mirar si algo falla:** `modelos-descargados/<usuario>/errores.txt` y las capturas `error_<id>.png` (Downloader), y `subidas/errores/<fecha>_<modelo>/error.txt` y `error.png` (Uploader). La primera línea del Downloader dice qué versión del código se está ejecutando.
- **Si MakerOnline cambia su web:** los selectores están en `Uploader.java` (formulario de subida) y `Downloader.java` (botones Download y like, buscador de usuarios). Revisa el HTML real de la página (por ejemplo con `--visible` o `Codegen`) antes de cambiarlos. La API interna que usa el Downloader (`/api/user/personal/index`, `/api/search/user`) solo se lee; las descargas y los likes se hacen pulsando los botones de la propia web.
- **Idioma:** el código, los mensajes y este README están en español.

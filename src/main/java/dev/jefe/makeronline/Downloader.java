package dev.jefe.makeronline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonObject;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.RequestOptions;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repasa los usuarios de usuarios.json y descarga sus modelos a los que todavía
 * no has dado "me gusta", dándoles like al terminar cada descarga. Así el like
 * hace de marca de "ya descargado" y la próxima vez se saltan.
 *
 * Para ir rápido, de cada usuario solo mira sus 10 últimos modelos subidos.
 *
 * Trabaja en segundo plano (Chrome headless): no se abre ninguna ventana.
 * Usa la misma sesión que el Uploader (chrome-profile/), y no puede estar en
 * marcha a la vez que él (ver AgentLock).
 *
 * Uso:  Downloader [usuario] [opciones]
 *   sin usuario    repasa todos los de usuarios.json
 *   usuario        solo ese: "@usuario_1234567 / NombreVisible", el @, el nombre, la URL de su perfil o su id
 *
 * Opciones:
 *   --ultimos=N    cuántos de los últimos modelos de cada usuario se miran (por defecto 10)
 *   --todos        mira todos los modelos de cada usuario (más lento; útil la primera vez)
 *   --simular      solo lista lo que descargaría; no descarga ni da like
 *   --max=N        descarga como mucho N modelos en esta ejecución (sumando todos los usuarios)
 *   --espera=S     pausa entre descargas: al azar entre S y S+20 s (por defecto 10, o sea 10-30 s),
 *                  contada desde que empieza la descarga anterior; siempre igual, aunque salgan captchas
 *   --perfil       descarga también el perfil de impresión (.3mf), no solo los archivos del modelo
 *   --visible      ventana de Chrome a la vista todo el rato (para ver qué hace)
 *   --sin-ventana  Chrome sin ventana (headless); si sale un captcha, se para
 *   --esperar      si el Uploader está en marcha, espera a que termine en vez de salir
 *
 * Ventana: por defecto Chrome se abre FUERA DE LA PANTALLA (no ocupa sitio, solo se ve en
 * la barra de tareas) y solo aparece delante si MakerOnline pide un captcha, para que lo
 * resuelvas tú. No se minimiza porque Chrome minimizado deja de pintar y Playwright se cuelga.
 *
 * Resultado: modelos-descargados/<usuario>/<título>_<id>/ con los archivos, portada e info.json,
 * y descargados.txt (en la raíz del proyecto) con lo ya bajado.
 */
public class Downloader {

    /** Se muestra al arrancar: así se sabe qué versión del código se está ejecutando. */
    private static final String VERSION = "7 (pausa fija 10-30 s + \"@usuario / nombre\")";

    private static final String WEB = "https://www.makeronline.com";
    private static final Path DESCARGAS = Paths.get("modelos-descargados");
    /**
     * Un id de modelo por línea. Segunda protección además del like: aunque el like
     * falle o vacíes modelos-descargados/, lo que está aquí no se vuelve a descargar.
     * Está en la raíz del proyecto (fuera de modelos-descargados/) para que no se
     * pierda al vaciar esa carpeta. Para volver a descargar un modelo, borra su línea.
     */
    private static final Path REGISTRO = Paths.get("descargados.txt");
    /** Donde estaba antes el registro: si existe, se mueve a la raíz. */
    private static final Path REGISTRO_ANTIGUO = DESCARGAS.resolve("descargados.txt");
    private static final Set<Long> REGISTRADOS = new HashSet<>();
    private static final Path LISTA = Paths.get("usuarios.json");
    private static final int ULTIMOS_POR_DEFECTO = 10;
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Para usuarios.json: admite comentarios // y una coma de más al final. */
    private static final ObjectMapper JSON_LISTA = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();
    private static final String PLANTILLA_LISTA = """
            [
              // Un usuario por línea, entre comillas y separados por comas.
              // Lo más seguro: "@usuario_1234567 / NombreVisible" (el @ y el nombre que se ve en su perfil).
              // También vale solo el @, solo el nombre o la URL de su perfil.
              // Las líneas que empiezan por // se ignoran.
              // "@NombreUsuario_1234567 / NombreVisible",
            ]
            """;
    private static final Pattern UUID_RE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Código con el que MakerOnline pide captcha. No se intenta saltar: se para. */
    private static final int CODIGO_CAPTCHA = 1003;

    /** usuario == null → todos los de usuarios.json; ultimos == 0 → todos sus modelos. */
    record Opciones(String usuario, int ultimos, boolean simular, int max, int espera,
                    boolean perfil, Modo modo, boolean esperar) {}

    /** Cómo se ve Chrome mientras trabaja. */
    enum Modo {
        /** Por defecto: ventana fuera de la pantalla; aparece si hay captcha. */
        APARTADA,
        /** --visible: ventana normal todo el rato. */
        VISIBLE,
        /** --sin-ventana: headless; un captcha para la ejecución. */
        SIN_VENTANA
    }

    /** La ventana de Chrome de esta ejecución (ver Ventana). */
    private static Ventana ventana;

    /** Pausa entre descargas: entre S y S + MARGEN_ESPERA segundos al azar (--espera=S). */
    private static final int ESPERA_POR_DEFECTO = 10;
    private static final int MARGEN_ESPERA = 20;

    /** Contadores y ritmo de toda la ejecución (todos los usuarios). */
    private static final class Estado {
        int descargados, soloLike, saltados, errores, restantes;
        boolean pausar, parar;
        /** Cuándo empezó el último modelo que generó tráfico: la pausa se cuenta desde ahí. */
        long inicioUltimo;
        final Set<Path> conErrores = new LinkedHashSet<>();

        Estado(int max) { this.restantes = max; }
    }

    record Usuario(String id, String nombre, String handle) {}

    record Modelo(long id, String titulo, boolean liked, boolean premium) {}

    enum Resultado { DESCARGADO, SOLO_LIKE, YA_TENIA_LIKE }

    /** MakerOnline ha pedido captcha y no se ha podido resolver: se para la ejecución. */
    static class CaptchaException extends RuntimeException {
        CaptchaException(String m) { super(m); }
    }

    /**
     * La ventana de Chrome. En modo APARTADA está fuera de la pantalla: Chrome funciona
     * con normalidad (Playwright lo arranca con --disable-backgrounding-occluded-windows),
     * pero no ocupa sitio; solo se ve en la barra de tareas. Si MakerOnline pide captcha,
     * se trae a la vista con las herramientas de Chrome (CDP) y luego se vuelve a apartar.
     */
    private static final class Ventana {
        static final long TIEMPO_CAPTCHA_MS = 5 * 60_000;
        /** Posición fuera de cualquier monitor. */
        static final int FUERA = -10_000;

        private final Modo modo;
        private final Page page;
        private CDPSession cdp;
        private int windowId = -1;

        Ventana(Modo modo, BrowserContext ctx, Page page) {
            this.modo = modo;
            this.page = page;
            if (modo != Modo.APARTADA) return;
            try {
                cdp = ctx.newCDPSession(page);
                windowId = cdp.send("Browser.getWindowForTarget").get("windowId").getAsInt();
                apartar(); // por si Chrome no hizo caso a --window-position
            } catch (Exception e) {
                cdp = null;
                System.out.println("   (no he podido apartar la ventana de Chrome: " + primeraLinea(e) + ")");
            }
        }

        /** Saca la ventana para que resuelvas el captcha; sin ventana, para la ejecución. */
        void pedirCaptcha(Page p, String para) {
            if (modo == Modo.SIN_VENTANA) throw new CaptchaException("MakerOnline pide captcha para " + para);
            mostrar();
            System.out.println("   ⚠ MakerOnline pide captcha para " + para
                    + ". Resuélvelo en la ventana de Chrome (tienes " + TIEMPO_CAPTCHA_MS / 60_000 + " minutos)");
        }

        void mostrar() {
            if (cdp != null) mover(80, 40);
            page.bringToFront();
        }

        void apartar() {
            if (cdp != null) mover(FUERA, FUERA);
        }

        private void mover(int x, int y) {
            try {
                JsonObject normal = new JsonObject();
                normal.addProperty("windowState", "normal"); // por si alguien la minimizó
                cdp.send("Browser.setWindowBounds", argumentos(normal));
                JsonObject posicion = new JsonObject();
                posicion.addProperty("left", x);
                posicion.addProperty("top", y);
                cdp.send("Browser.setWindowBounds", argumentos(posicion));
            } catch (Exception e) {
                System.out.println("   (no he podido mover la ventana de Chrome: " + primeraLinea(e) + ")");
            }
        }

        private JsonObject argumentos(JsonObject bounds) {
            JsonObject a = new JsonObject();
            a.addProperty("windowId", windowId);
            a.add("bounds", bounds);
            return a;
        }
    }

    public static void main(String[] args) throws Exception {
        Opciones op = leerArgs(args);
        if (op == null) {
            ayuda();
            System.exit(1);
        }
        // Solo un programa del agente a la vez: comparten chrome-profile/
        try (AgentLock lock = AgentLock.acquire("Downloader", op.esperar())) {
            ejecutar(op);
        }
    }

    private static void ejecutar(Opciones op) throws Exception {
        System.out.println("Downloader · versión " + VERSION);
        // Usuario por argumento → solo ese; si no, todos los de usuarios.json
        List<String> entradas = op.usuario() != null ? List.of(op.usuario()) : leerLista();
        if (entradas.isEmpty()) return;
        if (!Files.isDirectory(Browsers.PROFILE_DIR)) {
            System.err.println("No hay sesión guardada. Ejecuta primero SaveSession.");
            System.exit(1);
        }
        long inicio = System.currentTimeMillis();
        Estado est = new Estado(op.max());
        leerRegistro();

        try (Playwright pw = Playwright.create();
             BrowserContext ctx = Browsers.openChrome(pw, op.modo() == Modo.VISIBLE ? 100 : 0,
                     op.modo() == Modo.SIN_VENTANA, op.modo() == Modo.APARTADA)) {
            ctx.setDefaultTimeout(30_000);
            Page page = ctx.pages().isEmpty() ? ctx.newPage() : ctx.pages().get(0);
            ventana = new Ventana(op.modo(), ctx, page);

            // --- Sesión ---
            paso("Comprobando la sesión" + switch (op.modo()) {
                case APARTADA -> " (Chrome fuera de la pantalla; aparecerá solo si hay captcha)";
                case SIN_VENTANA -> " (sin ventana)";
                case VISIBLE -> "";
            });
            page.navigate(WEB + "/en/");
            if (!Boolean.TRUE.equals(page.evaluate("() => !!window.__NUXT__"))) {
                System.err.println("✘ La web de MakerOnline no ha cargado bien.");
                return;
            }
            cerrarCookies(page, 3_000);
            String token = token(ctx);
            if (token == null) {
                System.err.println("✘ No hay sesión iniciada en MakerOnline (o ha caducado). Ejecuta SaveSession.");
                return;
            }
            Api api = new Api(ctx.request(), token);

            // --- Repaso de todos los usuarios ---
            for (int k = 0; k < entradas.size() && !est.parar; k++) {
                String entrada = entradas.get(k);
                System.out.printf("%n######## [%d/%d] %s ########%n", k + 1, entradas.size(), entrada);
                try {
                    procesarUsuario(page, ctx, api, entrada, op, est);
                } catch (Exception e) {
                    est.errores++;
                    System.out.println("✘ No se ha podido revisar " + entrada + ": " + primeraLinea(e));
                }
            }

            long min = Duration.ofMillis(System.currentTimeMillis() - inicio).toMinutes();
            System.out.printf("%n======== Terminado en %d min ========%n", min);
            if (op.simular()) {
                System.out.println("(--simular: no se ha descargado nada ni se ha dado ningún like)");
            } else {
                System.out.printf("%d descargados, %d solo faltaba el like, %d ya tenían like, %d con error.%n",
                        est.descargados, est.soloLike, est.saltados, est.errores);
                System.out.println("Carpeta: " + DESCARGAS.toAbsolutePath());
                for (Path p : est.conErrores) {
                    System.out.println("Detalle de errores: " + p.resolve("errores.txt").toAbsolutePath());
                }
            }
        }
    }

    // ================================================================ un usuario

    private static void procesarUsuario(Page page, BrowserContext ctx, Api api, String entrada,
                                        Opciones op, Estado est) throws Exception {
        Usuario u = resolverUsuario(page, api, entrada);
        if (u == null) {
            est.errores++;
            return;
        }
        System.out.println("Usuario: " + u.nombre() + "  " + u.handle()
                + "\n         " + WEB + "/en/user/personalInfo/" + u.id() + ".html");

        List<Modelo> revisados = listarModelos(api, u.id(), op.ultimos());
        List<Modelo> pendientes = revisados.stream().filter(m -> !m.liked()).toList();
        System.out.printf("%s %d modelos: %d ya con like, %d por descargar.%n",
                op.ultimos() > 0 ? "Últimos" : "Todos sus", revisados.size(),
                revisados.size() - pendientes.size(), pendientes.size());

        if (op.simular()) {
            for (Modelo m : pendientes) {
                System.out.println("  · " + m.titulo() + (m.premium() ? "  [premium]" : "") + "\n    " + urlModelo(m));
            }
            return;
        }
        if (pendientes.isEmpty()) return;

        // modelos-descargados/<usuario>/
        Path carpetaUsuario = DESCARGAS.resolve(limpio(u.nombre(), 60));
        Files.createDirectories(carpetaUsuario);

        int antes = est.descargados;
        for (int i = 0; i < pendientes.size(); i++) {
            if (est.restantes <= 0) {
                System.out.println("Alcanzado el máximo de descargas (--max): paro aquí.");
                est.parar = true;
                break;
            }
            if (est.pausar) {
                pausa(page, op.espera(), est);
                est.pausar = false;
            }
            Modelo m = pendientes.get(i);
            System.out.printf("%n=== [%d/%d] %s ===%n", i + 1, pendientes.size(), m.titulo());
            long inicio = System.currentTimeMillis();
            try {
                switch (procesar(page, ctx, m, carpetaUsuario, op)) {
                    case DESCARGADO -> { est.descargados++; est.restantes--; est.pausar = true; est.inicioUltimo = inicio; }
                    case SOLO_LIKE -> { est.soloLike++; est.pausar = true; est.inicioUltimo = inicio; }
                    case YA_TENIA_LIKE -> est.saltados++;
                }
            } catch (CaptchaException e) {
                est.errores++;
                est.parar = true;
                est.conErrores.add(carpetaUsuario);
                System.out.println("✘ " + e.getMessage());
                System.out.println("  Paro aquí (también con el resto de usuarios) para no forzar nada.");
                System.out.println("  Vuelve a intentarlo más tarde" + (op.modo() == Modo.SIN_VENTANA
                        ? ", o lánzalo sin --sin-ventana para que aparezca Chrome y lo resuelvas tú."
                        : " y resuelve el captcha cuando aparezca la ventana de Chrome."));
                registrarError(carpetaUsuario, m, e.getMessage());
                break;
            } catch (Exception e) {
                est.errores++;
                est.pausar = true; // ha habido tráfico: se respeta la espera igualmente
                est.inicioUltimo = inicio;
                est.conErrores.add(carpetaUsuario);
                String msg = primeraLinea(e);
                System.out.println("✘ Error: " + msg);
                registrarError(carpetaUsuario, m, msg);
                captura(page, carpetaUsuario.resolve("error_" + m.id() + ".png"));
            }
        }
        System.out.println("→ " + u.nombre() + ": " + (est.descargados - antes) + " descargados en " + carpetaUsuario);
    }

    /** Lee usuarios.json (o lo crea vacío la primera vez). */
    private static List<String> leerLista() throws IOException {
        if (!Files.exists(LISTA)) {
            Files.writeString(LISTA, PLANTILLA_LISTA, StandardCharsets.UTF_8);
            System.out.println("He creado " + LISTA.toAbsolutePath());
            System.out.println("Añade ahí los usuarios que quieres repasar y vuelve a ejecutar.");
            return List.of();
        }
        JsonNode raiz;
        try {
            raiz = JSON_LISTA.readTree(LISTA.toFile());
        } catch (JsonProcessingException e) {
            String donde = e.getLocation() == null ? "" : " (línea " + e.getLocation().getLineNr() + ")";
            System.err.println("✘ usuarios.json no es válido" + donde + ": " + e.getOriginalMessage());
            System.err.println("  Tiene que ser una lista: [\"usuario1\", \"usuario2\"]");
            return List.of();
        }
        if (raiz == null || !raiz.isArray()) {
            System.err.println("✘ usuarios.json tiene que ser una lista: [\"usuario1\", \"usuario2\"]");
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Set<String> vistos = new LinkedHashSet<>();
        for (JsonNode n : raiz) {
            String s = n.asText("").trim();
            if (!s.isEmpty() && vistos.add(s.toLowerCase())) out.add(s);
        }
        if (out.isEmpty()) System.out.println("usuarios.json está vacío: añade algún usuario y vuelve a ejecutar.");
        else System.out.println("Usuarios en la lista: " + out.size());
        return out;
    }

    private static String primeraLinea(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage().lines().findFirst().orElse(e.toString());
    }

    // ================================================================ un modelo

    private static Resultado procesar(Page page, BrowserContext ctx, Modelo m, Path carpetaUsuario, Opciones op)
            throws IOException {
        Path dir = carpetaUsuario.resolve(limpio(m.titulo(), 60) + "_" + m.id());

        paso("Abriendo " + urlModelo(m));
        page.navigate(urlModelo(m));
        cerrarCookies(page, 0);
        JsonNode det = detalle(page);
        if (det == null) {
            throw new IllegalStateException("No se ha podido leer la página del modelo (¿borrado, privado o la web no ha cargado?)");
        }
        // Doble comprobación: la lista dice que no tiene like, pero lo confirmamos en su página
        if (det.path("is_like").asInt(0) == 1) {
            System.out.println("↷ Ya tenía like, lo salto");
            return Resultado.YA_TENIA_LIKE;
        }

        // Ya descargado en una ejecución anterior si está en descargados.txt o si existe
        // su info.json (se escribe lo último, así que la descarga se completó)
        String usuario = carpetaUsuario.getFileName().toString();
        boolean enRegistro = REGISTRADOS.contains(m.id());
        boolean yaDescargado = enRegistro || Files.exists(dir.resolve("info.json"));
        if (yaDescargado) {
            System.out.println("↷ Ya estaba descargado" + (enRegistro ? " (descargados.txt)" : " en " + dir)
                    + ", solo falta el like");
            registrar(m, usuario); // los bajados antes de existir el registro se anotan ahora
        } else {
            Files.createDirectories(dir);
            int raw = det.path("files").size();
            int perfiles = det.path("print_profiles").size();
            if (raw == 0 && perfiles == 0) throw new IllegalStateException("El modelo no tiene archivos para descargar");

            if (raw > 0) descargarArchivosModelo(page, dir);
            if (perfiles > 0 && (op.perfil() || raw == 0)) descargarPerfil(page, dir);

            guardarPortada(ctx, det, dir);
            guardarInfo(page, det, m, dir);
            registrar(m, usuario);
            System.out.println("✔ Descargado en " + dir);
        }

        darLike(page, m);
        return yaDescargado ? Resultado.SOLO_LIKE : Resultado.DESCARGADO;
    }

    /**
     * Pestaña "Raw Model Files". Su botón cambia según cuántos archivos tenga el modelo:
     *  - uno solo  → botón "Download" normal
     *  - varios    → botón partido "Download ▾" y, en el desplegable, "Download All"
     * (El botón de esta pestaña es .print-download-button.tailwind; el de la pestaña
     * de perfiles es .print-download-button a secas.)
     */
    private static void descargarArchivosModelo(Page page, Path dir) {
        paso("Archivos del modelo");
        Locator pestana = page.getByText(Pattern.compile("^\\s*Raw Model Files")).first();
        if (pestana.count() > 0) pestana.click();

        Locator zona = page.locator(".print-download-button.tailwind")
                .filter(new Locator.FilterOptions().setVisible(true)).first();
        try {
            zona.waitFor(new Locator.WaitForOptions().setTimeout(15_000));
        } catch (TimeoutError e) {
            throw new IllegalStateException("No encuentro el botón de descarga de los archivos del modelo");
        }

        Locator desplegable = zona.locator("#download-dropdown");
        if (desplegable.count() == 0) {
            // un solo archivo: botón "Download" sin desplegable
            Locator boton = zona.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Download").setExact(true)).first();
            esperarDescarga(page, dir, boton::click);
            return;
        }

        // varios archivos: flecha del desplegable → "Download All"
        Locator flecha = desplegable.locator(".el-dropdown__caret-button");
        Locator todo = page.locator(".el-dropdown-menu__item")
                .filter(new Locator.FilterOptions().setHasText("Download All").setVisible(true)).first();

        // el menú se abre al pasar el ratón por la flecha; si no, con un clic
        for (int intento = 1; !todo.isVisible(); intento++) {
            if (intento > 3) throw new IllegalStateException("No se abre el menú 'Download All'");
            flecha.hover();
            page.waitForTimeout(600);
            if (!todo.isVisible()) {
                flecha.click();
                page.waitForTimeout(600);
            }
        }
        esperarDescarga(page, dir, todo::click);
    }

    /**
     * Pestaña "Print Profile Files" → Download (perfil seleccionado, el primero).
     * Según el perfil, la descarga empieza directamente o sale la ventana
     * "Download Print Profile Files" con su botón "Download All".
     */
    private static void descargarPerfil(Page page, Path dir) {
        paso("Perfil de impresión");
        Locator pestana = page.getByText(Pattern.compile("^\\s*Print Profile Files")).first();
        if (pestana.count() > 0) pestana.click();

        Locator boton = page.locator(".print-download-button:not(.tailwind)")
                .filter(new Locator.FilterOptions().setVisible(true))
                .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Download").setExact(true))
                .first();
        try {
            boton.waitFor(new Locator.WaitForOptions().setTimeout(15_000));
        } catch (TimeoutError e) {
            throw new IllegalStateException("No encuentro el botón de descarga del perfil de impresión");
        }

        Locator todo = page.locator(".el-dialog")
                .filter(new Locator.FilterOptions().setHasText("Download Print Profile Files").setVisible(true))
                .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Download All"));
        esperarDescarga(page, dir, () -> {
            boton.click();
            // si sale la ventana con "Download All", se pulsa; si no, la descarga ya ha empezado
            try {
                todo.waitFor(new Locator.WaitForOptions().setTimeout(5_000));
                todo.click();
            } catch (TimeoutError e) {
                // sin ventana intermedia
            }
        });
        page.keyboard().press("Escape"); // cierra la ventana del perfil si quedó abierta
    }

    /**
     * Hace el clic y espera a que el navegador empiece la descarga. Vigila a la vez
     * la respuesta de la API por si MakerOnline pide captcha, da error o pide login.
     */
    private static void esperarDescarga(Page page, Path dir, Runnable clic) {
        AtomicReference<Download> descarga = new AtomicReference<>();
        AtomicReference<JsonNode> fallo = new AtomicReference<>();
        Consumer<Download> alDescargar = descarga::set;
        Consumer<Response> alResponder = r -> {
            String url = r.url();
            if (!url.contains("/api/file/download") && !url.contains("/api/file/check-is-package")) return;
            try {
                JsonNode j = JSON.readTree(r.text());
                if (j.path("code").asInt(0) != 0) fallo.set(j);
            } catch (Exception ignored) {
                // respuesta sin cuerpo JSON: no nos dice nada
            }
        };

        // por si la web abre la descarga en otra pestaña
        BrowserContext ctx = page.context();
        List<Page> pestanasNuevas = new ArrayList<>();
        Consumer<Page> alAbrirPestana = p -> {
            pestanasNuevas.add(p);
            p.onDownload(alDescargar);
        };
        Locator avisoError = page.locator(".el-message--error, .el-message--warning")
                .filter(new Locator.FilterOptions().setVisible(true)).first();

        page.onDownload(alDescargar);
        page.onResponse(alResponder);
        ctx.onPage(alAbrirPestana);
        boolean captcha = false;
        try {
            clic.run();
            long limite = System.currentTimeMillis() + 120_000;
            while (descarga.get() == null) {
                JsonNode j = fallo.getAndSet(null);
                if (j != null) {
                    int code = j.path("code").asInt();
                    if (code == CODIGO_CAPTCHA) {
                        if (!captcha) {
                            ventana.pedirCaptcha(page, "descargar"); // lanza CaptchaException si no hay ventana
                            limite = System.currentTimeMillis() + Ventana.TIEMPO_CAPTCHA_MS;
                            captcha = true;
                        }
                    } else if (code != 1) { // con code 1 la propia web reintenta sola
                        throw new IllegalStateException("MakerOnline no deja descargar: " + j.path("message").asText("?"));
                    }
                }
                if (page.locator(".login-container .el-dialog").isVisible()) {
                    throw new IllegalStateException("MakerOnline pide iniciar sesión: ha caducado. Ejecuta SaveSession.");
                }
                if (avisoError.isVisible()) {
                    throw new IllegalStateException("MakerOnline dice: " + avisoError.innerText().trim());
                }
                if (System.currentTimeMillis() > limite) {
                    if (captcha) throw new CaptchaException("El captcha no se ha resuelto a tiempo");
                    throw new IllegalStateException("La descarga no ha empezado (tiempo agotado)");
                }
                page.waitForTimeout(500);
            }
        } finally {
            page.offDownload(alDescargar);
            page.offResponse(alResponder);
            ctx.offPage(alAbrirPestana);
            if (captcha) ventana.apartar(); // resuelto (o no): la ventana vuelve fuera de la pantalla
        }

        Download d = descarga.get();
        Path destino = dir.resolve(limpioArchivo(d.suggestedFilename()));
        paso("Guardando " + destino.getFileName());
        try {
            d.saveAs(destino); // espera a que termine de bajar
            String error = d.failure();
            if (error != null) throw new IllegalStateException("La descarga ha fallado: " + error);
        } finally {
            for (Page p : pestanasNuevas) {
                if (!p.isClosed()) p.close();
            }
        }
    }

    /** Pulsa el corazón del modelo y confirma con la respuesta de la API. */
    private static void darLike(Page page, Modelo m) {
        if (!page.url().contains("/" + m.id() + ".html")) {
            page.navigate(urlModelo(m));
            cerrarCookies(page, 0);
        }
        page.keyboard().press("Escape"); // por si quedó abierta alguna ventana de la descarga encima
        // Botón 👍 de la barra bajo los archivos: <div class="... like-feedback ..."><svg><use href="#icon-ic_like">
        Locator corazon = page.locator(".like-feedback svg")
                .filter(new Locator.FilterOptions().setVisible(true)).first();
        try {
            corazon.waitFor(new Locator.WaitForOptions().setTimeout(10_000));
        } catch (TimeoutError e) {
            throw new IllegalStateException("Descargado, pero no encuentro el botón de me gusta");
        }
        if (icono(corazon).contains("ic_like_click")) {
            System.out.println("♥ Ya tenía like");
            return;
        }

        paso("Dando like");
        Response r;
        try {
            r = page.waitForResponse(resp -> resp.url().contains("/api/user/like/like"),
                    new Page.WaitForResponseOptions().setTimeout(15_000),
                    () -> corazon.click(new Locator.ClickOptions().setTimeout(10_000)));
        } catch (TimeoutError e) {
            throw new IllegalStateException("Descargado, pero el like no ha llegado a enviarse");
        }
        int code;
        String mensaje;
        try {
            JsonNode j = JSON.readTree(r.text());
            code = j.path("code").asInt(-1);
            mensaje = j.path("message").asText("?");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Descargado, pero la respuesta del like no se entiende");
        }

        if (code == CODIGO_CAPTCHA) {
            ventana.pedirCaptcha(page, "dar like"); // lanza CaptchaException si no hay ventana
            try {
                long limite = System.currentTimeMillis() + Ventana.TIEMPO_CAPTCHA_MS;
                while (!icono(corazon).contains("ic_like_click")) {
                    if (System.currentTimeMillis() > limite) {
                        throw new CaptchaException("Modelo descargado, pero el captcha del like no se ha resuelto a tiempo");
                    }
                    page.waitForTimeout(1000);
                }
            } finally {
                ventana.apartar();
            }
        } else if (code != 0) {
            throw new IllegalStateException("Descargado, pero el like ha fallado: " + mensaje);
        }
        System.out.println("♥ Like dado");
    }

    // ================================================================ datos

    /** Datos del modelo que la web incrusta en la página (window.__NUXT__). */
    private static JsonNode detalle(Page page) throws JsonProcessingException {
        Object s = page.evaluate("() => { const d = window.__NUXT__ && window.__NUXT__.data && window.__NUXT__.data[0];"
                + " return d && d.modelDetailData && !d.notFindView ? JSON.stringify(d.modelDetailData) : null; }");
        return s == null ? null : JSON.readTree((String) s);
    }

    private static Usuario resolverUsuario(Page page, Api api, String entrada) throws JsonProcessingException {
        // URL del perfil o id directamente: el nombre se saca de la página del perfil
        Matcher mm = UUID_RE.matcher(entrada);
        if (mm.find()) {
            String id = mm.group().toLowerCase();
            page.navigate(WEB + "/en/user/personalInfo/" + id + ".html");
            Object s = page.evaluate("() => { const d = window.__NUXT__ && window.__NUXT__.data && window.__NUXT__.data[0];"
                    + " return d && d.userInfo && d.userInfo.id ? JSON.stringify(d.userInfo) : null; }");
            if (s == null) {
                System.err.println("✘ No encuentro ningún usuario con id " + id);
                return null;
            }
            return usuario(JSON.readTree((String) s));
        }

        // El buscador de MakerOnline busca por el NOMBRE VISIBLE, no por el @. El @ conserva el
        // nombre de cuenta original (p. ej. @User_1234567 puede llamarse "Pepe"), así que con solo el @
        // a veces no aparece. Por eso se admite "@handle / nombre visible": se busca por el
        // nombre y se elige la cuenta cuyo @ coincide exactamente.
        String clave;              // @ sin la arroba, o el nombre si no hay @
        boolean soloPorHandle;     // con "@handle / nombre" solo vale coincidir el @
        Set<String> busquedas = new LinkedHashSet<>();
        String[] partes = entrada.split("\\s*/\\s*");
        if (partes.length == 2 && !partes[0].isBlank() && !partes[1].isBlank()) {
            boolean primeroEsHandle = partes[0].startsWith("@") || !partes[1].startsWith("@");
            String handle = primeroEsHandle ? partes[0] : partes[1];
            String nombre = primeroEsHandle ? partes[1] : partes[0];
            clave = handle.replaceFirst("^@", "");
            soloPorHandle = true;
            busquedas.add(nombre);
        } else if (partes.length > 1) {
            System.err.println("✘ No entiendo '" + entrada + "'. Usa \"@usuario_1234567 / NombreVisible\", el @, el nombre o la URL.");
            return null;
        } else {
            // "NombreVisible", "@Nombre" o "@NombreUsuario_1234567"
            clave = entrada.replaceFirst("^@", "");
            soloPorHandle = false;
        }
        busquedas.add(clave);
        busquedas.add(clave.replaceFirst("_\\d+$", ""));

        List<JsonNode> vistos = new ArrayList<>();
        for (String keyword : busquedas) {
            JsonNode data = api.post("/api/search/user", Map.of("keyword", keyword, "page", 1, "page_size", 20));
            List<JsonNode> porHandle = new ArrayList<>();
            List<JsonNode> porNombre = new ArrayList<>();
            for (JsonNode n : data.path("data")) {
                vistos.add(n);
                if (n.path("show_user_name").asText("").equalsIgnoreCase("@" + clave)) porHandle.add(n);
                else if (!soloPorHandle && (n.path("name").asText("").equalsIgnoreCase(clave)
                        || n.path("display_name").asText("").equalsIgnoreCase(clave))) porNombre.add(n);
            }
            if (porHandle.size() == 1) return usuario(porHandle.get(0));
            if (porNombre.size() == 1) return usuario(porNombre.get(0));
            if (porNombre.size() > 1) {
                System.err.println("✘ Hay varios usuarios llamados '" + clave + "'. Usa su @ completo o la URL del perfil:");
                porNombre.forEach(n -> System.err.println("   " + n.path("show_user_name").asText()
                        + " / " + n.path("display_name").asText() + "  (" + n.path("designs").asInt() + " modelos)"));
                return null;
            }
        }
        System.err.println("✘ No encuentro al usuario '" + entrada + "'.");
        System.err.println(soloPorHandle
                ? "  Revisa que el nombre visible esté bien escrito, o pon la URL de su perfil."
                : "  Si su nombre visible es distinto del @, escribe \"@usuario_1234567 / NombreVisible\" o pon la URL de su perfil.");
        if (!vistos.isEmpty()) {
            System.err.println("  Parecidos:");
            vistos.stream().limit(5).forEach(n -> System.err.println("   " + n.path("show_user_name").asText()
                    + " / " + n.path("display_name").asText() + "  (" + n.path("designs").asInt() + " modelos)"));
        }
        return null;
    }

    private static Usuario usuario(JsonNode n) {
        String id = n.path("user_id").asText(n.path("id").asText());
        String nombre = n.path("display_name").asText(n.path("name").asText(id));
        return new Usuario(id, nombre, n.path("show_user_name").asText(""));
    }

    /**
     * Modelos de "Uploads" del usuario (del más reciente al más antiguo), con
     * is_like según TU cuenta.
     *
     * @param limite cuántos de los últimos; 0 = todos (recorre todas las páginas)
     */
    private static List<Modelo> listarModelos(Api api, String userId, int limite) throws InterruptedException {
        List<Modelo> out = new ArrayList<>();
        for (int pagina = 1; ; pagina++) {
            JsonNode data = api.get("/api/user/personal/index?page_size=12&user_id=" + userId + "&page=" + pagina);
            JsonNode lista = data.path("data");
            for (JsonNode n : lista) {
                out.add(new Modelo(n.path("mold_id").asLong(), n.path("title").asText(""),
                        n.path("is_like").asInt(0) == 1, n.path("is_premium").asBoolean(false)));
            }
            if (limite > 0 && out.size() >= limite) break; // con los 10 últimos basta una página
            int ultima = Math.max(1, data.path("page").path("last_page").asInt(1));
            if (limite <= 0) System.out.print("\r→ Leyendo la lista de modelos: página " + pagina + "/" + ultima + "   ");
            if (pagina >= ultima || lista.isEmpty()) break;
            Thread.sleep(800 + ThreadLocalRandom.current().nextInt(700)); // sin prisas
        }
        if (limite <= 0) System.out.println();
        return limite > 0 && out.size() > limite ? new ArrayList<>(out.subList(0, limite)) : out;
    }

    private static void guardarPortada(BrowserContext ctx, JsonNode det, Path dir) {
        String url = null;
        for (JsonNode im : det.path("images")) {
            if (im.path("is_main").asInt(0) == 1) { url = im.path("url").asText(null); break; }
        }
        if (url == null && det.path("images").size() > 0) url = det.path("images").get(0).path("url").asText(null);
        if (url == null || url.isBlank()) return;
        try {
            APIResponse r = ctx.request().get(url);
            if (r.ok()) Files.write(dir.resolve("portada" + extension(url)), r.body());
        } catch (Exception e) {
            System.out.println("   (no se ha podido guardar la portada: " + e.getMessage() + ")");
        }
    }

    private static void guardarInfo(Page page, JsonNode det, Modelo m, Path dir) throws IOException {
        ObjectNode info = JSON.createObjectNode();
        info.put("id", m.id());
        info.put("title", det.path("title").asText(m.titulo()));
        info.put("author", det.path("user_display_name").asText(""));
        info.put("authorHandle", det.path("show_user_name").asText(""));
        info.put("url", urlModelo(m));
        info.put("category", det.path("category_nav").asText(""));
        Object licencia = page.evaluate("() => { const e = [...document.querySelectorAll('p,span,div')]"
                + ".find(x => x.children.length === 0 && /licensed under/i.test(x.textContent));"
                + " return e ? e.textContent.trim() : null; }");
        info.put("license", licencia == null ? "" : licencia.toString());
        info.set("tags", det.path("tags").isArray() ? det.path("tags").deepCopy() : JSON.createArrayNode());
        info.put("description", htmlATexto(det.path("desc").asText("")));
        info.put("downloadedAt", LocalDateTime.now().format(STAMP));
        JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("info.json").toFile(), info);
    }

    /** Carga descargados.txt (id ⇥ usuario ⇥ título ⇥ fecha). */
    private static void leerRegistro() throws IOException {
        // Antes vivía dentro de modelos-descargados/: se pasa a la raíz para que no se pierda al vaciarla
        if (Files.exists(REGISTRO_ANTIGUO)) {
            Files.write(REGISTRO, Files.readAllBytes(REGISTRO_ANTIGUO),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Files.delete(REGISTRO_ANTIGUO);
            System.out.println("descargados.txt pasa a estar en " + REGISTRO.toAbsolutePath());
        }
        if (!Files.exists(REGISTRO)) return;
        for (String linea : Files.readAllLines(REGISTRO, StandardCharsets.UTF_8)) {
            String id = linea.split("\t", 2)[0].trim();
            if (id.matches("\\d+")) REGISTRADOS.add(Long.parseLong(id));
        }
    }

    private static void registrar(Modelo m, String usuario) {
        if (!REGISTRADOS.add(m.id())) return;
        try {
            Files.writeString(REGISTRO,
                    m.id() + "\t" + usuario + "\t" + m.titulo().replaceAll("[\\t\\r\\n]+", " ")
                            + "\t" + LocalDateTime.now().format(STAMP) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("   (no se ha podido anotar en descargados.txt: " + e.getMessage() + ")");
        }
    }

    private static void registrarError(Path carpetaUsuario, Modelo m, String mensaje) {
        try {
            Files.writeString(carpetaUsuario.resolve("errores.txt"),
                    LocalDateTime.now().format(STAMP) + "  " + m.titulo() + "  " + urlModelo(m) + "\n    " + mensaje + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // no es grave
        }
    }

    // ================================================================ utilidades

    /** Peticiones a la API de MakerOnline con tu sesión (sin cargar páginas). */
    private record Api(APIRequestContext req, String token) {
        JsonNode get(String path) {
            return leer(req.get(WEB + path, RequestOptions.create()
                    .setHeader("Authorization", token)
                    .setHeader("Accept", "application/json")), path);
        }

        JsonNode post(String path, Object cuerpo) {
            try {
                return leer(req.post(WEB + path, RequestOptions.create()
                        .setHeader("Authorization", token)
                        .setHeader("Accept", "application/json")
                        .setHeader("Content-Type", "application/json")
                        .setData(JSON.writeValueAsString(cuerpo))), path);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }

        private static JsonNode leer(APIResponse r, String path) {
            if (!r.ok()) throw new IllegalStateException("La API ha respondido HTTP " + r.status() + " a " + path);
            try {
                JsonNode j = JSON.readTree(r.text());
                if (j.path("code").asInt(-1) != 0) {
                    throw new IllegalStateException("La API ha respondido '" + j.path("message").asText("?") + "' a " + path);
                }
                return j.path("data");
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Respuesta no válida de " + path);
            }
        }
    }

    /** La sesión de MakerOnline es la cookie mo_access_token; la web la manda como cabecera Authorization. */
    private static String token(BrowserContext ctx) {
        for (Cookie c : ctx.cookies(WEB)) {
            if ("mo_access_token".equals(c.name) && c.value != null && !c.value.isBlank()) return c.value;
        }
        return null;
    }

    /** Aviso de cookies: tapa botones. Se cierra con "Necessary Only". */
    private static void cerrarCookies(Page page, int esperaMs) {
        Locator boton = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Necessary Only"));
        try {
            if (esperaMs > 0) boton.waitFor(new Locator.WaitForOptions().setTimeout(esperaMs));
            if (boton.isVisible()) boton.click();
        } catch (TimeoutError e) {
            // no hay aviso
        }
    }

    /** Captura de la página en el momento del fallo: sin ventana es la única forma de ver qué pasó. */
    private static void captura(Page page, Path destino) {
        try {
            page.screenshot(new Page.ScreenshotOptions().setPath(destino).setFullPage(false));
        } catch (Exception ignored) {
            // si la página ya no responde, seguimos sin captura
        }
    }

    private static String icono(Locator svg) {
        Object href = svg.evaluate("el => { const u = el.querySelector('use');"
                + " return u ? (u.getAttribute('href') || u.getAttribute('xlink:href') || '') : ''; }");
        return href == null ? "" : href.toString();
    }

    private static String urlModelo(Modelo m) {
        String slug = URLEncoder.encode(m.titulo().replace('/', '-'), StandardCharsets.UTF_8).replace("+", "%20");
        return WEB + "/en/model/" + (slug.isEmpty() ? "model" : slug) + "/" + m.id() + ".html";
    }

    /**
     * Pausa entre descargas: al azar entre S y S+20 s (por defecto 10-30 s), siempre
     * igual aunque salgan captchas. Se cuenta desde que EMPEZÓ el modelo anterior, así
     * que lo que tardó en bajar ya cuenta como parte de la pausa. --espera=0 la quita.
     */
    private static void pausa(Page page, int minimo, Estado est) {
        if (minimo <= 0) return;
        double objetivoS = minimo + ThreadLocalRandom.current().nextDouble(MARGEN_ESPERA);
        long restanteMs = Math.round(objetivoS * 1000) - (System.currentTimeMillis() - est.inicioUltimo);
        if (restanteMs <= 0) return;
        System.out.printf("… esperando %d s antes del siguiente%n", Math.round(restanteMs / 1000.0));
        page.waitForTimeout(restanteMs);
    }

    /** Nombre válido para una carpeta de Windows. */
    private static String limpio(String nombre, int max) {
        String s = nombre.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("\\s+", " ").trim();
        if (s.length() > max) s = s.substring(0, max).trim();
        s = s.replaceAll("[. ]+$", "");
        return s.isEmpty() ? "modelo" : s;
    }

    /** Igual que limpio() pero conservando la extensión. */
    private static String limpioArchivo(String nombre) {
        int punto = nombre.lastIndexOf('.');
        if (punto <= 0) return limpio(nombre, 100);
        return limpio(nombre.substring(0, punto), 100) + nombre.substring(punto).replaceAll("[^A-Za-z0-9.]", "");
    }

    private static String extension(String url) {
        String ruta = url.replaceFirst("[?#].*$", "");
        String ext = ruta.substring(ruta.lastIndexOf('.') + 1).toLowerCase();
        return ext.matches("jpe?g|png|webp|gif") ? "." + ext : ".jpg";
    }

    private static String htmlATexto(String html) {
        String t = html.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</p>", "\n").replaceAll("<[^>]+>", "");
        t = t.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        return t.replaceAll("\n{3,}", "\n\n").trim();
    }

    private static void paso(String texto) {
        System.out.println("→ " + texto);
    }

    private static Opciones leerArgs(String[] args) {
        String usuario = null;
        boolean simular = false, perfil = false, esperar = false;
        Modo modo = Modo.APARTADA;
        int max = Integer.MAX_VALUE, espera = ESPERA_POR_DEFECTO, ultimos = ULTIMOS_POR_DEFECTO;
        try {
            for (String a : args) {
                if (a.equals("--simular")) simular = true;
                else if (a.equals("--perfil")) perfil = true;
                else if (a.equals("--visible")) modo = Modo.VISIBLE;
                else if (a.equals("--sin-ventana")) modo = Modo.SIN_VENTANA;
                else if (a.equals("--esperar")) esperar = true;
                else if (a.equals("--todos")) ultimos = 0;
                else if (a.startsWith("--ultimos=")) ultimos = Integer.parseInt(a.substring(10));
                else if (a.startsWith("--max=")) max = Integer.parseInt(a.substring(6));
                else if (a.startsWith("--espera=")) espera = Integer.parseInt(a.substring(9));
                else if (a.startsWith("--")) {
                    System.err.println("Opción desconocida: " + a);
                    return null;
                } else if (usuario == null) usuario = a;
                else {
                    System.err.println("Solo se admite un usuario por argumento (para varios, usa usuarios.json).");
                    return null;
                }
            }
        } catch (NumberFormatException e) {
            System.err.println("Número no válido en --ultimos, --max o --espera");
            return null;
        }
        if (max < 1 || espera < 0 || ultimos < 0) return null;
        return new Opciones(usuario, ultimos, simular, max, espera, perfil, modo, esperar);
    }

    private static void ayuda() {
        System.out.println("""
                Uso: Downloader [usuario] [opciones]
                  sin usuario   repasa todos los de usuarios.json
                  usuario       solo ese: nombre, @usuario_1234567, URL del perfil o id
                Opciones:
                  --ultimos=N   cuántos de los últimos modelos de cada usuario se miran (por defecto 10)
                  --todos       mira todos los modelos de cada usuario (más lento)
                  --simular     solo lista lo que descargaría (no descarga ni da like)
                  --max=N       como mucho N descargas en esta ejecución
                  --espera=S    pausa entre descargas: entre S y S+20 s (por defecto 10, o sea 10-30 s)
                  --perfil      descarga también el perfil de impresión (.3mf)
                  --visible     ventana de Chrome a la vista todo el rato
                  --sin-ventana Chrome sin ventana; si sale un captcha, se para
                  --esperar     si el Uploader está en marcha, espera a que termine
                Por defecto Chrome trabaja fuera de la pantalla y solo aparece si hay captcha.
                Ejemplo: -Dexec.args="--simular"
                """);
    }
}

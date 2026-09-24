package dev.jefe.makeronline;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * El agente.
 *
 * Recorre subidas/pendientes/, valida cada carpeta, rellena el formulario de
 * MakerOnline y lo guarda como BORRADOR (draft). Después mueve la carpeta a
 * subidas/subidos/ (con la fecha delante) o a subidas/errores/ (con error.txt
 * y una captura del momento del fallo).
 *
 * Argumentos (opcionales):
 *   --revisar   se para justo antes de guardar el borrador para que mires el
 *               formulario. Pulsa "Resume" en el Inspector para continuar.
 *   nombre ...  solo procesa esas carpetas de pendientes/ (sin nombres, todas).
 *               Ejemplo: --revisar llavero-gato
 */
public class Uploader {

    private static final String UPLOAD_URL = "https://www.makeronline.com/en/upload";

    private static final Path BASE = Paths.get("subidas");
    private static final Path PENDIENTES = BASE.resolve("pendientes");
    private static final Path SUBIDOS = BASE.resolve("subidos");
    private static final Path ERRORES = BASE.resolve("errores");

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm");

    public static void main(String[] args) throws Exception {
        boolean revisar = List.of(args).contains("--revisar");

        Files.createDirectories(PENDIENTES);
        Files.createDirectories(SUBIDOS);
        Files.createDirectories(ERRORES);

        if (!Files.isDirectory(Browsers.PROFILE_DIR)) {
            System.err.println("No hay sesión guardada. Ejecuta primero SaveSession.");
            System.exit(1);
        }

        // 1) Validar TODO antes de abrir el navegador
        List<ModelFolder> listos = new ArrayList<>();
        List<Path> carpetas;
        try (Stream<Path> s = Files.list(PENDIENTES)) {
            carpetas = s.filter(Files::isDirectory).sorted().toList();
        }

        // Si se pasan nombres de carpeta, solo se procesan esas
        List<String> nombres = Stream.of(args).filter(a -> !a.startsWith("--")).toList();
        if (!nombres.isEmpty()) {
            for (String n : nombres) {
                if (!Files.isDirectory(PENDIENTES.resolve(n))) System.out.println("✘ No existe en pendientes: " + n);
            }
            carpetas = carpetas.stream().filter(d -> nombres.contains(d.getFileName().toString())).toList();
        }

        if (carpetas.isEmpty()) {
            System.out.println("No hay nada en " + PENDIENTES.toAbsolutePath());
            return;
        }
        for (Path dir : carpetas) {
            try {
                listos.add(ModelFolder.load(dir));
                System.out.println("✔ " + dir.getFileName() + " validado");
            } catch (IllegalStateException | IOException e) {
                System.out.println("✘ " + dir.getFileName() + ":\n   " + e.getMessage().replace("\n", "\n   "));
                moverAErrores(dir, e.getMessage());
            }
        }
        if (listos.isEmpty()) {
            System.out.println("Ningún modelo válido. Revisa subidas/errores/.");
            return;
        }

        // 2) Subir los válidos
        int ok = 0, ko = 0;
        try (Playwright pw = Playwright.create();
             BrowserContext ctx = Browsers.openChrome(pw, 150)) {
            ctx.setDefaultTimeout(30_000);

            for (ModelFolder m : listos) {
                System.out.println("\n=== " + m.name() + " ===");
                Page page = ctx.newPage();
                try {
                    subir(page, m, revisar);
                    captura(page, m.dir().resolve("resultado.png"));
                    Path destino = mover(m.dir(), SUBIDOS);
                    System.out.println("✔ Borrador guardado → " + destino);
                    ok++;
                } catch (Exception e) {
                    System.out.println("✘ Error: " + e.getMessage());
                    captura(page, m.dir().resolve("error.png"));
                    moverAErrores(m.dir(), e.toString());
                    ko++;
                } finally {
                    page.close();
                }
            }
        }
        System.out.printf("%nTerminado: %d subidos, %d con error.%n", ok, ko);
    }

    /** Rellena el formulario completo y guarda como borrador. */
    private static void subir(Page page, ModelFolder m, boolean revisar) {
        ModelMeta meta = m.meta();

        paso("Abriendo formulario");
        page.navigate(UPLOAD_URL);

        // --- Licencia (ventana que sale al entrar) ---
        Locator licencia = page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("Choose a CC license"));
        try {
            licencia.waitFor(new Locator.WaitForOptions().setTimeout(15_000));
        } catch (TimeoutError e) {
            throw new IllegalStateException(
                    "No se ha cargado el formulario de subida. ¿Ha caducado la sesión? Ejecuta SaveSession.");
        }
        paso("Licencia: " + meta.licenseOrDefault());
        licencia.click();
        page.getByText(meta.licenseOrDefault()).first().click();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Submit")).click();

        // --- Portada ---
        paso("Portada: " + m.cover().getFileName());
        elegirArchivo(page, page.locator(".el-upload-dragger").first(), m.cover());
        esperarSubidas(page, 60);

        // --- Título ---
        paso("Título");
        Locator titulo = page.locator(".el-form-item")
                .filter(new Locator.FilterOptions().setHasText("Model Title"))
                .locator("input, textarea").first();
        if (titulo.count() == 0) {
            titulo = page.locator("form").getByRole(AriaRole.TEXTBOX).nth(1); // selector grabado por codegen
        }
        titulo.fill(meta.title());

        // --- Categoría (2 niveles) ---
        paso("Categoría: " + String.join(" > ", meta.category()));
        page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("First level classification/")).click();
        for (String nivel : meta.category()) {
            page.getByText(nivel, new Page.GetByTextOptions().setExact(true)).click();
        }

        // --- Tags ---
        paso("Tags: " + tagsLimpios(meta.tags()));
        Locator tags = page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("*Press the Enter key to"));
        for (String tag : tagsLimpios(meta.tags())) {
            tags.click();
            tags.fill(tag);
            tags.press("Enter");
        }

        // --- Visibilidad y tipo de impresión ---
        paso("Public + " + meta.printTypeOrDefault());
        page.locator("label").filter(new Locator.FilterOptions().setHasText("Public")).click();
        page.locator("label").filter(new Locator.FilterOptions().setHasText(meta.printTypeOrDefault())).click();

        // --- Descripción (editor Quill): se escribe de verdad, línea a línea ---
        paso("Descripción");
        page.locator(".ql-editor").click();
        String[] lineas = meta.description().split("\\R", -1);
        for (int i = 0; i < lineas.length; i++) {
            if (i > 0) page.keyboard().press("Enter");
            if (!lineas[i].isEmpty()) page.keyboard().insertText(lineas[i]);
        }

        // --- Paso 2: archivos ---
        paso("Siguiente paso: archivos");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Next Step, Add Files")).click();

        if (m.is3mf()) {
            paso("Perfil de impresión (3mf): " + m.model().getFileName());
            elegirArchivo(page, zonaSubida(page, "only supports single 3mf"), m.model());
            esperarSubidas(page, 300);

            paso("Título del perfil de impresión");
            Locator tituloPerfil = page.locator(".el-form-item")
                    .filter(new Locator.FilterOptions().setHasText("Print Profile Title"))
                    .locator("input, textarea").first();
            try {
                tituloPerfil.waitFor(new Locator.WaitForOptions().setTimeout(15_000));
            } catch (TimeoutError e) {
                throw new IllegalStateException(
                        "No encuentro el campo 'Print Profile Title'. Graba el selector con Codegen.");
            }
            tituloPerfil.fill(meta.title());

            paso("Imagen del perfil de impresión: " + m.cover().getFileName());
            Locator zonaFotos = page.locator(".el-form-item")
                    .filter(new Locator.FilterOptions().setHasText("Print Profile Pictures"))
                    .locator(".el-upload").first();
            try {
                zonaFotos.waitFor(new Locator.WaitForOptions().setTimeout(15_000));
            } catch (TimeoutError e) {
                throw new IllegalStateException(
                        "No encuentro la zona 'Print Profile Pictures'. Graba el selector con Codegen.");
            }
            elegirArchivo(page, zonaFotos, m.cover());
            esperarSubidas(page, 60);
        }
        paso("Archivo del modelo: " + m.model().getFileName());
        elegirArchivo(page, zonaSubida(page, "Supported files: stl"), m.model());
        esperarSubidas(page, 300);

        if (revisar) {
            System.out.println("   ⏸ Revisa el formulario. Pulsa 'Resume' en el Inspector para guardar el borrador.");
            page.pause();
        }

        // --- Guardar como borrador ---
        paso("Guardando borrador");
        page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(Pattern.compile("draft", Pattern.CASE_INSENSITIVE))).first().click();
        page.waitForTimeout(3000); // margen para que se guarde antes de cerrar la pestaña
    }

    // ----------------------------------------------------------------- utilidades

    /** Parte cada entrada por comas ("a, b" → "a", "b"), quita espacios, vacíos y repetidos. */
    private static List<String> tagsLimpios(List<String> tags) {
        return tags.stream()
                .flatMap(t -> Stream.of(t.split(",")))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .distinct()
                .toList();
    }

    /** Zona de subida (.el-upload) que contiene el texto indicado. */
    private static Locator zonaSubida(Page page, String texto) {
        return page.locator(".el-upload").filter(new Locator.FilterOptions().setHasText(texto)).first();
    }

    /** Hace clic como una persona y responde al selector de archivos del navegador. */
    private static void elegirArchivo(Page page, Locator zona, Path archivo) {
        FileChooser fc = page.waitForFileChooser(zona::click);
        fc.setFiles(archivo);
    }

    /** Espera a que no quede ninguna barra de progreso de subida visible. */
    private static void esperarSubidas(Page page, int maxSegundos) {
        page.waitForTimeout(1500);
        long fin = System.currentTimeMillis() + maxSegundos * 1000L;
        Locator ocupado = page.locator(".el-upload-list__item.is-uploading, .el-progress:visible");
        while (System.currentTimeMillis() < fin) {
            if (ocupado.count() == 0) return;
            page.waitForTimeout(1000);
        }
        throw new IllegalStateException("La subida de archivos ha tardado más de " + maxSegundos + " s");
    }

    private static void paso(String texto) {
        System.out.println("→ " + texto);
    }

    private static void captura(Page page, Path destino) {
        try {
            page.screenshot(new Page.ScreenshotOptions().setPath(destino).setFullPage(true));
        } catch (Exception ignored) {
            // si la página ya no responde, seguimos sin captura
        }
    }

    private static void moverAErrores(Path dir, String mensaje) {
        try {
            Files.writeString(dir.resolve("error.txt"),
                    LocalDateTime.now().format(STAMP) + "\n" + mensaje + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Path destino = mover(dir, ERRORES);
            System.out.println("   Movido a " + destino);
        } catch (IOException e) {
            System.out.println("   No se pudo mover a errores/: " + e.getMessage());
        }
    }

    /** Mueve la carpeta a destino con la fecha delante: 2026-09-23_2145_modelo-a */
    private static Path mover(Path dir, Path carpetaDestino) throws IOException {
        Path destino = carpetaDestino.resolve(LocalDateTime.now().format(STAMP) + "_" + dir.getFileName());
        int n = 2;
        while (Files.exists(destino)) {
            destino = carpetaDestino.resolve(LocalDateTime.now().format(STAMP) + "_" + dir.getFileName() + "_" + n++);
        }
        return Files.move(dir, destino);
    }
}

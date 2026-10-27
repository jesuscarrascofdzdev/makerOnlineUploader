package dev.jefe.makeronline;

import com.microsoft.playwright.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Abre tu Google Chrome real (no el Chromium de Playwright) con un perfil
 * dedicado en ./chrome-profile.
 *
 * ¿Por qué? Google bloquea "Iniciar sesión con Google" en navegadores
 * que detecta como automatizados. Con Chrome real + perfil persistente:
 *  - haces el login con Google a mano UNA vez,
 *  - las cookies (de Google y de MakerOnline) se quedan en el perfil,
 *  - el agente reutiliza ese perfil y ya entra logueado.
 *
 * Es un perfil aparte: no toca tu Chrome de diario (ciérralo igualmente
 * si da problemas de bloqueo del perfil).
 */
public final class Browsers {
    public static final Path PROFILE_DIR = Paths.get("chrome-profile");

    private Browsers() {}

    /** Chrome con ventana normal (Uploader, SaveSession, Codegen). */
    public static BrowserContext openChrome(Playwright pw, double slowMoMs) {
        return openChrome(pw, slowMoMs, false, false);
    }

    /**
     * @param headless        true = sin ventana (Chrome headless).
     * @param fueraDePantalla true = ventana normal pero colocada fuera de la pantalla:
     *                        funciona igual que una visible y no ocupa sitio (lo usa el
     *                        Downloader, que la trae a la vista si hace falta un captcha).
     */
    public static BrowserContext openChrome(Playwright pw, double slowMoMs, boolean headless, boolean fueraDePantalla) {
        List<String> args = new ArrayList<>(List.of("--disable-blink-features=AutomationControlled"));
        if (fueraDePantalla && !headless) args.add("--window-position=-10000,-10000");

        BrowserType.LaunchPersistentContextOptions opts = new BrowserType.LaunchPersistentContextOptions()
                .setChannel("chrome")          // usa Google Chrome instalado
                .setHeadless(headless)
                .setAcceptDownloads(true)
                .setSlowMo(slowMoMs)
                // Quita la marca de "navegador controlado por software"
                // que Google usa para bloquear el login.
                .setIgnoreDefaultArgs(List.of("--enable-automation"))
                .setArgs(args);
        if (headless || fueraDePantalla) {
            opts.setViewportSize(1366, 900); // tamaño de escritorio para que la web use su diseño normal
        }
        return pw.chromium().launchPersistentContext(PROFILE_DIR, opts);
    }
}

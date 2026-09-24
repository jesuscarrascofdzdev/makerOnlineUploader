package dev.jefe.makeronline;

import com.microsoft.playwright.*;

import java.nio.file.Path;
import java.nio.file.Paths;
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

    public static BrowserContext openChrome(Playwright pw, double slowMoMs) {
        return pw.chromium().launchPersistentContext(PROFILE_DIR,
                new BrowserType.LaunchPersistentContextOptions()
                        .setChannel("chrome")          // usa Google Chrome instalado
                        .setHeadless(false)
                        .setSlowMo(slowMoMs)
                        // Quita la marca de "navegador controlado por software"
                        // que Google usa para bloquear el login.
                        .setIgnoreDefaultArgs(List.of("--enable-automation"))
                        .setArgs(List.of("--disable-blink-features=AutomationControlled")));
    }
}

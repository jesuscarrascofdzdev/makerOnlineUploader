package dev.jefe.makeronline;

import com.microsoft.playwright.*;

/**
 * Abre Chrome con tu sesión y el Playwright Inspector para grabar clics
 * (botón Record). Útil si MakerOnline cambia su formulario.
 * Copia el código del Inspector ANTES de pulsar Resume.
 */
public class Codegen {
    public static void main(String[] args) {
        try (Playwright pw = Playwright.create();
             BrowserContext ctx = Browsers.openChrome(pw, 0)) {
            Page page = ctx.pages().isEmpty() ? ctx.newPage() : ctx.pages().get(0);
            page.navigate("https://www.makeronline.com/en/upload");
            page.pause();
        }
    }
}

package dev.jefe.makeronline;

import com.microsoft.playwright.*;

import java.util.Scanner;

/**
 * PASO 1 — se ejecuta UNA sola vez (o cuando caduque la sesión).
 *
 * Abre tu Chrome real con el perfil ./chrome-profile. Tú pulsas
 * "Iniciar sesión con Google" en MakerOnline, eliges tu cuenta y listo:
 * la sesión queda guardada en el perfil para el agente.
 */
public class SaveSession {
    public static void main(String[] args) {
        try (Playwright pw = Playwright.create();
             BrowserContext ctx = Browsers.openChrome(pw, 0)) {
            Page page = ctx.pages().isEmpty() ? ctx.newPage() : ctx.pages().get(0);
            page.navigate("https://www.makeronline.com/en/");

            System.out.println("Inicia sesión con Google en la ventana de Chrome.");
            System.out.println("Cuando veas que ya estás dentro de MakerOnline, pulsa ENTER aquí...");
            new Scanner(System.in).nextLine();

            System.out.println("Sesión guardada en ./chrome-profile (¡no la subas a git ni la compartas!).");
        }
    }
}

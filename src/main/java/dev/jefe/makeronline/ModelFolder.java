package dev.jefe.makeronline;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Una carpeta de subidas/pendientes/ ya validada.
 *
 * Convención (carpeta "modelo-a"):
 *   modelo-a.3mf  o  modelo-a.stl   → el modelo (exactamente uno)
 *   modelo-a.jpg / .jpeg / .png     → la portada
 *   info.json                       → textos
 */
public record ModelFolder(Path dir, String name, Path model, Path cover, ModelMeta meta) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Lee y valida la carpeta. Lanza IllegalStateException con todos los problemas juntos. */
    public static ModelFolder load(Path dir) throws IOException {
        String name = dir.getFileName().toString();
        List<String> errors = new ArrayList<>();

        if (name.contains(" ")) errors.add("El nombre de la carpeta no puede tener espacios: '" + name + "'");

        List<Path> models = findByName(dir, name, "3mf", "stl");
        if (models.isEmpty()) errors.add("Falta el modelo: " + name + ".3mf o " + name + ".stl");
        if (models.size() > 1) errors.add("Hay más de un modelo, deja solo uno: " + models);

        List<Path> covers = findByName(dir, name, "jpg", "jpeg", "png");
        if (covers.isEmpty()) errors.add("Falta la portada: " + name + ".jpg o " + name + ".png");
        if (covers.size() > 1) errors.add("Hay más de una portada, deja solo una: " + covers);

        ModelMeta meta = null;
        Path info = dir.resolve("info.json");
        if (!Files.exists(info)) {
            errors.add("Falta info.json");
        } else {
            try {
                meta = JSON.readValue(info.toFile(), ModelMeta.class);
                errors.addAll(meta.validate());
            } catch (IOException e) {
                errors.add("info.json no es válido (revisa comas y comillas): " + e.getMessage());
            }
        }

        if (!errors.isEmpty()) throw new IllegalStateException(String.join("\n", errors));
        return new ModelFolder(dir, name, models.get(0), covers.get(0), meta);
    }

    public boolean is3mf() {
        return model.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".3mf");
    }

    /** Archivos que se llaman exactamente <name>.<ext>, sin distinguir mayúsculas en la extensión. */
    private static List<Path> findByName(Path dir, String name, String... exts) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).filter(p -> {
                String f = p.getFileName().toString();
                for (String ext : exts) {
                    if (f.equalsIgnoreCase(name + "." + ext) && f.startsWith(name)) return true;
                }
                return false;
            }).sorted().toList();
        }
    }
}

package dev.jefe.makeronline;

import java.util.ArrayList;
import java.util.List;

/**
 * Contenido de info.json de cada modelo.
 *
 * Obligatorios: title, description, tags, category.
 * Opcionales:   license   (por defecto "CC0 (aka CC Zero)" — el principio del texto de la opción de licencia)
 *               printType (por defecto "FDM")
 */
public record ModelMeta(
        String title,
        String description,
        List<String> tags,
        List<String> category,
        String license,
        String printType
) {
    public String licenseOrDefault()   { return blank(license)   ? "CC0 (aka CC Zero)" : license; }
    public String printTypeOrDefault() { return blank(printType) ? "FDM" : printType; }

    /** Devuelve la lista de problemas; vacía si todo está bien. */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (blank(title)) errors.add("Falta 'title'");
        if (blank(description)) errors.add("Falta 'description'");
        if (tags == null || tags.isEmpty() || tags.stream().anyMatch(ModelMeta::blank))
            errors.add("'tags' tiene que tener al menos un tag y ninguno vacío");
        if (category == null || category.size() != 2 || category.stream().anyMatch(ModelMeta::blank))
            errors.add("'category' tiene que tener 2 niveles, p. ej. [\"Toys&Games\", \"Action Figures\"]");
        return errors;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}

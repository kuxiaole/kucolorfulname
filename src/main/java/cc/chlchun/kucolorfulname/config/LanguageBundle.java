package cc.chlchun.kucolorfulname.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;

/** Reloadable MiniMessage language bundle with simple %placeholder% support. */
public final class LanguageBundle {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private final Map<String, String> values;

    private LanguageBundle(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static LanguageBundle load(FileConfiguration configuration) {
        Map<String, String> loaded = new LinkedHashMap<>();
        configuration.getValues(true).forEach((key, value) -> {
            if (value instanceof String string) {
                loaded.put(key, string);
            }
        });
        return new LanguageBundle(loaded);
    }

    public String raw(String key) {
        return values.getOrDefault(key, key);
    }

    public String raw(String key, Map<String, ?> placeholders) {
        return interpolate(raw(key), placeholders);
    }

    public List<String> lines(String key) {
        return values.containsKey(key) ? List.of(values.get(key)) : List.of();
    }

    public Component component(String key) {
        return render(raw(key));
    }

    public Component component(String key, Map<String, ?> placeholders) {
        return render(raw(key, placeholders));
    }

    public static Component render(String template) {
        try {
            return MINI_MESSAGE.deserialize(template == null ? "" : template);
        } catch (IllegalArgumentException exception) {
            return Component.text(template == null ? "" : template);
        }
    }

    public Map<String, String> values() {
        return values;
    }

    private static String interpolate(String template, Map<String, ?> placeholders) {
        String result = template == null ? "" : template;
        if (placeholders == null || placeholders.isEmpty()) {
            return result;
        }
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            String key = "%" + entry.getKey() + "%";
            String value = String.valueOf(entry.getValue());
            result = result.replace(key, value);
        }
        return result;
    }
}

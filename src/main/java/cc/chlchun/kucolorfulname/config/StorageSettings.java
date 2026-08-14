package cc.chlchun.kucolorfulname.config;

import java.util.Locale;
import java.util.Objects;
import org.bukkit.configuration.file.FileConfiguration;

public record StorageSettings(StorageType type, MySqlSettings mysql, MariaDbSettings mariadb) {
    /** Backwards-compatible constructor for integrations that only supplied MySQL settings. */
    public StorageSettings(StorageType type, MySqlSettings mysql) {
        this(type, mysql, MariaDbSettings.defaults());
    }

    public StorageSettings {
        Objects.requireNonNull(type, "storage type");
        Objects.requireNonNull(mysql, "mysql settings");
        Objects.requireNonNull(mariadb, "mariadb settings");
    }

    public static StorageSettings load(FileConfiguration config) {
        String raw = config.getString("storage.type", "SQLITE");
        StorageType type;
        try {
            type = StorageType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("storage.type must be SQLITE, MYSQL or MARIADB", exception);
        }
        return new StorageSettings(type, MySqlSettings.load(config), MariaDbSettings.load(config));
    }
}

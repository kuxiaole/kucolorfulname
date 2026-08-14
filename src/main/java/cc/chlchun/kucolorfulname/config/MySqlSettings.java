package cc.chlchun.kucolorfulname.config;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.FileConfiguration;

public record MySqlSettings(
        String host,
        int port,
        String database,
        String username,
        String password,
        String tablePrefix,
        String sslMode,
        int maximumPoolSize,
        int minimumIdle,
        long connectionTimeoutMs,
        long validationTimeoutMs,
        long idleTimeoutMs,
        long maxLifetimeMs,
        long keepaliveTimeMs,
        int socketTimeoutMs,
        int synchronizationIntervalSeconds
) implements SqlConnectionSettings {
    private static final Pattern HOST = Pattern.compile("[a-zA-Z0-9._:\\[\\]-]+");
    private static final Pattern DATABASE = Pattern.compile("[a-zA-Z0-9_$-]+");
    private static final Pattern TABLE_PREFIX = Pattern.compile("[a-zA-Z0-9_]*");
    private static final Set<String> SSL_MODES = Set.of(
            "DISABLED", "PREFERRED", "REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY"
    );

    public MySqlSettings {
        host = requireText(host, "MySQL host");
        database = requireText(database, "MySQL database");
        username = requireText(username, "MySQL username");
        password = Objects.requireNonNull(password, "MySQL password");
        tablePrefix = Objects.requireNonNull(tablePrefix, "MySQL table prefix").trim();
        sslMode = requireText(sslMode, "MySQL SSL mode").toUpperCase(Locale.ROOT);
        if (!HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("MySQL host contains unsupported characters");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("MySQL port must be between 1 and 65535");
        }
        if (!DATABASE.matcher(database).matches() || database.length() > 64) {
            throw new IllegalArgumentException(
                    "MySQL database must contain at most 64 letters, numbers, _, $ or -");
        }
        if (!TABLE_PREFIX.matcher(tablePrefix).matches() || tablePrefix.length() > 32) {
            throw new IllegalArgumentException(
                    "MySQL table prefix must contain at most 32 letters, numbers or _");
        }
        if (!SSL_MODES.contains(sslMode)) {
            throw new IllegalArgumentException("Unsupported MySQL SSL mode: " + sslMode);
        }
        if (maximumPoolSize < 1 || maximumPoolSize > 64) {
            throw new IllegalArgumentException("MySQL maximum pool size must be between 1 and 64");
        }
        if (minimumIdle < 0 || minimumIdle > maximumPoolSize) {
            throw new IllegalArgumentException(
                    "MySQL minimum idle must be between 0 and maximum pool size");
        }
        if (connectionTimeoutMs < 250 || connectionTimeoutMs > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "MySQL connection timeout must be between 250 and 2147483647 ms");
        }
        if (validationTimeoutMs < 250 || validationTimeoutMs > connectionTimeoutMs) {
            throw new IllegalArgumentException(
                    "MySQL validation timeout must be between 250 ms and connection timeout");
        }
        if (idleTimeoutMs != 0 && idleTimeoutMs < 10_000) {
            throw new IllegalArgumentException("MySQL idle timeout must be 0 or at least 10000 ms");
        }
        if (maxLifetimeMs != 0 && maxLifetimeMs < 30_000) {
            throw new IllegalArgumentException("MySQL max lifetime must be 0 or at least 30000 ms");
        }
        if (keepaliveTimeMs != 0 && (keepaliveTimeMs < 30_000
                || (maxLifetimeMs != 0 && keepaliveTimeMs >= maxLifetimeMs))) {
            throw new IllegalArgumentException(
                    "MySQL keepalive time must be 0 or at least 30000 ms and lower than max lifetime");
        }
        if (socketTimeoutMs < 0) {
            throw new IllegalArgumentException("MySQL socket timeout cannot be negative");
        }
        if (synchronizationIntervalSeconds != 0 && synchronizationIntervalSeconds < 5) {
            throw new IllegalArgumentException(
                    "MySQL synchronization interval must be 0 or at least 5 seconds");
        }
    }

    public static MySqlSettings load(FileConfiguration config) {
        String root = "storage.mysql.";
        return new MySqlSettings(
                config.getString(root + "host", "127.0.0.1"),
                config.getInt(root + "port", 3306),
                config.getString(root + "database", "kucolorfulname"),
                config.getString(root + "username", "root"),
                config.getString(root + "password", "change-me"),
                config.getString(root + "table-prefix", "kucolorfulname_"),
                config.getString(root + "ssl-mode", "PREFERRED"),
                config.getInt(root + "maximum-pool-size", 10),
                config.getInt(root + "minimum-idle", 2),
                config.getLong(root + "connection-timeout-ms", 10_000),
                config.getLong(root + "validation-timeout-ms", 5_000),
                config.getLong(root + "idle-timeout-ms", 600_000),
                config.getLong(root + "max-lifetime-ms", 1_800_000),
                config.getLong(root + "keepalive-time-ms", 30_000),
                config.getInt(root + "socket-timeout-ms", 30_000),
                config.getInt(root + "synchronization-interval-seconds", 60)
        );
    }

    @Override
    public String jdbcUrl() {
        return jdbcUrl(database);
    }

    @Override
    public String serverJdbcUrl() {
        return jdbcUrl("");
    }

    private String jdbcUrl(String databasePath) {
        String urlHost = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return "jdbc:mysql://" + urlHost + ":" + port + "/" + databasePath
                + "?useUnicode=true&characterEncoding=UTF-8"
                + "&useServerPrepStmts=true&cachePrepStmts=true"
                + "&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048"
                + "&rewriteBatchedStatements=true&tcpKeepAlive=true"
                + "&connectTimeout=" + connectionTimeoutMs
                + "&socketTimeout=" + socketTimeoutMs
                + "&sslMode=" + sslMode;
    }

    private static String requireText(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " cannot be blank");
        }
        return normalized;
    }
}

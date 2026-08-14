package cc.chlchun.kucolorfulname.config;

/**
 * Common connection-pool settings shared by the MySQL and MariaDB backends.
 *
 * <p>The storage implementations use this immutable view so that the SQL
 * repository and its lifecycle do not need to know which wire-protocol
 * driver was selected.</p>
 */
public interface SqlConnectionSettings {
    String host();

    int port();

    String database();

    String username();

    String password();

    String tablePrefix();

    String sslMode();

    int maximumPoolSize();

    int minimumIdle();

    long connectionTimeoutMs();

    long validationTimeoutMs();

    long idleTimeoutMs();

    long maxLifetimeMs();

    long keepaliveTimeMs();

    int socketTimeoutMs();

    int synchronizationIntervalSeconds();

    /** JDBC URL including driver-specific connection options. */
    String jdbcUrl();

    /** JDBC URL for the same server without selecting a default database. */
    default String serverJdbcUrl() {
        throw new UnsupportedOperationException("Server-level JDBC URL is not available");
    }
}

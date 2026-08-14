package cc.chlchun.kucolorfulname.storage;

import cc.chlchun.kucolorfulname.config.SqlConnectionSettings;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** Creates a missing MySQL-compatible database before the connection pool starts. */
final class SqlDatabaseBootstrap {
    private static final int UNKNOWN_DATABASE = 1049;

    private SqlDatabaseBootstrap() {
    }

    static void ensureExists(
            SqlConnectionSettings settings,
            String driverClassName,
            String databaseLabel
    ) throws SQLException {
        Objects.requireNonNull(driverClassName, "driverClassName");
        Driver driver = loadDriver(driverClassName, databaseLabel);
        ensureExists(settings, databaseLabel,
                (url, username, password) -> connect(driver, url, username, password, databaseLabel));
    }

    static void ensureExists(
            SqlConnectionSettings settings,
            String databaseLabel,
            JdbcConnector connector
    ) throws SQLException {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(databaseLabel, "databaseLabel");
        Objects.requireNonNull(connector, "connector");

        SQLException missingDatabase;
        try (Connection ignored = connector.connect(
                settings.jdbcUrl(), settings.username(), settings.password())) {
            return;
        } catch (SQLException exception) {
            if (!containsErrorCode(exception, UNKNOWN_DATABASE)) {
                throw exception;
            }
            missingDatabase = exception;
        }

        String createSql = "CREATE DATABASE IF NOT EXISTS " + quoteIdentifier(settings.database())
                + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";
        try (Connection connection = connector.connect(
                settings.serverJdbcUrl(), settings.username(), settings.password());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(createSql);
        } catch (SQLException creationFailure) {
            // Another server may have created the database between our probe and CREATE.
            try (Connection ignored = connector.connect(
                    settings.jdbcUrl(), settings.username(), settings.password())) {
                return;
            } catch (SQLException retryFailure) {
                SQLException failure = new SQLException(
                        databaseLabel + " database '" + settings.database()
                                + "' is missing and automatic creation failed; grant CREATE "
                                + "permission for this database or create it manually",
                        creationFailure.getSQLState(), creationFailure.getErrorCode(), creationFailure);
                failure.addSuppressed(missingDatabase);
                failure.addSuppressed(retryFailure);
                throw failure;
            }
        }
    }

    static String quoteIdentifier(String identifier) {
        String value = Objects.requireNonNull(identifier, "identifier");
        return "`" + value.replace("`", "``") + "`";
    }

    private static Driver loadDriver(String driverClassName, String databaseLabel) throws SQLException {
        try {
            Class<?> driverType = Class.forName(
                    driverClassName, true, SqlDatabaseBootstrap.class.getClassLoader());
            return (Driver) driverType.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | ClassCastException exception) {
            throw new SQLException(
                    "Unable to load the " + databaseLabel + " JDBC driver", exception);
        }
    }

    private static Connection connect(
            Driver driver,
            String url,
            String username,
            String password,
            String databaseLabel
    ) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        Connection connection = driver.connect(url, properties);
        if (connection == null) {
            throw new SQLException(databaseLabel + " JDBC driver rejected the configured URL");
        }
        return connection;
    }

    private static boolean containsErrorCode(SQLException exception, int errorCode) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(exception);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current instanceof SQLException sqlException) {
                if (sqlException.getErrorCode() == errorCode) {
                    return true;
                }
                SQLException next = sqlException.getNextException();
                if (next != null) {
                    pending.addLast(next);
                }
            }
            if (current.getCause() != null) {
                pending.addLast(current.getCause());
            }
        }
        return false;
    }

    @FunctionalInterface
    interface JdbcConnector {
        Connection connect(String url, String username, String password) throws SQLException;
    }
}

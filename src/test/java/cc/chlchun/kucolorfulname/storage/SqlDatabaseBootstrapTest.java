package cc.chlchun.kucolorfulname.storage;

import cc.chlchun.kucolorfulname.config.MySqlSettings;
import cc.chlchun.kucolorfulname.config.SqlConnectionSettings;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlDatabaseBootstrapTest {
    @Test
    void existingDatabaseDoesNotRequireCreatePermission() throws Exception {
        SqlConnectionSettings settings = settings("existing_db");
        List<String> urls = new ArrayList<>();
        AtomicBoolean targetClosed = new AtomicBoolean();

        SqlDatabaseBootstrap.ensureExists(settings, "MySQL", (url, username, password) -> {
            urls.add(url);
            return connection(targetClosed, null);
        });

        assertEquals(List.of(settings.jdbcUrl()), urls);
        assertTrue(targetClosed.get());
    }

    @Test
    void unknownDatabaseIsCreatedThroughServerConnection() throws Exception {
        SqlConnectionSettings settings = settings("color-names$");
        List<String> urls = new ArrayList<>();
        List<String> statements = new ArrayList<>();
        AtomicBoolean serverClosed = new AtomicBoolean();
        AtomicBoolean statementClosed = new AtomicBoolean();

        SqlDatabaseBootstrap.ensureExists(settings, "MySQL", (url, username, password) -> {
            urls.add(url);
            if (url.equals(settings.jdbcUrl())) {
                throw unknownDatabase();
            }
            return connection(serverClosed, statement(statements, statementClosed, null));
        });

        assertEquals(List.of(settings.jdbcUrl(), settings.serverJdbcUrl()), urls);
        assertEquals(List.of("CREATE DATABASE IF NOT EXISTS `color-names$` "
                + "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"), statements);
        assertTrue(serverClosed.get());
        assertTrue(statementClosed.get());
    }

    @Test
    void nonMissingConnectionFailureIsNotTreatedAsMissingDatabase() {
        SqlConnectionSettings settings = settings("secured_db");
        SQLException authenticationFailure = new SQLException("access denied", "28000", 1045);
        List<String> urls = new ArrayList<>();

        SQLException thrown = assertThrows(SQLException.class,
                () -> SqlDatabaseBootstrap.ensureExists(settings, "MySQL", (url, user, password) -> {
                    urls.add(url);
                    throw authenticationFailure;
                }));

        assertSame(authenticationFailure, thrown);
        assertEquals(List.of(settings.jdbcUrl()), urls);
    }

    @Test
    void concurrentCreationWinsAfterCreateFailure() {
        SqlConnectionSettings settings = settings("shared_db");
        AtomicInteger targetAttempts = new AtomicInteger();
        AtomicBoolean retryClosed = new AtomicBoolean();
        SQLException createFailure = new SQLException("already changed", "HY000", 1007);

        assertDoesNotThrow(() -> SqlDatabaseBootstrap.ensureExists(
                settings, "MySQL", (url, user, password) -> {
                    if (url.equals(settings.jdbcUrl())) {
                        if (targetAttempts.getAndIncrement() == 0) {
                            throw unknownDatabase();
                        }
                        return connection(retryClosed, null);
                    }
                    return connection(new AtomicBoolean(),
                            statement(new ArrayList<>(), new AtomicBoolean(), createFailure));
                }));

        assertEquals(2, targetAttempts.get());
        assertTrue(retryClosed.get());
    }

    @Test
    void creationFailureHasClearMessageAndKeepsSqlCause() {
        SqlConnectionSettings settings = settings("missing_db");
        SQLException createFailure = new SQLException("permission denied", "42000", 1044);
        List<String> urls = new ArrayList<>();

        SQLException thrown = assertThrows(SQLException.class,
                () -> SqlDatabaseBootstrap.ensureExists(settings, "MariaDB", (url, user, password) -> {
                    urls.add(url);
                    if (url.equals(settings.jdbcUrl())) {
                        throw unknownDatabase();
                    }
                    return connection(new AtomicBoolean(),
                            statement(new ArrayList<>(), new AtomicBoolean(), createFailure));
                }));

        assertEquals(List.of(settings.jdbcUrl(), settings.serverJdbcUrl(), settings.jdbcUrl()), urls);
        assertSame(createFailure, thrown.getCause());
        assertTrue(thrown.getMessage().contains("automatic creation failed"));
        assertTrue(thrown.getMessage().contains("CREATE permission"));
    }

    @Test
    void nestedUnknownDatabaseErrorAndIdentifierEscapingAreSupported() throws Exception {
        SqlConnectionSettings settings = settings("nested_db");
        SQLException wrapper = new SQLException("wrapper", "HY000", 0);
        wrapper.setNextException(unknownDatabase());

        assertDoesNotThrow(() -> SqlDatabaseBootstrap.ensureExists(
                settings, "MySQL", (url, user, password) -> {
                    if (url.equals(settings.jdbcUrl())) {
                        throw wrapper;
                    }
                    return connection(new AtomicBoolean(),
                            statement(new ArrayList<>(), new AtomicBoolean(), null));
                }));
        assertEquals("`a``b`", SqlDatabaseBootstrap.quoteIdentifier("a`b"));
    }

    private static SqlConnectionSettings settings(String database) {
        return new MySqlSettings(
                "127.0.0.1", 3306, database, "minecraft", "secret", "kcn_",
                "DISABLED", 4, 1, 1_000, 500, 0, 0, 0, 1_000, 0);
    }

    private static SQLException unknownDatabase() {
        return new SQLException("unknown database", "42000", 1049);
    }

    private static Connection connection(AtomicBoolean closed, Statement statement) {
        return (Connection) Proxy.newProxyInstance(
                SqlDatabaseBootstrapTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "createStatement" -> statement;
                    case "close" -> {
                        closed.set(true);
                        yield null;
                    }
                    case "isClosed" -> closed.get();
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Statement statement(
            List<String> statements,
            AtomicBoolean closed,
            SQLException failure
    ) {
        return (Statement) Proxy.newProxyInstance(
                SqlDatabaseBootstrapTest.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "executeUpdate" -> {
                        statements.add((String) args[0]);
                        if (failure != null) {
                            throw failure;
                        }
                        yield 1;
                    }
                    case "close" -> {
                        closed.set(true);
                        yield null;
                    }
                    case "isClosed" -> closed.get();
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}

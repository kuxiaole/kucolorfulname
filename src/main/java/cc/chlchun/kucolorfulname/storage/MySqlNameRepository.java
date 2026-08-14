package cc.chlchun.kucolorfulname.storage;

import cc.chlchun.kucolorfulname.api.AuditAction;
import cc.chlchun.kucolorfulname.api.GrantAuditEntry;
import cc.chlchun.kucolorfulname.api.NameGrant;
import cc.chlchun.kucolorfulname.api.PlayerNameSnapshot;
import cc.chlchun.kucolorfulname.config.MySqlSettings;
import cc.chlchun.kucolorfulname.config.SqlConnectionSettings;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/** MySQL-compatible repository backed by a bounded HikariCP connection pool. */
public class MySqlNameRepository implements NameRepository {
    private static final Pattern TABLE_PREFIX = Pattern.compile("[a-zA-Z0-9_]*");
    private final DataSource dataSource;
    private final AutoCloseable ownedDataSource;
    private final RepositoryExecutor executor;
    private final String grantsTable;
    private final String activeTable;
    private final String auditTable;
    private final String threadNamePrefix;
    private final String databaseLabel;

    public MySqlNameRepository(MySqlSettings settings) throws SQLException {
        this(settings, "com.mysql.cj.jdbc.Driver", "kucolorfulname-MySQL",
                "kucolorfulname-mysql-", "MySQL");
    }

    /**
     * Constructor for another MySQL-wire-compatible driver (for example
     * MariaDB Connector/J). The SQL repository and lifecycle are shared while
     * the selected settings provide the driver-specific JDBC URL.
     */
    protected MySqlNameRepository(
            SqlConnectionSettings settings,
            String driverClassName,
            String poolName,
            String threadNamePrefix,
            String databaseLabel
    ) throws SQLException {
        this(createPool(settings, driverClassName, poolName, databaseLabel), settings.tablePrefix(),
                settings.maximumPoolSize(), true, threadNamePrefix, databaseLabel);
    }

    /** Allows embedding with an externally managed data source. The data source is not closed. */
    public MySqlNameRepository(DataSource dataSource, String tablePrefix) throws SQLException {
        this(dataSource, tablePrefix, 4, false, "kucolorfulname-mysql-", "MySQL");
    }

    /** Constructor for a subclass using an externally managed data source. */
    protected MySqlNameRepository(
            DataSource dataSource,
            String tablePrefix,
            int executorThreads,
            boolean ownsDataSource,
            String threadNamePrefix,
            String databaseLabel
    ) throws SQLException {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        String prefix = validatePrefix(tablePrefix);
        this.grantsTable = prefix + "nickname_grants";
        this.activeTable = prefix + "active_styles";
        this.auditTable = prefix + "grant_audit";
        this.threadNamePrefix = Objects.requireNonNull(threadNamePrefix, "threadNamePrefix");
        this.databaseLabel = Objects.requireNonNull(databaseLabel, "databaseLabel");
        this.ownedDataSource = ownsDataSource && dataSource instanceof AutoCloseable closeable
                ? closeable : null;
        this.executor = new RepositoryExecutor(
                Math.max(1, executorThreads),
                Thread.ofPlatform().daemon(true).name(this.threadNamePrefix, 0).factory()
        );
        try {
            migrate();
        } catch (SQLException exception) {
            close();
            throw exception;
        }
    }

    @Override
    public CompletionStage<List<NameGrant>> grants(UUID playerId) {
        return submit(() -> {
            try (Connection connection = connection()) {
                return findGrants(connection, playerId);
            }
        });
    }

    @Override
    public CompletionStage<PlayerNameSnapshot> snapshot(UUID playerId) {
        return submit(() -> transaction(connection -> {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            return new PlayerNameSnapshot(
                    playerId,
                    findGrants(connection, playerId),
                    findActiveStyle(connection, playerId)
            );
        }));
    }

    @Override
    public CompletionStage<NameGrant> saveGrant(NameGrant grant) {
        return submit(() -> transaction(connection -> {
            Optional<NameGrant> previous = findGrant(connection, grant.playerId(), grant.styleKey(), true);
            String sql = "INSERT INTO " + grantsTable
                    + " (id,player_uuid,style_key,granted_at,expires_at,granted_by,source)"
                    + " VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE"
                    + " id=VALUES(id),granted_at=VALUES(granted_at),expires_at=VALUES(expires_at),"
                    + " granted_by=VALUES(granted_by),source=VALUES(source)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, grant.id().toString());
                statement.setString(2, grant.playerId().toString());
                statement.setString(3, grant.styleKey());
                statement.setLong(4, grant.grantedAt().toEpochMilli());
                statement.setLong(5, grant.expiresAt().toEpochMilli());
                setUuid(statement, 6, grant.grantedBy());
                statement.setString(7, grant.source());
                statement.executeUpdate();
            }
            writeAudit(connection, new GrantAuditEntry(
                    UUID.randomUUID(), grant.playerId(), grant.styleKey(),
                    previous.isPresent() ? AuditAction.REPLACE : AuditAction.GRANT,
                    Instant.now(), grant.grantedBy(), grant.source(),
                    previous.map(NameGrant::expiresAt).orElse(null), grant.expiresAt()
            ));
            return grant;
        }));
    }

    @Override
    public CompletionStage<Optional<NameGrant>> extendGrant(
            UUID playerId,
            String styleKey,
            Duration duration,
            UUID actorId,
            String source
    ) {
        return submit(() -> {
            requirePositive(duration);
            return transaction(connection -> {
                Optional<NameGrant> existing = findGrant(connection, playerId, styleKey, true);
                if (existing.isEmpty()) {
                    return Optional.empty();
                }
                NameGrant previous = existing.get();
                Instant base = previous.expiresAt().isAfter(Instant.now())
                        ? previous.expiresAt() : Instant.now();
                Instant newExpiry = base.plus(duration);
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE " + grantsTable
                                + " SET expires_at=? WHERE player_uuid=? AND style_key=?")) {
                    statement.setLong(1, newExpiry.toEpochMilli());
                    statement.setString(2, playerId.toString());
                    statement.setString(3, styleKey);
                    statement.executeUpdate();
                }
                NameGrant extended = new NameGrant(
                        previous.id(), playerId, styleKey, previous.grantedAt(), newExpiry,
                        previous.grantedBy(), previous.source());
                writeAudit(connection, new GrantAuditEntry(
                        UUID.randomUUID(), playerId, styleKey, AuditAction.EXTEND, Instant.now(), actorId,
                        normalizeSource(source), previous.expiresAt(), newExpiry
                ));
                return Optional.of(extended);
            });
        });
    }

    @Override
    public CompletionStage<Boolean> deleteGrant(
            UUID playerId,
            String styleKey,
            UUID actorId,
            String source
    ) {
        return submit(() -> transaction(connection -> {
            Optional<NameGrant> existing = findGrant(connection, playerId, styleKey, true);
            if (existing.isEmpty()) {
                return false;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + grantsTable + " WHERE player_uuid=? AND style_key=?")) {
                statement.setString(1, playerId.toString());
                statement.setString(2, styleKey);
                statement.executeUpdate();
            }
            writeAudit(connection, new GrantAuditEntry(
                    UUID.randomUUID(), playerId, styleKey, AuditAction.REVOKE, Instant.now(), actorId,
                    normalizeSource(source), existing.get().expiresAt(), null
            ));
            return true;
        }));
    }

    @Override
    public CompletionStage<Optional<String>> activeStyle(UUID playerId) {
        return submit(() -> {
            try (Connection connection = connection()) {
                return findActiveStyle(connection, playerId);
            }
        });
    }

    @Override
    public CompletionStage<Void> setActiveStyle(
            UUID playerId,
            String styleKey,
            UUID actorId,
            String source
    ) {
        return submit(() -> transaction(connection -> {
            String sql = "INSERT INTO " + activeTable
                    + " (player_uuid,style_key,updated_at) VALUES(?,?,?)"
                    + " ON DUPLICATE KEY UPDATE style_key=VALUES(style_key),updated_at=VALUES(updated_at)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerId.toString());
                statement.setString(2, styleKey);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
            writeAudit(connection, new GrantAuditEntry(
                    UUID.randomUUID(), playerId, styleKey, AuditAction.ACTIVATE, Instant.now(), actorId,
                    normalizeSource(source), null, null
            ));
            return null;
        }));
    }

    @Override
    public CompletionStage<Void> clearActiveStyle(UUID playerId, UUID actorId, String source) {
        return submit(() -> transaction(connection -> {
            Optional<String> active = findActiveStyle(connection, playerId, true);
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM " + activeTable + " WHERE player_uuid=?")) {
                statement.setString(1, playerId.toString());
                statement.executeUpdate();
            }
            if (active.isPresent()) {
                writeAudit(connection, new GrantAuditEntry(
                        UUID.randomUUID(), playerId, active.get(), AuditAction.DEACTIVATE,
                        Instant.now(), actorId, normalizeSource(source), null, null
                ));
            }
            return null;
        }));
    }

    @Override
    public CompletionStage<Integer> removeExpired(Instant now) {
        return submit(() -> transaction(connection -> {
            List<NameGrant> expired = expiredGrants(connection, now, true);
            if (expired.isEmpty()) {
                return 0;
            }
            String sql = "DELETE FROM " + grantsTable + " WHERE id=?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (NameGrant grant : expired) {
                    statement.setString(1, grant.id().toString());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            for (NameGrant grant : expired) {
                writeAudit(connection, new GrantAuditEntry(
                        UUID.randomUUID(), grant.playerId(), grant.styleKey(), AuditAction.EXPIRE,
                        now, null, "expiry-task", grant.expiresAt(), null
                ));
            }
            return expired.size();
        }));
    }

    @Override
    public CompletionStage<List<GrantAuditEntry>> audit(UUID playerId, int limit) {
        return submit(() -> {
            int safeLimit = Math.max(1, Math.min(limit, 100));
            List<GrantAuditEntry> entries = new ArrayList<>();
            String sql = "SELECT id,style_key,action,occurred_at,actor_uuid,source,"
                    + "previous_expires_at,new_expires_at FROM " + auditTable
                    + " WHERE player_uuid=? ORDER BY occurred_at DESC,id DESC LIMIT ?";
            try (Connection connection = connection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, playerId.toString());
                statement.setInt(2, safeLimit);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        entries.add(readAudit(result, playerId));
                    }
                }
            }
            return List.copyOf(entries);
        });
    }

    private void migrate() throws SQLException {
        List<String> statements = List.of(
                "CREATE TABLE IF NOT EXISTS " + grantsTable + " ("
                        + "id CHAR(36) CHARACTER SET ascii NOT NULL,"
                        + "player_uuid CHAR(36) CHARACTER SET ascii NOT NULL,"
                        + "style_key VARCHAR(191) CHARACTER SET ascii NOT NULL,"
                        + "granted_at BIGINT NOT NULL,expires_at BIGINT NOT NULL,"
                        + "granted_by CHAR(36) CHARACTER SET ascii NULL,source TEXT NOT NULL,"
                        + "PRIMARY KEY (id),UNIQUE KEY uq_player_style (player_uuid,style_key),"
                        + "KEY idx_player (player_uuid),KEY idx_expiry (expires_at)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",
                "CREATE TABLE IF NOT EXISTS " + activeTable + " ("
                        + "player_uuid CHAR(36) CHARACTER SET ascii NOT NULL,"
                        + "style_key VARCHAR(191) CHARACTER SET ascii NOT NULL,updated_at BIGINT NOT NULL,"
                        + "PRIMARY KEY (player_uuid)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",
                "CREATE TABLE IF NOT EXISTS " + auditTable + " ("
                        + "id CHAR(36) CHARACTER SET ascii NOT NULL,"
                        + "player_uuid CHAR(36) CHARACTER SET ascii NOT NULL,"
                        + "style_key VARCHAR(191) CHARACTER SET ascii NOT NULL,"
                        + "action VARCHAR(32) CHARACTER SET ascii NOT NULL,occurred_at BIGINT NOT NULL,"
                        + "actor_uuid CHAR(36) CHARACTER SET ascii NULL,source TEXT NOT NULL,"
                        + "previous_expires_at BIGINT NULL,new_expires_at BIGINT NULL,"
                        + "PRIMARY KEY (id),KEY idx_player_time (player_uuid,occurred_at)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci"
        );
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private Optional<NameGrant> findGrant(
            Connection connection,
            UUID playerId,
            String styleKey,
            boolean lock
    ) throws SQLException {
        String sql = "SELECT id,style_key,granted_at,expires_at,granted_by,source FROM "
                + grantsTable + " WHERE player_uuid=? AND style_key=?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            statement.setString(2, styleKey);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readGrant(result, playerId)) : Optional.empty();
            }
        }
    }

    private List<NameGrant> findGrants(Connection connection, UUID playerId) throws SQLException {
        List<NameGrant> grants = new ArrayList<>();
        String sql = "SELECT id,style_key,granted_at,expires_at,granted_by,source FROM "
                + grantsTable + " WHERE player_uuid=? ORDER BY granted_at DESC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    grants.add(readGrant(result, playerId));
                }
            }
        }
        return List.copyOf(grants);
    }

    private Optional<String> findActiveStyle(Connection connection, UUID playerId) throws SQLException {
        return findActiveStyle(connection, playerId, false);
    }

    private Optional<String> findActiveStyle(
            Connection connection,
            UUID playerId,
            boolean lock
    ) throws SQLException {
        String sql = "SELECT style_key FROM " + activeTable + " WHERE player_uuid=?"
                + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString("style_key")) : Optional.empty();
            }
        }
    }

    private List<NameGrant> expiredGrants(Connection connection, Instant now, boolean lock)
            throws SQLException {
        List<NameGrant> grants = new ArrayList<>();
        String sql = "SELECT id,player_uuid,style_key,granted_at,expires_at,granted_by,source FROM "
                + grantsTable + " WHERE expires_at<=?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, now.toEpochMilli());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    UUID playerId = UUID.fromString(result.getString("player_uuid"));
                    grants.add(readGrant(result, playerId));
                }
            }
        }
        return grants;
    }

    private void writeAudit(Connection connection, GrantAuditEntry entry) throws SQLException {
        String sql = "INSERT INTO " + auditTable
                + " (id,player_uuid,style_key,action,occurred_at,actor_uuid,source,"
                + "previous_expires_at,new_expires_at) VALUES(?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, entry.id().toString());
            statement.setString(2, entry.playerId().toString());
            statement.setString(3, entry.styleKey());
            statement.setString(4, entry.action().name());
            statement.setLong(5, entry.occurredAt().toEpochMilli());
            setUuid(statement, 6, entry.actorId());
            statement.setString(7, normalizeSource(entry.source()));
            setInstant(statement, 8, entry.previousExpiresAt());
            setInstant(statement, 9, entry.newExpiresAt());
            statement.executeUpdate();
        }
    }

    private NameGrant readGrant(ResultSet result, UUID playerId) throws SQLException {
        String grantedBy = result.getString("granted_by");
        return new NameGrant(
                UUID.fromString(result.getString("id")), playerId, result.getString("style_key"),
                Instant.ofEpochMilli(result.getLong("granted_at")),
                Instant.ofEpochMilli(result.getLong("expires_at")),
                grantedBy == null ? null : UUID.fromString(grantedBy), result.getString("source")
        );
    }

    private GrantAuditEntry readAudit(ResultSet result, UUID playerId) throws SQLException {
        String actor = result.getString("actor_uuid");
        return new GrantAuditEntry(
                UUID.fromString(result.getString("id")), playerId, result.getString("style_key"),
                AuditAction.valueOf(result.getString("action")),
                Instant.ofEpochMilli(result.getLong("occurred_at")),
                actor == null ? null : UUID.fromString(actor), result.getString("source"),
                nullableInstant(result, "previous_expires_at"),
                nullableInstant(result, "new_expires_at")
        );
    }

    private Instant nullableInstant(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private void setInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) statement.setNull(index, Types.BIGINT);
        else statement.setLong(index, value.toEpochMilli());
    }

    private void setUuid(PreparedStatement statement, int index, UUID value) throws SQLException {
        if (value == null) statement.setNull(index, Types.CHAR);
        else statement.setString(index, value.toString());
    }

    private Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    private <T> T transaction(SqlWork<T> work) throws Exception {
        try (Connection connection = connection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    private <T> CompletionStage<T> submit(Callable<T> task) {
        return executor.submit(task);
    }

    private static HikariDataSource createPool(
            SqlConnectionSettings settings,
            String driverClassName,
            String poolName,
            String databaseLabel
    ) throws SQLException {
        SqlDatabaseBootstrap.ensureExists(settings, driverClassName, databaseLabel);
        HikariConfig config = new HikariConfig();
        config.setPoolName(poolName);
        config.setDriverClassName(driverClassName);
        config.setJdbcUrl(settings.jdbcUrl());
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(settings.maximumPoolSize());
        config.setMinimumIdle(settings.minimumIdle());
        config.setConnectionTimeout(settings.connectionTimeoutMs());
        config.setValidationTimeout(settings.validationTimeoutMs());
        config.setIdleTimeout(settings.idleTimeoutMs());
        config.setMaxLifetime(settings.maxLifetimeMs());
        config.setKeepaliveTime(settings.keepaliveTimeMs());
        config.setAutoCommit(true);
        config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        config.setInitializationFailTimeout(settings.connectionTimeoutMs());
        return new HikariDataSource(config);
    }

    private static String validatePrefix(String value) {
        String prefix = Objects.requireNonNull(value, "tablePrefix").trim();
        if (!TABLE_PREFIX.matcher(prefix).matches() || prefix.length() > 32) {
            throw new IllegalArgumentException(
                    "SQL table prefix must contain at most 32 letters, numbers or _");
        }
        return prefix;
    }

    private static void requirePositive(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Duration must be positive");
        }
    }

    private static String normalizeSource(String source) {
        return source == null || source.isBlank() ? "api" : source;
    }

    @Override
    public void close() {
        executor.close();
        if (ownedDataSource != null) {
            try {
                ownedDataSource.close();
            } catch (Exception exception) {
                throw new IllegalStateException(
                        "Unable to close " + databaseLabel + " connection pool", exception);
            }
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T execute(Connection connection) throws Exception;
    }
}

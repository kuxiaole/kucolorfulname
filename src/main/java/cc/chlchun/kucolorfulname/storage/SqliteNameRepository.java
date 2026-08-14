package cc.chlchun.kucolorfulname.storage;

import cc.chlchun.kucolorfulname.api.AuditAction;
import cc.chlchun.kucolorfulname.api.GrantAuditEntry;
import cc.chlchun.kucolorfulname.api.NameGrant;
import cc.chlchun.kucolorfulname.api.PlayerNameSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;

public final class SqliteNameRepository implements NameRepository {
    private final String jdbcUrl;
    private final RepositoryExecutor executor;

    public SqliteNameRepository(Path dataFolder) throws SQLException, IOException {
        Files.createDirectories(dataFolder);
        this.jdbcUrl = "jdbc:sqlite:" + dataFolder.resolve("kucolorfulname.db").toAbsolutePath();
        this.executor = new RepositoryExecutor(
                1,
                Thread.ofPlatform().daemon(true).name("kucolorfulname-sqlite").factory()
        );
        migrate();
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
        return submit(() -> {
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                List<NameGrant> grants = findGrants(connection, playerId);
                Optional<String> active = findActiveStyle(connection, playerId);
                connection.commit();
                return new PlayerNameSnapshot(playerId, grants, active);
            }
        });
    }

    @Override
    public CompletionStage<NameGrant> saveGrant(NameGrant grant) {
        return submit(() -> {
            String sql = "INSERT INTO nickname_grants"
                    + "(id,player_uuid,style_key,granted_at,expires_at,granted_by,source) VALUES(?,?,?,?,?,?,?) "
                    + "ON CONFLICT(player_uuid,style_key) DO UPDATE SET "
                    + "id=excluded.id,granted_at=excluded.granted_at,expires_at=excluded.expires_at,"
                    + "granted_by=excluded.granted_by,source=excluded.source";
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                Optional<NameGrant> previous = findGrant(connection, grant.playerId(), grant.styleKey());
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, grant.id().toString());
                    statement.setString(2, grant.playerId().toString());
                    statement.setString(3, grant.styleKey());
                    statement.setLong(4, grant.grantedAt().toEpochMilli());
                    setInstant(statement, 5, grant.expiresAt());
                    setUuid(statement, 6, grant.grantedBy());
                    statement.setString(7, grant.source());
                    statement.executeUpdate();
                }
                writeAudit(connection, new GrantAuditEntry(
                        UUID.randomUUID(),
                        grant.playerId(),
                        grant.styleKey(),
                        previous.isPresent() ? AuditAction.REPLACE : AuditAction.GRANT,
                        Instant.now(),
                        grant.grantedBy(),
                        grant.source(),
                        previous.map(NameGrant::expiresAt).orElse(null),
                        grant.expiresAt()
                ));
                connection.commit();
            }
            return grant;
        });
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
            if (duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException("Duration must be positive");
            }
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                Optional<NameGrant> existing = findGrant(connection, playerId, styleKey);
                if (existing.isEmpty()) {
                    connection.rollback();
                    return existing;
                }
                NameGrant previous = existing.get();
                Instant base = previous.expiresAt().isAfter(Instant.now()) ? previous.expiresAt() : Instant.now();
                Instant newExpiry = base.plus(duration);
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE nickname_grants SET expires_at=? WHERE player_uuid=? AND style_key=?")) {
                    statement.setLong(1, newExpiry.toEpochMilli());
                    statement.setString(2, playerId.toString());
                    statement.setString(3, styleKey);
                    statement.executeUpdate();
                }
                NameGrant extended = new NameGrant(
                        previous.id(),
                        playerId,
                        styleKey,
                        previous.grantedAt(),
                        newExpiry,
                        previous.grantedBy(),
                        previous.source()
                );
                writeAudit(connection, new GrantAuditEntry(
                        UUID.randomUUID(), playerId, styleKey, AuditAction.EXTEND, Instant.now(), actorId,
                        normalizeSource(source), previous.expiresAt(), newExpiry
                ));
                connection.commit();
                return Optional.of(extended);
            }
        });
    }

    @Override
    public CompletionStage<Boolean> deleteGrant(UUID playerId, String styleKey, UUID actorId, String source) {
        return submit(() -> {
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                Optional<NameGrant> existing = findGrant(connection, playerId, styleKey);
                if (existing.isEmpty()) {
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM nickname_grants WHERE player_uuid=? AND style_key=?")) {
                    statement.setString(1, playerId.toString());
                    statement.setString(2, styleKey);
                    statement.executeUpdate();
                }
                writeAudit(connection, new GrantAuditEntry(
                        UUID.randomUUID(), playerId, styleKey, AuditAction.REVOKE, Instant.now(), actorId,
                        normalizeSource(source), existing.get().expiresAt(), null
                ));
                connection.commit();
                return true;
            }
        });
    }

    @Override
    public CompletionStage<Optional<String>> activeStyle(UUID playerId) {
        return submit(() -> {
            try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                    "SELECT style_key FROM active_styles WHERE player_uuid=?")) {
                statement.setString(1, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(result.getString("style_key")) : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletionStage<Void> setActiveStyle(UUID playerId, String styleKey, UUID actorId, String source) {
        return submit(() -> {
            String sql = "INSERT INTO active_styles(player_uuid,style_key,updated_at) VALUES(?,?,?) "
                    + "ON CONFLICT(player_uuid) DO UPDATE SET "
                    + "style_key=excluded.style_key,updated_at=excluded.updated_at";
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
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
                connection.commit();
            }
            return null;
        });
    }

    @Override
    public CompletionStage<Void> clearActiveStyle(UUID playerId, UUID actorId, String source) {
        return submit(() -> {
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                Optional<String> active = findActiveStyle(connection, playerId);
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM active_styles WHERE player_uuid=?")) {
                    statement.setString(1, playerId.toString());
                    statement.executeUpdate();
                }
                if (active.isPresent()) {
                    writeAudit(connection, new GrantAuditEntry(
                            UUID.randomUUID(), playerId, active.get(), AuditAction.DEACTIVATE, Instant.now(), actorId,
                            normalizeSource(source), null, null
                    ));
                }
                connection.commit();
            }
            return null;
        });
    }

    @Override
    public CompletionStage<Integer> removeExpired(Instant now) {
        return submit(() -> {
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                List<NameGrant> expired = expiredGrants(connection, now);
                int removed;
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM nickname_grants WHERE expires_at<=?")) {
                    delete.setLong(1, now.toEpochMilli());
                    removed = delete.executeUpdate();
                }
                for (NameGrant grant : expired) {
                    writeAudit(connection, new GrantAuditEntry(
                            UUID.randomUUID(), grant.playerId(), grant.styleKey(), AuditAction.EXPIRE, now, null,
                            "expiry-task", grant.expiresAt(), null
                    ));
                }
                connection.commit();
                return removed;
            }
        });
    }

    @Override
    public CompletionStage<List<GrantAuditEntry>> audit(UUID playerId, int limit) {
        return submit(() -> {
            int safeLimit = Math.max(1, Math.min(limit, 100));
            List<GrantAuditEntry> entries = new ArrayList<>();
            String sql = "SELECT id,style_key,action,occurred_at,actor_uuid,source,"
                    + "previous_expires_at,new_expires_at FROM grant_audit "
                    + "WHERE player_uuid=? ORDER BY occurred_at DESC LIMIT ?";
            try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
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

    private void migrate() throws IOException, SQLException {
        try (InputStream stream = getClass().getResourceAsStream("/db/migration/V1__initial.sql")) {
            if (stream == null) {
                throw new IOException("Missing database schema V1__initial.sql");
            }
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            try (Connection connection = connection(); Statement statement = connection.createStatement()) {
                for (String part : sql.split(";")) {
                    if (!part.isBlank()) {
                        statement.execute(part);
                    }
                }
            }
        }
    }

    private Optional<NameGrant> findGrant(Connection connection, UUID playerId, String styleKey) throws SQLException {
        String sql = "SELECT id,style_key,granted_at,expires_at,granted_by,source "
                + "FROM nickname_grants WHERE player_uuid=? AND style_key=?";
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
        String sql = "SELECT id,style_key,granted_at,expires_at,granted_by,source "
                + "FROM nickname_grants WHERE player_uuid=? ORDER BY granted_at DESC";
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
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT style_key FROM active_styles WHERE player_uuid=?")) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString("style_key")) : Optional.empty();
            }
        }
    }

    private List<NameGrant> expiredGrants(Connection connection, Instant now) throws SQLException {
        List<NameGrant> grants = new ArrayList<>();
        String sql = "SELECT id,player_uuid,style_key,granted_at,expires_at,granted_by,source "
                + "FROM nickname_grants WHERE expires_at<=?";
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
        String sql = "INSERT INTO grant_audit(id,player_uuid,style_key,action,occurred_at,actor_uuid,"
                + "source,previous_expires_at,new_expires_at) VALUES(?,?,?,?,?,?,?,?,?)";
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

    private GrantAuditEntry readAudit(ResultSet result, UUID playerId) throws SQLException {
        String actor = result.getString("actor_uuid");
        return new GrantAuditEntry(
                UUID.fromString(result.getString("id")),
                playerId,
                result.getString("style_key"),
                AuditAction.valueOf(result.getString("action")),
                Instant.ofEpochMilli(result.getLong("occurred_at")),
                actor == null ? null : UUID.fromString(actor),
                result.getString("source"),
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
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value.toString());
    }

    private String normalizeSource(String source) {
        return source == null || source.isBlank() ? "api" : source;
    }

    private Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA temp_store=MEMORY");
        }
        return connection;
    }

    private NameGrant readGrant(ResultSet result, UUID playerId) throws SQLException {
        String grantedBy = result.getString("granted_by");
        return new NameGrant(
                UUID.fromString(result.getString("id")),
                playerId,
                result.getString("style_key"),
                Instant.ofEpochMilli(result.getLong("granted_at")),
                Instant.ofEpochMilli(result.getLong("expires_at")),
                grantedBy == null ? null : UUID.fromString(grantedBy),
                result.getString("source")
        );
    }

    private <T> CompletionStage<T> submit(Callable<T> task) {
        return executor.submit(task);
    }

    @Override
    public void close() {
        executor.close();
    }
}

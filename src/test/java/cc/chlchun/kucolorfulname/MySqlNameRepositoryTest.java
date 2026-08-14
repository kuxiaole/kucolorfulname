package cc.chlchun.kucolorfulname;

import cc.chlchun.kucolorfulname.api.AuditAction;
import cc.chlchun.kucolorfulname.api.NameGrant;
import cc.chlchun.kucolorfulname.storage.MySqlNameRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlNameRepositoryTest {
    @Test
    void supportsFullRepositoryContractWithMySqlSqlDialect() throws Exception {
        UUID player = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        Instant now = Instant.now();
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:kcn_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");

        try (MySqlNameRepository repository = new MySqlNameRepository(dataSource, "test_")) {
            repository.setActiveStyle(player, "aurora", actor, "activate")
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals("aurora", repository.activeStyle(player).toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).orElseThrow());

            NameGrant first = new NameGrant(
                    UUID.randomUUID(), player, "aurora", now,
                    now.plus(Duration.ofHours(1)), actor, "first");
            NameGrant replacement = new NameGrant(
                    UUID.randomUUID(), player, "aurora", now.plusMillis(1),
                    now.plus(Duration.ofHours(2)), actor, "replacement");
            repository.saveGrant(first).toCompletableFuture().get(5, TimeUnit.SECONDS);
            repository.saveGrant(replacement).toCompletableFuture().get(5, TimeUnit.SECONDS);

            NameGrant extended = repository.extendGrant(
                            player, "aurora", Duration.ofHours(1), actor, "extend")
                    .toCompletableFuture().get(5, TimeUnit.SECONDS).orElseThrow();
            assertEquals(replacement.expiresAt().plus(Duration.ofHours(1)).toEpochMilli(),
                    extended.expiresAt().toEpochMilli());
            assertEquals(1, repository.grants(player).toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).size());

            int expired = repository.removeExpired(extended.expiresAt().plusMillis(1))
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(1, expired);
            assertTrue(repository.grants(player).toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).isEmpty());
            assertEquals("aurora", repository.activeStyle(player).toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).orElseThrow());

            var audit = repository.audit(player, 20).toCompletableFuture()
                    .get(5, TimeUnit.SECONDS);
            assertTrue(audit.stream().map(entry -> entry.action()).toList().containsAll(
                    java.util.List.of(AuditAction.ACTIVATE, AuditAction.GRANT,
                            AuditAction.REPLACE, AuditAction.EXTEND, AuditAction.EXPIRE)));

            assertFalse(repository.deleteGrant(player, "aurora", actor, "missing")
                    .toCompletableFuture().get(5, TimeUnit.SECONDS));

            NameGrant revocable = new NameGrant(
                    UUID.randomUUID(), player, "ruby", now,
                    now.plus(Duration.ofDays(1)), actor, "revocable");
            repository.saveGrant(revocable).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(repository.deleteGrant(player, "ruby", actor, "revoke")
                    .toCompletableFuture().get(5, TimeUnit.SECONDS));
            assertTrue(repository.audit(player, 20).toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).stream()
                    .anyMatch(entry -> entry.action() == AuditAction.REVOKE));

            repository.clearActiveStyle(player, actor, "deactivate")
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(repository.activeStyle(player).toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).isEmpty());
        }
    }

    @Test
    void rejectsUnsafeTablePrefixBeforeUsingDatabase() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:unused");
        assertThrowsPrefix(dataSource);
    }

    private void assertThrowsPrefix(JdbcDataSource dataSource) {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MySqlNameRepository(dataSource, "unsafe-prefix;"));
    }
}

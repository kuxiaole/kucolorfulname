package cc.chlchun.kucolorfulname;

import cc.chlchun.kucolorfulname.api.AuditAction;
import cc.chlchun.kucolorfulname.api.GrantAuditEntry;
import cc.chlchun.kucolorfulname.api.NameGrant;
import cc.chlchun.kucolorfulname.storage.SqliteNameRepository;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteNameRepositoryTest {
    @TempDir
    Path directory;

    @Test
    void storesActiveStyleWithoutASeparateGrant() throws Exception {
        UUID player = UUID.randomUUID();
        try (SqliteNameRepository repository = new SqliteNameRepository(directory)) {
            repository.setActiveStyle(player, "aurora").toCompletableFuture().get(5, TimeUnit.SECONDS);
            var snapshot = repository.snapshot(player).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(snapshot.grants().isEmpty());
            assertEquals("aurora", snapshot.activeStyleKey().orElseThrow());
        }
    }

    @Test
    void removesExpiredGrantButPreservesActiveSelection() throws Exception {
        UUID player = UUID.randomUUID();
        Instant now = Instant.now();
        NameGrant longLived = new NameGrant(
                UUID.randomUUID(), player, "ruby", now, now.plus(Duration.ofDays(30)), null, "test"
        );
        NameGrant expiring = new NameGrant(
                UUID.randomUUID(), player, "aurora", now, now.plus(Duration.ofMinutes(5)), null, "test"
        );

        try (SqliteNameRepository repository = new SqliteNameRepository(directory)) {
            repository.saveGrant(longLived).toCompletableFuture().get(5, TimeUnit.SECONDS);
            repository.saveGrant(expiring).toCompletableFuture().get(5, TimeUnit.SECONDS);
            repository.setActiveStyle(player, "aurora").toCompletableFuture().get(5, TimeUnit.SECONDS);

            var snapshot = repository.snapshot(player).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(2, snapshot.grants().size());
            assertEquals("aurora", snapshot.activeStyleKey().orElseThrow());

            var grants = repository.grants(player).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(2, grants.size());
            assertEquals(longLived.expiresAt().toEpochMilli(), grants.stream()
                    .filter(grant -> grant.styleKey().equals("ruby"))
                    .findFirst().orElseThrow().expiresAt().toEpochMilli());

            int removed = repository.removeExpired(now.plus(Duration.ofMinutes(6)))
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(1, removed);
            assertEquals("aurora", repository.activeStyle(player).toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).orElseThrow());
            assertFalse(repository.grants(player).toCompletableFuture().get(5, TimeUnit.SECONDS)
                    .stream().anyMatch(grant -> grant.styleKey().equals("aurora")));
        }
    }

    @Test
    void revokesGrantButPreservesActiveSelection() throws Exception {
        UUID player = UUID.randomUUID();
        Instant now = Instant.now();
        NameGrant grant = new NameGrant(
                UUID.randomUUID(), player, "aurora", now, now.plus(Duration.ofDays(7)), null, "test"
        );

        try (SqliteNameRepository repository = new SqliteNameRepository(directory)) {
            repository.saveGrant(grant).toCompletableFuture().get(5, TimeUnit.SECONDS);
            repository.setActiveStyle(player, "aurora").toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertTrue(repository.deleteGrant(player, "aurora").toCompletableFuture()
                    .get(5, TimeUnit.SECONDS));
            assertTrue(repository.grants(player).toCompletableFuture().get(5, TimeUnit.SECONDS).isEmpty());
            assertEquals("aurora", repository.activeStyle(player).toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).orElseThrow());
        }
    }

    @Test
    void recordsReplacementExtensionAndExpiryAuditMetadata() throws Exception {
        UUID player = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        Instant grantedAt = Instant.now().minus(Duration.ofHours(1));
        Instant firstExpiry = Instant.now().plus(Duration.ofHours(1));
        NameGrant first = new NameGrant(
                UUID.randomUUID(), player, "aurora", grantedAt, firstExpiry, actor, "first-grant"
        );
        NameGrant replacement = new NameGrant(
                UUID.randomUUID(), player, "aurora", grantedAt.plusSeconds(5),
                firstExpiry.plus(Duration.ofHours(1)), actor, "replacement"
        );

        try (SqliteNameRepository repository = new SqliteNameRepository(directory)) {
            repository.saveGrant(first).toCompletableFuture().get(5, TimeUnit.SECONDS);
            repository.saveGrant(replacement).toCompletableFuture().get(5, TimeUnit.SECONDS);

            NameGrant extended = repository.extendGrant(
                            player, "aurora", Duration.ofHours(2), actor, "test-extend"
                    ).toCompletableFuture().get(5, TimeUnit.SECONDS).orElseThrow();
            assertEquals(replacement.expiresAt().plus(Duration.ofHours(2)).toEpochMilli(),
                    extended.expiresAt().toEpochMilli());

            int removed = repository.removeExpired(extended.expiresAt().plusMillis(1))
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(1, removed);

            var entries = repository.audit(player, 20).toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(4, entries.size());
            assertTrue(entries.stream().map(GrantAuditEntry::action).toList().containsAll(
                    java.util.List.of(AuditAction.GRANT, AuditAction.REPLACE, AuditAction.EXTEND, AuditAction.EXPIRE)
            ));

            GrantAuditEntry replaceAudit = entry(entries, AuditAction.REPLACE);
            assertEquals("replacement", replaceAudit.source());
            assertEquals(first.expiresAt().toEpochMilli(), replaceAudit.previousExpiresAt().toEpochMilli());
            assertEquals(replacement.expiresAt().toEpochMilli(), replaceAudit.newExpiresAt().toEpochMilli());

            GrantAuditEntry extendAudit = entry(entries, AuditAction.EXTEND);
            assertEquals(actor, extendAudit.actorId());
            assertEquals("test-extend", extendAudit.source());
            assertEquals(replacement.expiresAt().toEpochMilli(), extendAudit.previousExpiresAt().toEpochMilli());
            assertEquals(extended.expiresAt().toEpochMilli(), extendAudit.newExpiresAt().toEpochMilli());

            GrantAuditEntry expireAudit = entry(entries, AuditAction.EXPIRE);
            assertEquals("expiry-task", expireAudit.source());
            assertEquals(extended.expiresAt().toEpochMilli(), expireAudit.previousExpiresAt().toEpochMilli());
            assertNotNull(expireAudit.occurredAt());
        }
    }

    private GrantAuditEntry entry(java.util.List<GrantAuditEntry> entries, AuditAction action) {
        return entries.stream().filter(entry -> entry.action() == action).findFirst().orElseThrow();
    }
}

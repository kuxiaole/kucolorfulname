package cc.chlchun.kucolorfulname;

import cc.chlchun.kucolorfulname.api.NameGrant;
import cc.chlchun.kucolorfulname.storage.MariaDbNameRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MariaDbNameRepositoryTest {
    @Test
    void usesSharedSqlContractWithMariaDbRepositoryEntryPoint() throws Exception {
        UUID player = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        Instant now = Instant.now();
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:mariadb_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");

        try (MariaDbNameRepository repository = new MariaDbNameRepository(dataSource, "test_")) {
            NameGrant grant = new NameGrant(
                    UUID.randomUUID(), player, "aurora", now,
                    now.plus(Duration.ofHours(1)), actor, "mariadb-test");
            repository.saveGrant(grant).toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(1, repository.grants(player).toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).size());

            repository.setActiveStyle(player, "aurora", actor, "mariadb-test")
                    .toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals("aurora", repository.snapshot(player).toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).activeStyleKey().orElse(null));
            assertTrue(repository.activeStyle(player).toCompletableFuture()
                    .get(5, TimeUnit.SECONDS).isPresent());
        }
    }
}

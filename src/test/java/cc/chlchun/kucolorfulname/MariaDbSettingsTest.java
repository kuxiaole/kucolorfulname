package cc.chlchun.kucolorfulname;

import cc.chlchun.kucolorfulname.config.MariaDbSettings;
import cc.chlchun.kucolorfulname.config.StorageSettings;
import cc.chlchun.kucolorfulname.config.StorageType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MariaDbSettingsTest {
    @Test
    void loadsMariaDbSettingsWithMariaDbDriverUrl() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("storage.type", "mariadb");
        configuration.set("storage.mariadb.host", "2001:db8::1");
        configuration.set("storage.mariadb.port", 3307);
        configuration.set("storage.mariadb.database", "color_names");
        configuration.set("storage.mariadb.username", "minecraft");
        configuration.set("storage.mariadb.password", "secret&value");
        configuration.set("storage.mariadb.table-prefix", "network_");
        configuration.set("storage.mariadb.ssl-mode", "verify_identity");

        StorageSettings storage = StorageSettings.load(configuration);
        MariaDbSettings mariadb = storage.mariadb();

        assertEquals(StorageType.MARIADB, storage.type());
        assertEquals("network_", mariadb.tablePrefix());
        assertTrue(mariadb.jdbcUrl().startsWith("jdbc:mariadb://[2001:db8::1]:3307/color_names?"));
        assertTrue(mariadb.serverJdbcUrl().startsWith("jdbc:mariadb://[2001:db8::1]:3307/?"));
        assertTrue(mariadb.jdbcUrl().contains("sslMode=verify-full"));
        assertTrue(!mariadb.jdbcUrl().contains(mariadb.password()));
        org.mariadb.jdbc.Driver driver = new org.mariadb.jdbc.Driver();
        assertTrue(driver.acceptsURL(mariadb.jdbcUrl()));
        assertTrue(driver.acceptsURL(mariadb.serverJdbcUrl()));
        assertDoesNotThrow(() -> driver.getPropertyInfo(mariadb.jdbcUrl(), null));
        assertDoesNotThrow(() -> driver.getPropertyInfo(mariadb.serverJdbcUrl(), null));
    }

    @Test
    void rejectsUnsafeMariaDbSettings() {
        YamlConfiguration invalidPrefix = new YamlConfiguration();
        invalidPrefix.set("storage.mariadb.table-prefix", "bad-prefix;");
        assertThrows(IllegalArgumentException.class, () -> StorageSettings.load(invalidPrefix));

        YamlConfiguration invalidSsl = new YamlConfiguration();
        invalidSsl.set("storage.mariadb.ssl-mode", "trust-everything");
        assertThrows(IllegalArgumentException.class, () -> StorageSettings.load(invalidSsl));

        YamlConfiguration longDatabase = new YamlConfiguration();
        longDatabase.set("storage.mariadb.database", "a".repeat(65));
        assertThrows(IllegalArgumentException.class, () -> StorageSettings.load(longDatabase));
    }
}

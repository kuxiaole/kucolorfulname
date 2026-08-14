package cc.chlchun.kucolorfulname;

import cc.chlchun.kucolorfulname.config.MySqlSettings;
import cc.chlchun.kucolorfulname.config.StorageSettings;
import cc.chlchun.kucolorfulname.config.StorageType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MySqlSettingsTest {
    @Test
    void loadsValidatedMySqlSettingsAndBuildsSafeJdbcUrl() throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("storage.type", "mysql");
        configuration.set("storage.mysql.host", "2001:db8::1");
        configuration.set("storage.mysql.port", 3307);
        configuration.set("storage.mysql.database", "color_names");
        configuration.set("storage.mysql.username", "minecraft");
        configuration.set("storage.mysql.password", "secret&value");
        configuration.set("storage.mysql.table-prefix", "network_");
        configuration.set("storage.mysql.ssl-mode", "required");

        StorageSettings storage = StorageSettings.load(configuration);
        MySqlSettings mysql = storage.mysql();

        assertEquals(StorageType.MYSQL, storage.type());
        assertEquals("network_", mysql.tablePrefix());
        assertTrue(mysql.jdbcUrl().startsWith("jdbc:mysql://[2001:db8::1]:3307/color_names?"));
        assertTrue(mysql.serverJdbcUrl().startsWith("jdbc:mysql://[2001:db8::1]:3307/?"));
        assertTrue(mysql.jdbcUrl().contains("sslMode=REQUIRED"));
        assertTrue(!mysql.jdbcUrl().contains(mysql.password()));
        com.mysql.cj.jdbc.Driver driver = new com.mysql.cj.jdbc.Driver();
        assertTrue(driver.acceptsURL(mysql.jdbcUrl()));
        assertTrue(driver.acceptsURL(mysql.serverJdbcUrl()));
    }

    @Test
    void defaultsToSqliteAndRejectsUnsafeConnectionSettings() {
        assertEquals(StorageType.SQLITE, StorageSettings.load(new YamlConfiguration()).type());

        YamlConfiguration mysqlDefaults = new YamlConfiguration();
        mysqlDefaults.set("storage.type", "MYSQL");
        assertEquals("kucolorfulname_", StorageSettings.load(mysqlDefaults)
                .mysql().tablePrefix());

        YamlConfiguration invalidPrefix = new YamlConfiguration();
        invalidPrefix.set("storage.mysql.table-prefix", "bad-prefix;");
        assertThrows(IllegalArgumentException.class, () -> StorageSettings.load(invalidPrefix));

        YamlConfiguration invalidPool = new YamlConfiguration();
        invalidPool.set("storage.mysql.maximum-pool-size", 2);
        invalidPool.set("storage.mysql.minimum-idle", 3);
        assertThrows(IllegalArgumentException.class, () -> StorageSettings.load(invalidPool));

        YamlConfiguration invalidSsl = new YamlConfiguration();
        invalidSsl.set("storage.mysql.ssl-mode", "trust-everything");
        assertThrows(IllegalArgumentException.class, () -> StorageSettings.load(invalidSsl));

        YamlConfiguration longDatabase = new YamlConfiguration();
        longDatabase.set("storage.mysql.database", "a".repeat(65));
        assertThrows(IllegalArgumentException.class, () -> StorageSettings.load(longDatabase));
    }
}

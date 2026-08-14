package cc.chlchun.kucolorfulname.storage;

import cc.chlchun.kucolorfulname.config.MariaDbSettings;
import java.sql.SQLException;
import javax.sql.DataSource;

/** MariaDB repository using the official MariaDB Connector/J driver. */
public final class MariaDbNameRepository extends MySqlNameRepository {
    public MariaDbNameRepository(MariaDbSettings settings) throws SQLException {
        super(settings, "org.mariadb.jdbc.Driver", "kucolorfulname-MariaDB",
                "kucolorfulname-mariadb-", "MariaDB");
    }

    /** Allows tests or embedding applications to provide an external pool. */
    public MariaDbNameRepository(DataSource dataSource, String tablePrefix) throws SQLException {
        super(dataSource, tablePrefix, 4, false, "kucolorfulname-mariadb-", "MariaDB");
    }
}

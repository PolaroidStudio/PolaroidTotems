package me.juancayc.polaroidtotems.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The SQLite/MySQL implementation of {@link CooldownRepository}.
 *
 * <h2>One table, two dialects</h2>
 *
 * <p>The schema and every query are shared; exactly two fragments differ, and both are the upsert.
 * SQLite spells it {@code ON CONFLICT(...) DO UPDATE SET}, MySQL spells it
 * {@code ON DUPLICATE KEY UPDATE}. Keeping the difference down to one string is why the rest of this
 * class has no branches in it.
 *
 * <h2>Types chosen for portability, not elegance</h2>
 *
 * <p>The UUID is stored as its 36-character text form in a {@code VARCHAR(36)} rather than as 16
 * binary bytes. Binary is smaller and marginally faster to index, and it is also unreadable in every
 * tool an operator would use to inspect the table — and this table will be inspected, because
 * "why is my cooldown stuck" is the first support question a cooldown feature generates. The row
 * count here is bounded by (players x totem types), so the space argument never becomes real.
 *
 * <p>The expiry is a {@code BIGINT} of epoch millis, not a {@code TIMESTAMP}. {@code TIMESTAMP}
 * carries timezone semantics that differ between the two backends and silently change when a server
 * is moved; epoch millis compare as integers everywhere and mean the same instant in every region.
 *
 * <h2>Threading</h2>
 *
 * <p>Every public method blocks on JDBC. None of them may be called from the main thread — see
 * {@link CooldownRepository}.
 */
public final class SqlCooldownRepository implements CooldownRepository {

    /**
     * The one table this plugin owns.
     *
     * <p>Prefixed with the plugin name because a MySQL backend is a SHARED database by definition:
     * `type: mysql` exists so several servers can agree on one set of cooldowns, and those servers
     * frequently point every plugin at the same schema. An unprefixed `cooldowns` table is a
     * collision waiting to happen.
     */
    private static final String TABLE = "polaroidtotems_cooldowns";

    private final HikariDataSource dataSource;
    private final StorageSettings.Type type;

    private SqlCooldownRepository(HikariDataSource dataSource, StorageSettings.Type type) {
        this.dataSource = dataSource;
        this.type = type;
    }

    /**
     * Opens the pool for the configured backend. Called once, from {@code onEnable}.
     *
     * @param settings   the parsed {@code data.yml}
     * @param dataFolder the plugin's data folder; the sqlite file is created in {@code data/} inside
     *                   it, and nowhere else
     */
    public static SqlCooldownRepository open(StorageSettings settings, File dataFolder) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("PolaroidTotems-" + settings.type().name().toLowerCase(java.util.Locale.ROOT));
        hikari.setMaximumPoolSize(settings.effectivePoolSize());

        if (settings.type() == StorageSettings.Type.SQLITE) {
            // The data/ folder is created ONLY for sqlite, and only here. Nothing else in the plugin
            // ever walks it: a reload that listed this folder would eventually parse the .db as YAML.
            File folder = new File(dataFolder, StorageSettings.DATA_FOLDER);
            if (!folder.exists() && !folder.mkdirs()) {
                throw new StorageException("Could not create the storage folder at " + folder);
            }
            File database = new File(folder, settings.sqliteFile());

            hikari.setDriverClassName("org.sqlite.JDBC");
            hikari.setJdbcUrl("jdbc:sqlite:" + database.getAbsolutePath());
            // SQLite has no server to ping, and Hikari's default validation query behaves oddly on
            // some driver builds; SELECT 1 is the documented choice for this driver.
            hikari.setConnectionTestQuery("SELECT 1");
        } else {
            hikari.setDriverClassName("com.mysql.cj.jdbc.Driver");
            hikari.setJdbcUrl("jdbc:mysql://" + settings.host() + ":" + settings.port() + "/"
                    + settings.database() + "?useUnicode=true&characterEncoding=utf8");
            hikari.setUsername(settings.username());
            hikari.setPassword(settings.password());
        }

        try {
            return new SqlCooldownRepository(new HikariDataSource(hikari), settings.type());
        } catch (RuntimeException poolRefused) {
            // Hikari throws on a bad URL, a missing driver or an unreachable MySQL. Wrapped so the
            // enable path sees one exception type rather than whatever Hikari felt like raising.
            throw new StorageException("Could not open the " + settings.type() + " connection pool.",
                    poolRefused);
        }
    }

    @Override
    public void createSchema() {
        // IF NOT EXISTS in both dialects, so this is safe to run on a fresh database and on one that
        // has been in use for a year. It is still only run once, at enable — see the interface.
        String create = "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "player VARCHAR(36) NOT NULL, "
                + "totem_id VARCHAR(64) NOT NULL, "
                + "expires_at BIGINT NOT NULL, "
                + "PRIMARY KEY (player, totem_id))";

        // Indexed AFTER the table exists, never before — a column cannot be indexed until the
        // statement that adds it has run. The index serves purgeExpired() alone: every other query
        // is satisfied by the primary key's leading `player` column.
        String index = "CREATE INDEX IF NOT EXISTS idx_" + TABLE + "_expires_at "
                + "ON " + TABLE + " (expires_at)";

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(create);
            statement.executeUpdate(index);
        } catch (SQLException failed) {
            throw new StorageException("Could not create the cooldown table.", failed);
        }
    }

    @Override
    public List<CooldownRow> loadActive(UUID player, long now) {
        String sql = "SELECT totem_id, expires_at FROM " + TABLE
                + " WHERE player = ? AND expires_at > ?";

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, player.toString());
            statement.setLong(2, now);

            List<CooldownRow> rows = new ArrayList<>();
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    rows.add(new CooldownRow(player, results.getString(1), results.getLong(2)));
                }
            }
            return rows;
        } catch (SQLException failed) {
            throw new StorageException("Could not load cooldowns for " + player + ".", failed);
        }
    }

    @Override
    public void saveAll(Collection<CooldownRow> rows) {
        if (rows == null || rows.isEmpty()) return; // never open a connection for nothing

        String sql = upsertSql();
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                for (CooldownRow row : rows) {
                    statement.setString(1, row.player().toString());
                    statement.setString(2, row.totemId());
                    statement.setLong(3, row.expiresAt());
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (SQLException batchFailed) {
                // All or nothing. A half-written flush would leave some cooldowns persisted and
                // others not, and the cache has already cleared its dirty flags optimistically —
                // so a partial commit is strictly worse than a rollback the next flush retries.
                connection.rollback();
                throw batchFailed;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException failed) {
            throw new StorageException("Could not save " + rows.size() + " cooldown row(s).", failed);
        } finally {
            closeQuietly(connection);
        }
    }

    @Override
    public int purgeExpired(long now) {
        String sql = "DELETE FROM " + TABLE + " WHERE expires_at <= ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, now);
            return statement.executeUpdate();
        } catch (SQLException failed) {
            throw new StorageException("Could not purge expired cooldowns.", failed);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }

    /**
     * The ONE statement that differs between the two backends.
     *
     * <p>Both do the same thing — insert, or overwrite the expiry of an existing (player, totem_id)
     * — and both rely on the primary key to detect the conflict. SQLite needs the conflicting
     * columns named explicitly; MySQL infers them from whichever unique key was violated.
     */
    private String upsertSql() {
        String insert = "INSERT INTO " + TABLE + " (player, totem_id, expires_at) VALUES (?, ?, ?) ";
        return type == StorageSettings.Type.SQLITE
                ? insert + "ON CONFLICT(player, totem_id) DO UPDATE SET expires_at = excluded.expires_at"
                : insert + "ON DUPLICATE KEY UPDATE expires_at = VALUES(expires_at)";
    }

    /**
     * Returns a connection to the pool, swallowing a close failure.
     *
     * <p>Not a {@code try-with-resources} above because the transaction handling needs the connection
     * in scope across two nested blocks. A failure to CLOSE is not worth masking the real exception
     * that is already propagating.
     */
    private static void closeQuietly(Connection connection) {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // The pool reclaims it regardless; there is nothing actionable to report.
        }
    }
}

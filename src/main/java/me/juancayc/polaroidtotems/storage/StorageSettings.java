package me.juancayc.polaroidtotems.storage;

import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Locale;

/**
 * Everything {@code data.yml} says, as one immutable value.
 *
 * <p>A record rather than a manager with getters, because of what {@code data.yml} is for: it is
 * read ONCE, at enable, to decide which pool to open. Nothing in this plugin re-reads it at runtime,
 * so there is no reload seam to design around — {@link
 * me.juancayc.polaroidtotems.PolaroidTotemsPlugin#reloadEverything} deliberately leaves the pool
 * alone (see the comment there).
 *
 * <p>Storage settings live here and nowhere else. They are specifically NOT in {@code config.yml}:
 * that file is re-read on every reload, and a connection string that appears to be reloadable but
 * is not is the fastest way to get an operator to change a host, run {@code /totems reload}, and
 * believe the plugin is now talking to a database it has never opened.
 *
 * @param type      the chosen backend
 * @param sqliteFile the sqlite file NAME (never a path); always created under {@code data/}
 * @param host      MySQL host, ignored under sqlite
 * @param port      MySQL port, ignored under sqlite
 * @param database  MySQL schema name, ignored under sqlite
 * @param username  MySQL user, ignored under sqlite
 * @param password  MySQL password, ignored under sqlite
 * @param poolSize  MySQL pool size. SQLite ignores it entirely — see {@link #effectivePoolSize()}
 */
public record StorageSettings(Type type,
                              String sqliteFile,
                              String host,
                              int port,
                              String database,
                              String username,
                              String password,
                              int poolSize) {

    /** The backends this plugin can open. */
    public enum Type {
        SQLITE,
        MYSQL
    }

    /**
     * The one and only legal SQLite pool size.
     *
     * <p>Not a tuning knob. SQLite permits a single writer at a time, so additional connections do
     * not parallelise anything: they queue on the write lock while occupying pool slots, so the next
     * caller asking for a connection waits behind writers that are themselves waiting. Raising it to
     * hide a main-thread stall makes the stall worse.
     */
    public static final int SQLITE_POOL_SIZE = 1;

    /** The folder, relative to the plugin data folder, that holds the sqlite file. */
    public static final String DATA_FOLDER = "data";

    /**
     * Reads a loaded {@code data.yml}.
     *
     * <p>Static and free of {@code JavaPlugin} so a test can hand it a {@link YamlConfiguration}
     * built from a string, matching {@link me.juancayc.polaroidtotems.config.TotemsConfig#parse}.
     *
     * <p>An unrecognised {@code type} falls back to SQLite rather than throwing. Refusing to enable
     * over a typo in a backend name would cost the server every totem feature, including the ones
     * that need no database at all.
     */
    public static StorageSettings parse(YamlConfiguration cfg,
                                        java.util.function.Consumer<String> onWarning) {
        String rawType = cfg.getString("type", "sqlite");
        Type type;
        try {
            type = Type.valueOf(rawType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknownBackend) {
            onWarning.accept("data.yml has an unknown storage type '" + rawType
                    + "'; falling back to sqlite.");
            type = Type.SQLITE;
        }

        String sqliteFile = cfg.getString("sqlite.file", "totems.db");
        if (sqliteFile == null || sqliteFile.isBlank()) sqliteFile = "totems.db";

        return new StorageSettings(
                type,
                sqliteFile.trim(),
                cfg.getString("mysql.host", "127.0.0.1"),
                cfg.getInt("mysql.port", 3306),
                cfg.getString("mysql.database", "polaroidtotems"),
                cfg.getString("mysql.username", "root"),
                cfg.getString("mysql.password", ""),
                Math.max(1, cfg.getInt("mysql.pool-size", 8)));
    }

    /**
     * The pool size actually used, which for SQLite is not the configured one.
     *
     * <p>{@link #SQLITE_POOL_SIZE} wins unconditionally under SQLite: the file has no key to raise
     * it, and this method exists so that stays true even if a future edit adds one by accident.
     */
    public int effectivePoolSize() {
        return type == Type.SQLITE ? SQLITE_POOL_SIZE : poolSize;
    }

    /**
     * Whether this describes the same backend as another snapshot.
     *
     * <p>Used by the reload path to decide whether to warn. Credentials are compared too: a changed
     * password is just as much a "restart required" as a changed host, and telling the operator only
     * about the host would leave them staring at a pool still authenticated as the old user.
     */
    public boolean describesSameBackend(StorageSettings other) {
        if (other == null) return false;
        if (type != other.type) return false;
        return type == Type.SQLITE
                ? sqliteFile.equals(other.sqliteFile)
                : host.equals(other.host) && port == other.port && database.equals(other.database)
                        && username.equals(other.username) && password.equals(other.password);
    }
}

package me.juancayc.polaroidtotems.storage;

/**
 * Anything the storage layer could not do.
 *
 * <p>Exists so {@link java.sql.SQLException} stops at the repository boundary. A service that
 * catches {@code SQLException} is a service that has silently committed to JDBC forever: swapping
 * the backend, or adding a flat-file one, would mean editing every caller. Callers here catch this
 * instead and know nothing about what is underneath.
 *
 * <p>Unchecked on purpose. The callers are scheduler tasks — a join load, a timed flush, a disable
 * flush — and none of them has a meaningful recovery beyond "log it and carry on with the cache".
 * A checked exception would buy a {@code throws} clause on every one of them and change no outcome.
 */
public final class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}

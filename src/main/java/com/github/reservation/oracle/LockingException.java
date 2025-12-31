package com.github.reservation.oracle;

/**
 * Exception thrown when a database locking operation fails.
 */
public class LockingException extends Exception {

    public LockingException(String message) {
        super(message);
    }

    public LockingException(String message, Throwable cause) {
        super(message, cause);
    }
}

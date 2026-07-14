package com.github.reservation;

/**
 * Thrown when an invalid reservation identifier is provided (e.g., null or empty).
 */
public class InvalidReservationKeyException extends IllegalArgumentException {

    public InvalidReservationKeyException(String message) {
        super(message);
    }
}

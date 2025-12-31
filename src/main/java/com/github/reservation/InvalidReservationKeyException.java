package com.github.reservation;

/**
 * Thrown when invalid arguments are provided (e.g., delimiter in domain/identifier).
 */
public class InvalidReservationKeyException extends IllegalArgumentException {

    public InvalidReservationKeyException(String message) {
        super(message);
    }
}

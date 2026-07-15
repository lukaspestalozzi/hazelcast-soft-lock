package com.github.reservation;

/**
 * Thrown when an invalid reservation identifier is provided (e.g., null or empty).
 *
 * <p>Deliberately extends {@link IllegalArgumentException} rather than
 * {@link ReservationException}: an invalid identifier is a programming error in the
 * caller, not a reservation runtime condition, so it should behave like the standard
 * argument-validation exceptions. Note that {@code catch (ReservationException e)}
 * therefore does not catch it.</p>
 */
public class InvalidReservationKeyException extends IllegalArgumentException {

    public InvalidReservationKeyException(String message) {
        super(message);
    }
}

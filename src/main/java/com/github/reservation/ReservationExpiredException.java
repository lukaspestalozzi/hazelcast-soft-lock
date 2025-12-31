package com.github.reservation;

/**
 * Thrown when attempting to unlock a reservation whose lease has already expired.
 *
 * <p>This exception indicates a potential violation of the critical section
 * guarantee - another process may have acquired the reservation after expiration.</p>
 */
public class ReservationExpiredException extends ReservationException {

    private final String domain;
    private final String identifier;

    public ReservationExpiredException(String domain, String identifier) {
        super(String.format(
            "Reservation [%s::%s] lease expired before unlock. " +
            "Critical section guarantee may be violated.",
            domain, identifier));
        this.domain = domain;
        this.identifier = identifier;
    }

    public String getDomain() {
        return domain;
    }

    public String getIdentifier() {
        return identifier;
    }
}

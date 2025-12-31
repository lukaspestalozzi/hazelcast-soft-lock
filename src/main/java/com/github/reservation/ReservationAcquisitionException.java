package com.github.reservation;

/**
 * Thrown when a reservation cannot be acquired.
 */
public class ReservationAcquisitionException extends ReservationException {

    private final String domain;
    private final String identifier;

    public ReservationAcquisitionException(String domain, String identifier, String message) {
        super(message);
        this.domain = domain;
        this.identifier = identifier;
    }

    public ReservationAcquisitionException(String domain, String identifier, String message, Throwable cause) {
        super(message, cause);
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

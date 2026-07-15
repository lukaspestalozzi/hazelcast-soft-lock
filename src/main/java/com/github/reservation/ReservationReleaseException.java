package com.github.reservation;

/**
 * Thrown when a reservation cannot be released because of an infrastructure failure
 * (e.g. the backend is unreachable).
 *
 * <p>Unlike {@link ReservationExpiredException}, this does not mean ownership was lost:
 * the backend-side outcome is unknown and the local hold state is kept, so the caller
 * may retry {@code unlock()}. If the release ultimately never reaches the backend, the
 * lease expiry releases the reservation.</p>
 */
public class ReservationReleaseException extends ReservationException {

    private final String domain;
    private final String identifier;

    public ReservationReleaseException(String domain, String identifier, String message, Throwable cause) {
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

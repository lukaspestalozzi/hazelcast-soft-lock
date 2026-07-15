package com.github.reservation;

/**
 * Thrown when attempting to unlock a reservation whose ownership was already lost.
 *
 * <p>The most common cause is lease expiry, but the backend cannot distinguish the
 * causes: an administrative {@link Reservation#forceUnlock()} by another thread or
 * process, or ownership lost through a cluster event (e.g. split-brain healing),
 * surfaces as this same exception.</p>
 *
 * <p>This exception indicates a potential violation of the critical section
 * guarantee - another process may have acquired the reservation in the meantime.</p>
 */
public class ReservationExpiredException extends ReservationException {

    private final String domain;
    private final String identifier;

    public ReservationExpiredException(String domain, String identifier) {
        super(String.format(
            "Reservation [%s::%s] was no longer held by this thread at unlock. " +
            "The lease most likely expired, but the reservation may also have been " +
            "force-released. Critical section guarantee may be violated.",
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

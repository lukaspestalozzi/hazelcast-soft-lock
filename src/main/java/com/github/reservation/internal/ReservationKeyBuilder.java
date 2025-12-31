package com.github.reservation.internal;

import com.github.reservation.InvalidReservationKeyException;

/**
 * Internal utility for building and validating reservation keys.
 */
public final class ReservationKeyBuilder {

    private final String delimiter;

    public ReservationKeyBuilder(String delimiter) {
        this.delimiter = delimiter;
    }

    public String buildKey(String domain, String identifier) {
        validate(domain, "domain");
        validate(identifier, "identifier");
        return domain + delimiter + identifier;
    }

    public String getDelimiter() {
        return delimiter;
    }

    private void validate(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new InvalidReservationKeyException(name + " must not be null or empty");
        }
        if (value.contains(delimiter)) {
            throw new InvalidReservationKeyException(
                name + " must not contain the delimiter '" + delimiter + "': " + value);
        }
    }
}

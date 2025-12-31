package com.github.reservation;

import com.github.reservation.internal.ReservationKeyBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for ReservationKeyBuilder.
 */
class ReservationKeyBuilderTest {

    private final ReservationKeyBuilder keyBuilder = new ReservationKeyBuilder("::");

    @Test
    void shouldBuildKeyWithDefaultDelimiter() {
        String key = keyBuilder.buildKey("orders", "12345");
        assertThat(key).isEqualTo("orders::12345");
    }

    @Test
    void shouldBuildKeyWithCustomDelimiter() {
        ReservationKeyBuilder customBuilder = new ReservationKeyBuilder("---");
        String key = customBuilder.buildKey("orders", "12345");
        assertThat(key).isEqualTo("orders---12345");
    }

    @Test
    void shouldRejectNullDomain() {
        assertThatThrownBy(() -> keyBuilder.buildKey(null, "12345"))
            .isInstanceOf(InvalidReservationKeyException.class)
            .hasMessageContaining("domain");
    }

    @Test
    void shouldRejectEmptyDomain() {
        assertThatThrownBy(() -> keyBuilder.buildKey("", "12345"))
            .isInstanceOf(InvalidReservationKeyException.class)
            .hasMessageContaining("domain");
    }

    @Test
    void shouldRejectNullIdentifier() {
        assertThatThrownBy(() -> keyBuilder.buildKey("orders", null))
            .isInstanceOf(InvalidReservationKeyException.class)
            .hasMessageContaining("identifier");
    }

    @Test
    void shouldRejectEmptyIdentifier() {
        assertThatThrownBy(() -> keyBuilder.buildKey("orders", ""))
            .isInstanceOf(InvalidReservationKeyException.class)
            .hasMessageContaining("identifier");
    }

    @Test
    void shouldRejectDomainContainingDelimiter() {
        assertThatThrownBy(() -> keyBuilder.buildKey("order::type", "12345"))
            .isInstanceOf(InvalidReservationKeyException.class)
            .hasMessageContaining("delimiter");
    }

    @Test
    void shouldRejectIdentifierContainingDelimiter() {
        assertThatThrownBy(() -> keyBuilder.buildKey("orders", "123::456"))
            .isInstanceOf(InvalidReservationKeyException.class)
            .hasMessageContaining("delimiter");
    }

    @Test
    void shouldReturnConfiguredDelimiter() {
        assertThat(keyBuilder.getDelimiter()).isEqualTo("::");
    }

    @Test
    void shouldAllowSpecialCharactersInDomainExceptDelimiter() {
        String key = keyBuilder.buildKey("order-type_v2", "12345");
        assertThat(key).isEqualTo("order-type_v2::12345");
    }

    @Test
    void shouldAllowSpecialCharactersInIdentifierExceptDelimiter() {
        String key = keyBuilder.buildKey("orders", "order-12345_v2");
        assertThat(key).isEqualTo("orders::order-12345_v2");
    }
}

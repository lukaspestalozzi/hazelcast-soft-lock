package com.github.reservation.hazelcast;

import com.github.reservation.AbstractReservationManagerTest;
import com.github.reservation.ReservationManager;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.UUID;

/**
 * Tests for the Hazelcast-backed ReservationManager implementation.
 * Uses embedded Hazelcast for fast, isolated tests.
 */
class HazelcastReservationManagerTest extends AbstractReservationManagerTest {

    private static HazelcastInstance hazelcast;
    private String mapName;

    @BeforeAll
    static void setupHazelcast() {
        Config config = new Config();
        config.setClusterName("test-" + UUID.randomUUID());
        // Disable network for embedded testing
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        hazelcast = Hazelcast.newHazelcastInstance(config);
    }

    @AfterAll
    static void teardownHazelcast() {
        if (hazelcast != null) {
            hazelcast.shutdown();
        }
    }

    @Override
    protected ReservationManager createManager(Duration leaseTime) {
        mapName = "test-reservations-" + UUID.randomUUID();
        return ReservationManager.hazelcast(hazelcast)
            .leaseTime(leaseTime)
            .mapName(mapName)
            .build();
    }

    @Override
    protected void cleanup() {
        if (mapName != null && hazelcast != null) {
            try {
                hazelcast.getMap(mapName).destroy();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
    }

    // ==================== Hazelcast-Specific Tests ====================

    @Test
    void shouldStoreDebugValueInMap() {
        var reservation = manager.getReservation("orders", "debug-test");
        reservation.lock();

        try {
            String value = hazelcast.getMap(mapName).get("orders::debug-test").toString();
            org.assertj.core.api.Assertions.assertThat(value).contains("holder=");
            org.assertj.core.api.Assertions.assertThat(value).contains("acquired=");
        } finally {
            reservation.unlock();
        }
    }

    @Test
    void shouldReturnCorrectMapName() {
        HazelcastReservationManager hzManager = (HazelcastReservationManager) manager;
        org.assertj.core.api.Assertions.assertThat(hzManager.getMapName()).isEqualTo(mapName);
    }
}

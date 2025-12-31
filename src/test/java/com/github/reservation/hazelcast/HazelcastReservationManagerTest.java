package com.github.reservation.hazelcast;

import com.github.reservation.AbstractReservationManagerTest;
import com.github.reservation.ReservationManager;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the Hazelcast-backed ReservationManager implementation.
 * Uses embedded Hazelcast for fast, isolated tests.
 */
class HazelcastReservationManagerTest extends AbstractReservationManagerTest {

    private static HazelcastInstance hazelcast;
    private String currentMapName;

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
    protected ReservationManager createManager(String domain, Duration leaseTime) {
        // Use unique map prefix to avoid conflicts between tests
        String mapPrefix = "test-reservations-" + UUID.randomUUID().toString().substring(0, 8);
        currentMapName = mapPrefix + "-" + domain;
        return ReservationManager.hazelcast(hazelcast)
            .domain(domain)
            .leaseTime(leaseTime)
            .mapPrefix(mapPrefix)
            .build();
    }

    @Override
    protected void cleanup() {
        if (currentMapName != null && hazelcast != null) {
            try {
                hazelcast.getMap(currentMapName).destroy();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
    }

    // ==================== Hazelcast-Specific Tests ====================

    @Test
    void shouldStoreDebugValueInMap() {
        var reservation = manager.getReservation("debug-test");
        reservation.lock();

        try {
            String value = hazelcast.getMap(currentMapName).get("debug-test").toString();
            assertThat(value).contains("holder=");
            assertThat(value).contains("acquired=");
        } finally {
            reservation.unlock();
        }
    }

    @Test
    void shouldReturnCorrectMapName() {
        HazelcastReservationManager hzManager = (HazelcastReservationManager) manager;
        assertThat(hzManager.getMapName()).isEqualTo(currentMapName);
    }

    @Test
    void shouldUseDomainBasedMapName() {
        String domain = "test-domain-" + UUID.randomUUID().toString().substring(0, 8);
        ReservationManager domainManager = ReservationManager.hazelcast(hazelcast)
            .domain(domain)
            .build();

        try {
            HazelcastReservationManager hzManager = (HazelcastReservationManager) domainManager;
            assertThat(hzManager.getMapName()).isEqualTo("reservations-" + domain);
            assertThat(hzManager.getDomain()).isEqualTo(domain);
        } finally {
            domainManager.close();
        }
    }

    @Test
    void shouldIsolateBetweenDomains() {
        String domain1 = "domain1-" + UUID.randomUUID().toString().substring(0, 8);
        String domain2 = "domain2-" + UUID.randomUUID().toString().substring(0, 8);

        ReservationManager manager1 = ReservationManager.hazelcast(hazelcast)
            .domain(domain1)
            .build();
        ReservationManager manager2 = ReservationManager.hazelcast(hazelcast)
            .domain(domain2)
            .build();

        try {
            // Lock same identifier in domain1
            var res1 = manager1.getReservation("shared-id");
            res1.lock();

            // Should be able to lock same identifier in domain2
            var res2 = manager2.getReservation("shared-id");
            assertThat(res2.tryLock()).isTrue();
            res2.unlock();

            res1.unlock();
        } finally {
            manager1.close();
            manager2.close();
            hazelcast.getMap("reservations-" + domain1).destroy();
            hazelcast.getMap("reservations-" + domain2).destroy();
        }
    }

    @Test
    void builderShouldRequireDomain() {
        assertThatThrownBy(() ->
            ReservationManager.hazelcast(hazelcast).build()
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("domain");
    }
}

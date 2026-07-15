package com.github.reservation.hazelcast;

import com.github.reservation.AbstractReservationManagerTest;
import com.github.reservation.Reservation;
import com.github.reservation.ReservationAcquisitionException;
import com.github.reservation.ReservationManager;
import com.github.reservation.ReservationReleaseException;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the Hazelcast-backed ReservationManager implementation.
 * Uses embedded Hazelcast for fast, isolated tests.
 */
class HazelcastReservationManagerTest extends AbstractReservationManagerTest {

    private static HazelcastInstance hazelcast;
    private final Set<String> mapNamesToCleanup = ConcurrentHashMap.newKeySet();
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
        mapNamesToCleanup.add(currentMapName);
        return ReservationManager.hazelcast(hazelcast)
            .domain(domain)
            .leaseTime(leaseTime)
            .mapPrefix(mapPrefix)
            .build();
    }

    @Override
    protected void cleanup() {
        if (hazelcast != null) {
            for (String mapName : mapNamesToCleanup) {
                try {
                    hazelcast.getMap(mapName).destroy();
                } catch (Exception e) {
                    // Ignore cleanup errors
                }
            }
            mapNamesToCleanup.clear();
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
        String expectedMapName = "reservations-" + domain;
        mapNamesToCleanup.add(expectedMapName);

        ReservationManager domainManager = ReservationManager.hazelcast(hazelcast)
            .domain(domain)
            .build();

        try {
            HazelcastReservationManager hzManager = (HazelcastReservationManager) domainManager;
            assertThat(hzManager.getMapName()).isEqualTo(expectedMapName);
            assertThat(hzManager.getDomain()).isEqualTo(domain);
        } finally {
            domainManager.close();
        }
    }

    @Test
    void shouldIsolateBetweenDomains() {
        String domain1 = "domain1-" + UUID.randomUUID().toString().substring(0, 8);
        String domain2 = "domain2-" + UUID.randomUUID().toString().substring(0, 8);
        mapNamesToCleanup.add("reservations-" + domain1);
        mapNamesToCleanup.add("reservations-" + domain2);

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
        }
    }

    @Test
    void builderShouldRequireDomain() {
        assertThatThrownBy(() ->
            ReservationManager.hazelcast(hazelcast).build()
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("domain");
    }

    @Test
    void tryLockShouldThrowOnBackendError() throws Exception {
        // Dedicated instance so shutting it down doesn't affect the shared member
        Config config = new Config();
        config.setClusterName("backend-error-" + UUID.randomUUID());
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        HazelcastInstance doomed = Hazelcast.newHazelcastInstance(config);

        ReservationManager doomedManager = ReservationManager.hazelcast(doomed)
            .domain("doomed")
            .build();
        Reservation reservation = doomedManager.getReservation("unreachable");

        doomed.shutdown();

        try {
            assertThatThrownBy(reservation::tryLock)
                .isInstanceOf(ReservationAcquisitionException.class);
            assertThatThrownBy(() -> reservation.tryLock(100, TimeUnit.MILLISECONDS))
                .isInstanceOf(ReservationAcquisitionException.class);
        } finally {
            doomedManager.close();
        }
    }

    @Test
    void unlockShouldThrowReservationReleaseExceptionOnBackendError() {
        // Dedicated instance so shutting it down doesn't affect the shared member
        Config config = new Config();
        config.setClusterName("release-error-" + UUID.randomUUID());
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        HazelcastInstance doomed = Hazelcast.newHazelcastInstance(config);

        ReservationManager doomedManager = ReservationManager.hazelcast(doomed)
            .domain("doomed-release")
            .build();
        Reservation reservation = doomedManager.getReservation("held-then-down");
        reservation.lock();

        doomed.shutdown();

        try {
            assertThatThrownBy(reservation::unlock)
                .isInstanceOf(ReservationReleaseException.class);
        } finally {
            doomedManager.close();
        }
    }

    @Test
    void shouldNotStoreDebugValueWhenDisabled() {
        String mapPrefix = "no-debug-" + UUID.randomUUID().toString().substring(0, 8);
        String mapName = mapPrefix + "-no-debug-domain";
        mapNamesToCleanup.add(mapName);

        ReservationManager plainManager = ReservationManager.hazelcast(hazelcast)
            .domain("no-debug-domain")
            .leaseTime(Duration.ofSeconds(5))
            .mapPrefix(mapPrefix)
            .debugValues(false)
            .build();

        try {
            Reservation reservation = plainManager.getReservation("silent");
            reservation.lock();
            try {
                assertThat(hazelcast.getMap(mapName).get("silent")).isNull();
            } finally {
                reservation.unlock();
            }
        } finally {
            plainManager.close();
        }
    }

    @Test
    void shouldRecordMetricsWhenRegistryConfigured() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        String mapPrefix = "metrics-test-" + UUID.randomUUID().toString().substring(0, 8);
        mapNamesToCleanup.add(mapPrefix + "-metrics-domain");

        ReservationManager metricsManager = ReservationManager.hazelcast(hazelcast)
            .domain("metrics-domain")
            .leaseTime(Duration.ofSeconds(5))
            .mapPrefix(mapPrefix)
            .meterRegistry(registry)
            .build();

        try {
            Reservation reservation = metricsManager.getReservation("metered");
            reservation.lock();
            reservation.unlock();

            assertThat(registry.get("reservation.acquire")
                .tag("domain", "metrics-domain")
                .tag("backend", "hazelcast")
                .tag("result", "acquired")
                .timer().count()).isEqualTo(1);
            assertThat(registry.get("reservation.held.time")
                .tag("domain", "metrics-domain")
                .timer().count()).isEqualTo(1);
        } finally {
            metricsManager.close();
        }
    }
}

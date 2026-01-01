package com.github.reservation.hazelcast;

import com.github.reservation.AbstractStressIntegrationTest;
import com.github.reservation.ReservationManager;
import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Hazelcast stress tests using Testcontainers.
 *
 * <p>Runs all shared stress tests from {@link AbstractStressIntegrationTest}
 * against a real Hazelcast container.</p>
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HazelcastStressIntegrationTest extends AbstractStressIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HazelcastStressIntegrationTest.class);

    @Container
    static GenericContainer<?> hazelcast = new GenericContainer<>(DockerImageName.parse("hazelcast/hazelcast:5.3"))
            .withExposedPorts(5701);

    private HazelcastInstance client;
    private String currentMapName;

    @BeforeAll
    void setupClient() {
        ClientConfig config = new ClientConfig();
        config.setClusterName("dev");
        config.getNetworkConfig().addAddress(
                hazelcast.getHost() + ":" + hazelcast.getMappedPort(5701));
        config.getConnectionStrategyConfig()
                .getConnectionRetryConfig()
                .setClusterConnectTimeoutMillis(30000);

        client = HazelcastClient.newHazelcastClient(config);
        log.info("Connected to Hazelcast container at {}:{}",
                hazelcast.getHost(), hazelcast.getMappedPort(5701));
    }

    @AfterAll
    void teardownClient() {
        if (client != null) {
            client.shutdown();
        }
    }

    @Override
    protected ReservationManager createManager(String domain, Duration leaseTime) {
        currentMapName = "reservations-" + domain;
        return ReservationManager.hazelcast(client)
                .domain(domain)
                .leaseTime(leaseTime)
                .build();
    }

    @Override
    protected void cleanup() {
        if (currentMapName != null && client != null) {
            try {
                client.getMap(currentMapName).destroy();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
    }
}

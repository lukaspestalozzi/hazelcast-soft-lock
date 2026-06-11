package com.github.reservation.hazelcast;

import com.github.reservation.AbstractReservationManagerTest;
import com.github.reservation.ReservationManager;
import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs the full reservation contract against a Hazelcast CLIENT connected to an
 * embedded member. The client-side proxies behave differently from the member-side
 * ones in places (e.g. interrupts surface as HazelcastException wrapping
 * InterruptedException instead of InterruptedException itself), so the contract
 * must hold for both topologies.
 */
class HazelcastClientReservationManagerTest extends AbstractReservationManagerTest {

    private static HazelcastInstance member;
    private static HazelcastInstance client;
    private final Set<String> mapNamesToCleanup = ConcurrentHashMap.newKeySet();

    @BeforeAll
    static void setupHazelcast() {
        String clusterName = "client-test-" + UUID.randomUUID();
        Config config = new Config();
        config.setClusterName(clusterName);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig().setEnabled(false);
        member = Hazelcast.newHazelcastInstance(config);

        // Connect to the member's exact address with a bounded retry: the default
        // config scans fixed localhost ports and retries the cluster connect forever,
        // which silently hangs the whole build if the member is not where expected.
        var memberAddress = member.getCluster().getLocalMember().getAddress();
        ClientConfig clientConfig = new ClientConfig();
        clientConfig.setClusterName(clusterName);
        clientConfig.getNetworkConfig().addAddress(
            memberAddress.getHost() + ":" + memberAddress.getPort());
        clientConfig.getConnectionStrategyConfig()
            .getConnectionRetryConfig()
            .setClusterConnectTimeoutMillis(30_000);
        client = HazelcastClient.newHazelcastClient(clientConfig);
    }

    @AfterAll
    static void teardownHazelcast() {
        if (client != null) {
            client.shutdown();
        }
        if (member != null) {
            member.shutdown();
        }
    }

    @Override
    protected ReservationManager createManager(String domain, Duration leaseTime) {
        // Use unique map prefix to avoid conflicts between tests
        String mapPrefix = "client-test-reservations-" + UUID.randomUUID().toString().substring(0, 8);
        mapNamesToCleanup.add(mapPrefix + "-" + domain);
        return ReservationManager.hazelcast(client)
            .domain(domain)
            .leaseTime(leaseTime)
            .mapPrefix(mapPrefix)
            .build();
    }

    @Override
    protected void cleanup() {
        if (client != null) {
            for (String mapName : mapNamesToCleanup) {
                try {
                    client.getMap(mapName).destroy();
                } catch (Exception e) {
                    // Ignore cleanup errors
                }
            }
            mapNamesToCleanup.clear();
        }
    }

    @Test
    void builderShouldRequireDomain() {
        assertThatThrownBy(() ->
            ReservationManager.hazelcast(client).build()
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("domain");
    }
}

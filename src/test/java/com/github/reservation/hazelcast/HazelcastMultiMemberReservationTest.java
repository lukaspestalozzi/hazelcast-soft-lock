package com.github.reservation.hazelcast;

import com.github.reservation.Reservation;
import com.github.reservation.ReservationManager;
import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies lock visibility across two clustered Hazelcast members: a reservation
 * acquired through one member must be seen (and respected) through the other.
 *
 * <p>All other unit tests run against a single member, which cannot detect a lock
 * that is only effective on the local member.</p>
 */
@Timeout(120)
class HazelcastMultiMemberReservationTest {

    private static HazelcastInstance member1;
    private static HazelcastInstance member2;
    private final Set<String> mapNamesToCleanup = ConcurrentHashMap.newKeySet();

    @BeforeAll
    static void setupCluster() {
        String clusterName = "multi-member-" + UUID.randomUUID();
        member1 = Hazelcast.newHazelcastInstance(clusterConfig(clusterName));
        member2 = Hazelcast.newHazelcastInstance(clusterConfig(clusterName));

        await().atMost(Duration.ofSeconds(30)).until(() ->
            member1.getCluster().getMembers().size() == 2
                && member2.getCluster().getMembers().size() == 2);
    }

    private static Config clusterConfig(String clusterName) {
        Config config = new Config();
        config.setClusterName(clusterName);
        config.getNetworkConfig().getJoin().getMulticastConfig().setEnabled(false);
        config.getNetworkConfig().getJoin().getTcpIpConfig()
            .setEnabled(true)
            .addMember("127.0.0.1");
        return config;
    }

    @AfterAll
    static void teardownCluster() {
        if (member2 != null) {
            member2.shutdown();
        }
        if (member1 != null) {
            member1.shutdown();
        }
    }

    @AfterEach
    void cleanup() {
        for (String mapName : mapNamesToCleanup) {
            try {
                member1.getMap(mapName).destroy();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
        mapNamesToCleanup.clear();
    }

    private ReservationManager managerOn(HazelcastInstance member, String mapPrefix, String domain) {
        mapNamesToCleanup.add(mapPrefix + "-" + domain);
        return ReservationManager.hazelcast(member)
            .domain(domain)
            .leaseTime(Duration.ofSeconds(10))
            .mapPrefix(mapPrefix)
            .build();
    }

    @Test
    void lockAcquiredOnOneMemberMustBlockAcquisitionViaTheOther() {
        String mapPrefix = "multi-" + UUID.randomUUID().toString().substring(0, 8);
        ReservationManager viaMember1 = managerOn(member1, mapPrefix, "orders");
        ReservationManager viaMember2 = managerOn(member2, mapPrefix, "orders");

        try {
            Reservation held = viaMember1.getReservation("shared-id");
            held.lock();
            try {
                Reservation contender = viaMember2.getReservation("shared-id");
                assertThat(contender.isLocked())
                    .as("lock must be visible through the other member")
                    .isTrue();
                assertThat(contender.tryLock())
                    .as("lock held via member1 must not be acquirable via member2")
                    .isFalse();
            } finally {
                held.unlock();
            }

            // After release, the other member can acquire
            Reservation afterRelease = viaMember2.getReservation("shared-id");
            assertThat(afterRelease.tryLock()).isTrue();
            afterRelease.unlock();
        } finally {
            viaMember1.close();
            viaMember2.close();
        }
    }

    @Test
    void domainsMustStayIsolatedAcrossMembers() {
        String mapPrefix = "multi-iso-" + UUID.randomUUID().toString().substring(0, 8);
        ReservationManager ordersViaMember1 = managerOn(member1, mapPrefix, "orders");
        ReservationManager usersViaMember2 = managerOn(member2, mapPrefix, "users");

        try {
            Reservation orders = ordersViaMember1.getReservation("shared-id");
            orders.lock();
            try {
                Reservation users = usersViaMember2.getReservation("shared-id");
                assertThat(users.tryLock())
                    .as("same identifier in a different domain must be independent")
                    .isTrue();
                users.unlock();
            } finally {
                orders.unlock();
            }
        } finally {
            ordersViaMember1.close();
            usersViaMember2.close();
        }
    }
}

package com.autotest.runner;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PinnedDnsCacheManagerTest {

    @Test
    void neverFallsBackToSystemDnsForAnUnpinnedHost() {
        PinnedDnsCacheManager manager = new PinnedDnsCacheManager();

        assertThrows(UnknownHostException.class, () -> manager.resolve("localhost"));
    }

    @Test
    void returnsOnlyTheDeterministicallyPinnedAddressSet() throws Exception {
        PinnedDnsCacheManager manager = new PinnedDnsCacheManager();
        InetAddress first = InetAddress.getByName("192.0.2.10");
        InetAddress second = InetAddress.getByName("198.51.100.20");

        manager.pin("api.example.test", 443, List.of(second, first, first), Instant.now().plusSeconds(30));
        manager.activate("api.example.test", 443);

        // All DNS answers are policy-checked before pinning, but the socket
        // resolver returns one deterministic address so the connection cannot
        // perform a later/default DNS lookup.
        assertArrayEquals(new InetAddress[]{first}, manager.resolve("api.example.test"));
        assertThrows(UnknownHostException.class, () -> manager.activate("api.example.test", 80));
    }

    @Test
    void rejectsExpiredPinsAndClearsTheRunLocalState() throws Exception {
        PinnedDnsCacheManager manager = new PinnedDnsCacheManager();
        InetAddress address = InetAddress.getByName("192.0.2.10");
        assertThrows(IllegalArgumentException.class,
                () -> manager.pin("api.example.test", 443, List.of(address), Instant.now().minusSeconds(1)));

        manager.pin("api.example.test", 443, List.of(address), Instant.now().plusSeconds(30));
        manager.activate("api.example.test", 443);
        manager.clearPins();
        assertThrows(UnknownHostException.class, () -> manager.resolve("api.example.test"));
    }
}

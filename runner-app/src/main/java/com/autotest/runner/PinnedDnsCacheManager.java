package com.autotest.runner;

import org.apache.jmeter.protocol.http.control.DNSCacheManager;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Run-local JMeter DNS resolver. It deliberately never delegates to the
 * JMeter/system resolver: an HTTP connection is possible only after the
 * current sampler has pinned a policy-approved address set.
 */
public final class PinnedDnsCacheManager extends DNSCacheManager {
    private final Map<String, Pin> pins = new HashMap<>();
    private final Map<String, String> activeKeys = new HashMap<>();

    public synchronized void pin(String host, int port, List<InetAddress> addresses, Instant expiresAt) {
        String canonicalHost = canonicalHost(host);
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口不合法");
        if (expiresAt == null || !expiresAt.isAfter(Instant.now())) throw new IllegalArgumentException("DNS pin 已过期");
        if (addresses == null || addresses.isEmpty()) throw new IllegalArgumentException("DNS pin 地址为空");
        Map<Bytes, InetAddress> unique = new LinkedHashMap<>();
        addresses.stream().filter(Objects::nonNull).sorted(addressComparator())
                .forEach(address -> unique.putIfAbsent(new Bytes(address.getAddress()), address));
        if (unique.isEmpty()) throw new IllegalArgumentException("DNS pin 地址为空");
        pins.put(key(canonicalHost, port), new Pin(canonicalHost, port, List.copyOf(unique.values()), expiresAt));
    }

    public synchronized void activate(String host, int port) throws UnknownHostException {
        String canonicalHost = canonicalHost(host);
        String activeKey = key(canonicalHost, port);
        Pin pin = pins.get(activeKey);
        if (pin == null || pin.port() != port || !pin.expiresAt().isAfter(Instant.now())) {
            throw unknown(canonicalHost);
        }
        activeKeys.put(canonicalHost, activeKey);
    }

    @Override
    public synchronized InetAddress[] resolve(String host) throws UnknownHostException {
        String canonicalHost = canonicalHost(host);
        String activeKey = activeKeys.get(canonicalHost);
        Pin pin = activeKey == null ? null : pins.get(activeKey);
        if (pin == null || !pin.expiresAt().isAfter(Instant.now())) throw unknown(canonicalHost);
        // The complete answer set was policy-checked when pin() was called;
        // return one deterministic address so HC4 cannot perform a later
        // fallback resolution or choose an unrecorded address.
        return new InetAddress[]{pin.addresses().get(0)};
    }

    public synchronized InetAddress selectedAddress(String host) throws UnknownHostException {
        InetAddress[] resolved = resolve(host);
        return resolved[0];
    }

    public synchronized void clearPins() {
        pins.clear();
        activeKeys.clear();
        clear();
    }

    private static String key(String host, int port) { return host + "|" + port; }

    private static Comparator<InetAddress> addressComparator() {
        return (left, right) -> Arrays.compareUnsigned(left.getAddress(), right.getAddress());
    }

    private static String canonicalHost(String host) {
        String value = host == null ? "" : host.strip().toLowerCase(java.util.Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        if (value.isBlank() || value.contains("%") || value.contains("/") || value.contains("@")) {
            throw new IllegalArgumentException("host 不合法");
        }
        return value;
    }

    private static UnknownHostException unknown(String host) {
        return new UnknownHostException("未 pin 的目标 host: " + host);
    }

    private record Pin(String host, int port, List<InetAddress> addresses, Instant expiresAt) {}

    private static final class Bytes {
        private final byte[] bytes;

        private Bytes(byte[] bytes) { this.bytes = bytes.clone(); }

        @Override public boolean equals(Object other) { return other instanceof Bytes value && Arrays.equals(bytes, value.bytes); }
        @Override public int hashCode() { return Arrays.hashCode(bytes); }
    }
}

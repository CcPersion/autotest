package com.autotest.runner;

import com.autotest.contracts.network.TargetAllowlist;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 在 HTTP 客户端连接前解析并审核目标地址。每个 A/AAAA 地址均必须通过
 * 同一策略，结果按地址字节稳定去重排序，用于后续 socket pinning。
 */
public final class ApprovedDnsResolver {
    @FunctionalInterface
    public interface AddressLookup {
        InetAddress[] lookup(String host) throws UnknownHostException;
    }

    public record Resolution(String host, int port, List<InetAddress> addresses) {
        public Resolution {
            host = Objects.requireNonNull(host, "host").toLowerCase(Locale.ROOT);
            addresses = List.copyOf(addresses);
            if (addresses.isEmpty()) throw new IllegalArgumentException("DNS 解析结果为空");
        }
    }

    public static final class DnsPolicyException extends IllegalArgumentException {
        public DnsPolicyException(String message) { super(message); }
        public DnsPolicyException(String message, Throwable cause) { super(message, cause); }
    }

    private final AddressLookup lookup;

    public ApprovedDnsResolver() { this(new LazyDirectDnsLookup()); }

    public ApprovedDnsResolver(AddressLookup lookup) { this.lookup = Objects.requireNonNull(lookup, "lookup"); }

    /** Package-private hook for the wire-level resolver regression test. */
    static AddressLookup directLookup(InetAddress nameServer) {
        return directLookup(nameServer, 53);
    }

    static AddressLookup directLookup(InetAddress nameServer, int port) {
        return new DirectDnsLookup(Objects.requireNonNull(nameServer, "nameServer"), port);
    }

    public Resolution resolve(URI target, Collection<String> rules) {
        if (target == null || target.getHost() == null || target.getHost().isBlank()) throw new DnsPolicyException("目标主机为空");
        TargetAllowlist policy;
        try { policy = TargetAllowlist.parse(normalizeRules(rules)); }
        catch (IllegalArgumentException exception) { throw new DnsPolicyException("目标 DNS 策略不合法", exception); }
        TargetAllowlist.Decision hostDecision = policy.evaluate(target);
        if (!hostDecision.allowed()) throw new DnsPolicyException("目标主机未加入已批准策略");
        int port;
        try { port = TargetAllowlist.effectivePort(target); }
        catch (IllegalArgumentException exception) { throw new DnsPolicyException("目标端口不合法", exception); }

        InetAddress[] raw;
        if (isAddressLiteral(target.getHost())) {
            raw = new InetAddress[]{parseLiteral(target.getHost())};
        } else {
            try { raw = lookup.lookup(target.getHost()); }
            catch (UnknownHostException exception) { throw new DnsPolicyException("目标 DNS 解析失败", exception); }
        }
        List<InetAddress> addresses = stableUnique(raw);
        if (addresses.isEmpty()) throw new DnsPolicyException("目标 DNS 解析结果为空");
        boolean requireAddressApproval = policy.hasAddressRules();
        for (InetAddress address : addresses) {
            if ((requireAddressApproval || isRestricted(address)) && !policy.matchesAddress(target, address)) {
                throw new DnsPolicyException("目标 DNS 地址未获准");
            }
        }
        return new Resolution(canonicalHost(target.getHost()), port, addresses);
    }

    public void verifyStable(URI target, Collection<String> rules, Resolution approved) {
        if (approved == null) throw new DnsPolicyException("缺少 DNS 解析快照");
        Resolution current = resolve(target, rules);
        verifyStable(current, approved);
    }

    /**
     * Compares two already-resolved snapshots without performing another DNS
     * lookup.  The caller must pin the {@code current} instance only after
     * this comparison succeeds; this prevents an A→B→A lookup sequence from
     * approving A while the socket is pinned to B.
     */
    public void verifyStable(Resolution current, Resolution approved) {
        if (approved == null || current == null) throw new DnsPolicyException("缺少 DNS 解析快照");
        if (!approved.host().equalsIgnoreCase(current.host()) || !sameAddresses(approved, current)) {
            throw new DnsPolicyException("目标 DNS 地址发生变化，已拒绝请求");
        }
    }

    private static List<String> normalizeRules(Collection<String> rules) {
        if (rules == null) return List.of();
        return rules.stream().filter(Objects::nonNull).map(String::strip).filter(value -> !value.isBlank()).toList();
    }

    private static boolean isRestricted(InetAddress address) {
        return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || (address instanceof Inet6Address ipv6 && ipv6.isLinkLocalAddress());
    }

    private static boolean isAddressLiteral(String host) {
        String value = host;
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        if (value.contains(":")) return value.matches("[0-9a-fA-F:]+") && value.contains(":");
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isBlank() || !part.chars().allMatch(Character::isDigit)) return false;
            try { if (Integer.parseInt(part) > 255) return false; }
            catch (NumberFormatException exception) { return false; }
        }
        return true;
    }

    private static InetAddress parseLiteral(String host) {
        String value = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        try { return InetAddress.getByName(value); }
        catch (UnknownHostException exception) { throw new DnsPolicyException("IP 地址不合法", exception); }
    }

    private static String canonicalHost(String host) {
        String value = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        return value;
    }

    private static List<InetAddress> stableUnique(InetAddress[] raw) {
        if (raw == null) return List.of();
        List<InetAddress> sorted = new ArrayList<>();
        for (InetAddress address : raw) {
            if (address != null && sorted.stream().noneMatch(existing -> Arrays.equals(existing.getAddress(), address.getAddress()))) sorted.add(address);
        }
        sorted.sort((left, right) -> Arrays.compareUnsigned(left.getAddress(), right.getAddress()));
        return List.copyOf(sorted);
    }

    private static boolean sameAddresses(Resolution left, Resolution right) {
        if (left.port() != right.port() || left.addresses().size() != right.addresses().size()) return false;
        for (int index = 0; index < left.addresses().size(); index++) {
            if (!Arrays.equals(left.addresses().get(index).getAddress(), right.addresses().get(index).getAddress())) return false;
        }
        return true;
    }

    private static InetAddress resolveNameServer() {
        String configured = System.getenv("AUTOTEST_DNS_SERVER");
        String value = configured == null ? "" : configured.strip();
        if (value.isBlank()) {
            try {
                value = Files.readAllLines(Path.of("/etc/resolv.conf"), StandardCharsets.UTF_8).stream()
                        .map(String::strip)
                        .filter(line -> line.startsWith("nameserver "))
                        .map(line -> line.substring("nameserver ".length()).strip())
                        .filter(line -> !line.isBlank())
                        .findFirst()
                        .orElse("");
            } catch (IOException ignored) {
                value = "";
            }
        }
        if (value.isBlank()) throw new IllegalStateException("未配置受控 DNS nameserver");
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException exception) {
            throw new IllegalStateException("受控 DNS nameserver 不合法", exception);
        }
    }

    /**
     * 每次调用都直接向受控 nameserver 发出 A 与 AAAA 查询，绝不调用
     * InetAddress.getAllByName，也不使用 JVM/JMeter DNS 缓存。
     */
    private static final class DirectDnsLookup implements AddressLookup {
        private static final int TIMEOUT_MILLIS = 1500;
        private final InetAddress nameServer;
        private final int port;

        private DirectDnsLookup(InetAddress nameServer, int port) {
            if (port < 1 || port > 65535) throw new IllegalArgumentException("DNS port 不合法");
            this.nameServer = nameServer;
            this.port = port;
        }

        @Override
        public InetAddress[] lookup(String host) throws UnknownHostException {
            List<InetAddress> result = new ArrayList<>();
            query(host, 1, result);
            query(host, 28, result);
            return result.toArray(InetAddress[]::new);
        }

        private void query(String host, int qtype, List<InetAddress> result) throws UnknownHostException {
            int id = (int) (System.nanoTime() & 0xffff);
            byte[] request;
            try {
                request = queryPacket(host, qtype, id);
            } catch (IOException exception) {
                throw unknown(host, exception);
            }
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(TIMEOUT_MILLIS);
                socket.connect(new InetSocketAddress(nameServer, port));
                socket.send(new DatagramPacket(request, request.length));
                byte[] buffer = new byte[4096];
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                socket.receive(response);
                if (!nameServer.equals(response.getAddress()) || response.getPort() != port) {
                    throw new IOException("DNS 响应来源不匹配");
                }
                parseResponse(host, qtype, id, buffer, response.getLength(), result);
            } catch (IOException exception) {
                throw unknown(host, exception);
            }
        }

        private static byte[] queryPacket(String host, int qtype, int id) throws IOException {
            String normalized = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
            if (normalized.isBlank()) throw new IOException("DNS host 为空");
            byte[] encodedName = encodeName(normalized);
            byte[] packet = new byte[12 + encodedName.length + 4];
            packet[0] = (byte) (id >>> 8);
            packet[1] = (byte) id;
            packet[2] = 1; // RD: allow the configured fixture/upstream to recurse.
            packet[5] = 1; // one question
            System.arraycopy(encodedName, 0, packet, 12, encodedName.length);
            int offset = 12 + encodedName.length;
            packet[offset] = (byte) (qtype >>> 8);
            packet[offset + 1] = (byte) qtype;
            packet[offset + 3] = 1; // IN
            return packet;
        }

        private static byte[] encodeName(String host) throws IOException {
            String[] labels = host.split("\\.", -1);
            int length = 1;
            for (String label : labels) {
                byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
                if (bytes.length == 0 || bytes.length > 63) throw new IOException("DNS label 不合法");
                length += bytes.length + 1;
            }
            if (length > 255) throw new IOException("DNS name 过长");
            byte[] result = new byte[length];
            int offset = 0;
            for (String label : labels) {
                byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
                result[offset++] = (byte) bytes.length;
                System.arraycopy(bytes, 0, result, offset, bytes.length);
                offset += bytes.length;
            }
            result[offset] = 0;
            return result;
        }

        private static void parseResponse(String host, int qtype, int id, byte[] packet,
                                          int length, List<InetAddress> result) throws IOException {
            if (length < 12 || readU16(packet, 0) != id) throw new IOException("DNS response 不匹配");
            int flags = readU16(packet, 2);
            if ((flags & 0x8000) == 0 || (flags & 0x000f) != 0) {
                throw new UnknownHostException(host);
            }
            int questions = readU16(packet, 4);
            int answers = readU16(packet, 6);
            if (questions != 1) throw new IOException("DNS question 数量不匹配");
            int offset = 12;
            NameRead question = readName(packet, offset, length);
            offset = question.nextOffset();
            if (offset + 4 > length) throw new IOException("DNS question 不完整");
            int questionType = readU16(packet, offset);
            int questionClass = readU16(packet, offset + 2);
            if (!normalizeDnsName(host).equals(normalizeDnsName(question.name()))
                    || questionType != qtype || questionClass != 1) {
                throw new IOException("DNS question 不匹配");
            }
            offset += 4;
            for (int index = 0; index < answers; index++) {
                offset = skipName(packet, offset, length);
                if (offset + 10 > length) throw new IOException("DNS answer 不完整");
                int recordType = readU16(packet, offset);
                int recordClass = readU16(packet, offset + 2);
                int dataLength = readU16(packet, offset + 8);
                offset += 10;
                if (offset + dataLength > length) throw new IOException("DNS answer 长度不合法");
                if (recordClass == 1 && recordType == qtype
                        && ((qtype == 1 && dataLength == 4) || (qtype == 28 && dataLength == 16))) {
                    result.add(InetAddress.getByAddress(host, java.util.Arrays.copyOfRange(packet, offset, offset + dataLength)));
                }
                offset += dataLength;
            }
        }

        private static int skipName(byte[] packet, int offset, int length) throws IOException {
            int cursor = offset;
            while (cursor < length) {
                int labelLength = packet[cursor] & 0xff;
                if (labelLength == 0) return cursor + 1;
                if ((labelLength & 0xc0) == 0xc0) {
                    if (cursor + 1 >= length) throw new IOException("DNS name 指针不完整");
                    return cursor + 2;
                }
                if (labelLength > 63 || cursor + 1 + labelLength > length) {
                    throw new IOException("DNS name 不合法");
                }
                cursor += 1 + labelLength;
            }
            throw new IOException("DNS name 超出响应长度");
        }

        private static NameRead readName(byte[] packet, int offset, int length) throws IOException {
            StringBuilder name = new StringBuilder();
            HashSet<Integer> visited = new HashSet<>();
            int cursor = offset;
            int nextOffset = -1;
            while (cursor < length) {
                if (!visited.add(cursor)) throw new IOException("DNS name 指针循环");
                int labelLength = packet[cursor] & 0xff;
                if (labelLength == 0) {
                    if (nextOffset < 0) nextOffset = cursor + 1;
                    return new NameRead(name.toString(), nextOffset);
                }
                if ((labelLength & 0xc0) == 0xc0) {
                    if (cursor + 1 >= length) throw new IOException("DNS name 指针不完整");
                    int pointer = ((labelLength & 0x3f) << 8) | (packet[cursor + 1] & 0xff);
                    if (pointer >= length) throw new IOException("DNS name 指针越界");
                    if (nextOffset < 0) nextOffset = cursor + 2;
                    cursor = pointer;
                    continue;
                }
                if (labelLength > 63 || cursor + 1 + labelLength > length) {
                    throw new IOException("DNS name 不合法");
                }
                if (name.length() > 0) name.append('.');
                name.append(new String(packet, cursor + 1, labelLength, StandardCharsets.US_ASCII));
                cursor += 1 + labelLength;
            }
            throw new IOException("DNS name 超出响应长度");
        }

        private static String normalizeDnsName(String value) {
            String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
            while (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
            return normalized;
        }

        private record NameRead(String name, int nextOffset) {}

        private static int readU16(byte[] packet, int offset) {
            return ((packet[offset] & 0xff) << 8) | (packet[offset + 1] & 0xff);
        }

        private static UnknownHostException unknown(String host, Exception cause) {
            UnknownHostException exception = new UnknownHostException("DNS 解析失败: " + host);
            exception.initCause(cause);
            return exception;
        }
    }

    private static final class LazyDirectDnsLookup implements AddressLookup {
        private volatile DirectDnsLookup delegate;

        @Override
        public InetAddress[] lookup(String host) throws UnknownHostException {
            DirectDnsLookup current = delegate;
            if (current == null) {
                synchronized (this) {
                    current = delegate;
                    if (current == null) {
                        current = new DirectDnsLookup(resolveNameServer(), 53);
                        delegate = current;
                    }
                }
            }
            return current.lookup(host);
        }
    }
}

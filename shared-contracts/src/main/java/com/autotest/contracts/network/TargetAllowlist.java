package com.autotest.contracts.network;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 不执行网络访问的目标白名单值对象。
 *
 * <p>规则只允许 hostname、单层 wildcard、IP literal 和 CIDR，并可带端口。
 * 运行时的 DNS 解析和连接固定由 Runner 负责。</p>
 */
public final class TargetAllowlist {
    public static final int MAX_RULES = 100;

    private final List<String> rules;
    private final List<Rule> parsedRules;

    private TargetAllowlist(List<Rule> parsedRules) {
        this.parsedRules = List.copyOf(parsedRules);
        this.rules = parsedRules.stream().map(Rule::normalized).toList();
    }

    public static TargetAllowlist parse(Collection<String> values) {
        if (values == null || values.isEmpty()) return new TargetAllowlist(List.of());
        if (values.size() > MAX_RULES) throw new IllegalArgumentException("目标白名单最多包含 " + MAX_RULES + " 条规则");
        Map<String, Rule> normalized = new LinkedHashMap<>();
        for (String raw : values) {
            Rule rule = parseRule(raw);
            normalized.putIfAbsent(rule.normalized(), rule);
        }
        return new TargetAllowlist(new ArrayList<>(normalized.values()));
    }

    public List<String> rules() { return rules; }

    /** Whether the policy contains an IP literal or CIDR rule. */
    public boolean hasAddressRules() {
        return parsedRules.stream().anyMatch(rule -> rule.kind != Kind.HOSTNAME);
    }

    public Decision evaluate(URI target) {
        if (target == null) return Decision.reject("目标 URL 为空", null);
        String scheme = target.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return Decision.reject("只允许 HTTP 或 HTTPS", target.getHost());
        }
        if (target.getHost() == null || target.getHost().isBlank()
                || target.getUserInfo() != null || target.getFragment() != null) {
            return Decision.reject("目标 URL 的主机、用户信息或 Fragment 不合法", target.getHost());
        }
        String host;
        try { host = normalizeHost(target.getHost()); }
        catch (IllegalArgumentException exception) { return Decision.reject("目标主机不合法", target.getHost()); }
        int port;
        try { port = effectivePort(target); }
        catch (IllegalArgumentException exception) { return Decision.reject("目标端口不合法", host); }
        for (Rule rule : parsedRules) {
            if (rule.portMatches(port, defaultPort(scheme)) && rule.matchesHost(host)) return Decision.allow(host);
        }
        return Decision.reject("目标主机未加入项目白名单", host);
    }

    /** 用于 DNS 结果的地址/CIDR 检查，不会触发 DNS 查询。 */
    public boolean matchesAddress(URI target, InetAddress address) {
        if (target == null || address == null || target.getScheme() == null) return false;
        int port;
        try { port = effectivePort(target); }
        catch (IllegalArgumentException exception) { return false; }
        int defaultPort = defaultPort(target.getScheme());
        return parsedRules.stream().filter(rule -> rule.portMatches(port, defaultPort))
                .anyMatch(rule -> rule.matchesAddress(address));
    }

    public static int effectivePort(URI target) {
        Objects.requireNonNull(target, "target");
        if (target.getPort() >= 0) { validatePort(target.getPort()); return target.getPort(); }
        return defaultPort(target.getScheme());
    }

    public static int defaultPort(String scheme) {
        if ("http".equalsIgnoreCase(scheme)) return 80;
        if ("https".equalsIgnoreCase(scheme)) return 443;
        throw new IllegalArgumentException("只允许 HTTP 或 HTTPS");
    }

    private static Rule parseRule(String raw) {
        String value = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (value.isBlank() || value.length() > 253 || value.contains("://") || value.contains("?")
                || value.contains("#") || value.contains("@") || value.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new IllegalArgumentException("目标白名单规则不合法");
        }
        boolean wildcard = value.startsWith("*.");
        if (value.contains("*") && !wildcard) throw new IllegalArgumentException("目标白名单只允许单层前缀通配符");
        String withoutWildcard = wildcard ? value.substring(2) : value;
        HostAndPort hostAndPort = splitHostAndPort(withoutWildcard);
        if (hostAndPort.host().isBlank()) throw new IllegalArgumentException("目标白名单主机不能为空");
        if (wildcard && (hostAndPort.host().contains(":") || hostAndPort.host().contains("/"))) {
            throw new IllegalArgumentException("通配符只能用于 hostname");
        }
        Integer port = hostAndPort.port();
        String host = hostAndPort.host();
        if (host.contains("/")) {
            if (wildcard) throw new IllegalArgumentException("CIDR 不支持通配符");
            Cidr cidr = parseCidr(host);
            return Rule.cidr(cidr.normalized(), port, cidr.network(), cidr.prefixLength());
        }
        if (looksLikeAddress(host)) {
            if (wildcard) throw new IllegalArgumentException("IP 地址不支持通配符");
            InetAddress address = parseLiteralAddress(host);
            return Rule.address(formatAddress(address), port, address.getAddress());
        }
        String normalizedHost = normalizeHost(host);
        if (wildcard && normalizedHost.indexOf('.') < 0) throw new IllegalArgumentException("通配符后缀必须是合法 hostname");
        return Rule.hostname((wildcard ? "*." : "") + normalizedHost, port, wildcard);
    }

    private static HostAndPort splitHostAndPort(String value) {
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close <= 1) throw new IllegalArgumentException("IPv6 地址不合法");
            String host = value.substring(1, close);
            String suffix = value.substring(close + 1);
            if (suffix.isEmpty()) return new HostAndPort(host, null);
            if (!suffix.startsWith(":")) throw new IllegalArgumentException("目标端口不合法");
            return new HostAndPort(host, parsePort(suffix.substring(1)));
        }
        int colonCount = (int) value.chars().filter(c -> c == ':').count();
        if (colonCount == 1) {
            int colon = value.lastIndexOf(':');
            String host = value.substring(0, colon);
            String port = value.substring(colon + 1);
            if (host.isBlank() || port.isBlank()) throw new IllegalArgumentException("目标端口不合法");
            return new HostAndPort(host, parsePort(port));
        }
        if (colonCount > 1 && value.contains("/") && value.lastIndexOf(':') > value.indexOf('/')) {
            int colon = value.lastIndexOf(':');
            return new HostAndPort(value.substring(0, colon), parsePort(value.substring(colon + 1)));
        }
        return new HostAndPort(value, null);
    }

    private static int parsePort(String value) {
        try { int port = Integer.parseInt(value); validatePort(port); return port; }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("目标端口不合法", exception); }
    }

    private static void validatePort(int port) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("目标端口不合法");
    }

    private static String normalizeHost(String host) {
        String value = host.strip().toLowerCase(Locale.ROOT);
        if (value.endsWith(".") && !value.equals(".")) value = value.substring(0, value.length() - 1);
        if (value.startsWith("[") || value.endsWith("]")) {
            if (!(value.startsWith("[") && value.endsWith("]"))) throw new IllegalArgumentException("目标主机不合法");
            value = value.substring(1, value.length() - 1);
        }
        if (value.isBlank() || value.contains("/") || value.contains("@") || value.contains("%")) throw new IllegalArgumentException("目标主机不合法");
        if (looksLikeAddress(value)) return formatAddress(parseLiteralAddress(value));
        try {
            String ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES);
            if (ascii.isBlank() || ascii.length() > 253 || ascii.startsWith(".") || ascii.endsWith(".")) throw new IllegalArgumentException("目标主机不合法");
            for (String label : ascii.split("\\.", -1)) {
                if (label.isBlank() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) throw new IllegalArgumentException("目标主机不合法");
            }
            return ascii.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException exception) { throw new IllegalArgumentException("目标主机不合法", exception); }
    }

    private static boolean looksLikeAddress(String host) {
        if (host.indexOf(':') >= 0) return true;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isBlank() || !part.chars().allMatch(Character::isDigit)) return false;
            try { if (Integer.parseInt(part) > 255) return false; }
            catch (NumberFormatException exception) { return false; }
        }
        return true;
    }

    private static InetAddress parseLiteralAddress(String host) {
        if (host.contains("%")) throw new IllegalArgumentException("地址 zone 不允许");
        try {
            InetAddress address = InetAddress.getByName(host);
            if (host.indexOf(':') >= 0 && !address.getHostAddress().contains(":")) throw new IllegalArgumentException("IPv6 地址不合法");
            return address;
        } catch (UnknownHostException exception) { throw new IllegalArgumentException("IP 地址不合法", exception); }
    }

    private static String formatAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        String value = bytes.length == 16 ? formatIpv6(bytes) : address.getHostAddress().toLowerCase(Locale.ROOT);
        return value.contains(":") ? "[" + value + "]" : value;
    }

    private static String formatIpv6(byte[] bytes) {
        int bestStart = -1;
        int bestLength = 1;
        for (int start = 0; start < 8; start++) {
            if (((bytes[start * 2] & 0xff) << 8 | (bytes[start * 2 + 1] & 0xff)) != 0) continue;
            int end = start;
            while (end < 8 && ((bytes[end * 2] & 0xff) << 8 | (bytes[end * 2 + 1] & 0xff)) == 0) end++;
            if (end - start > bestLength) {
                bestStart = start;
                bestLength = end - start;
            }
            start = end - 1;
        }
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < 8;) {
            if (index == bestStart) {
                result.append("::");
                index += bestLength;
                continue;
            }
            if (result.length() > 0 && result.charAt(result.length() - 1) != ':') result.append(':');
            int group = (bytes[index * 2] & 0xff) << 8 | (bytes[index * 2 + 1] & 0xff);
            result.append(Integer.toHexString(group));
            index++;
        }
        return result.toString();
    }

    private static Cidr parseCidr(String value) {
        int slash = value.indexOf('/');
        if (slash <= 0 || slash != value.lastIndexOf('/')) throw new IllegalArgumentException("CIDR 不合法");
        InetAddress network = parseLiteralAddress(value.substring(0, slash));
        int prefix;
        try { prefix = Integer.parseInt(value.substring(slash + 1)); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("CIDR 前缀不合法", exception); }
        byte[] bytes = network.getAddress();
        if (prefix < 0 || prefix > bytes.length * 8) throw new IllegalArgumentException("CIDR 前缀不合法");
        byte[] normalizedBytes = bytes.clone();
        int full = prefix / 8;
        int remainder = prefix % 8;
        if (remainder != 0) normalizedBytes[full] &= (byte) (0xff << (8 - remainder));
        for (int index = full + (remainder == 0 ? 0 : 1); index < normalizedBytes.length; index++) normalizedBytes[index] = 0;
        InetAddress base;
        try { base = InetAddress.getByAddress(normalizedBytes); }
        catch (UnknownHostException exception) { throw new IllegalArgumentException("CIDR 网络地址不合法", exception); }
        String address = formatAddress(base);
        // Keep IPv6 CIDR and its optional port inside one bracketed host
        // token: [2001:db8:0:0:0:0:0:0/32]:443.  This is the same syntax
        // accepted by splitHostAndPort, so a persisted rules() snapshot can
        // be parsed again without treating the CIDR suffix as a port.
        String normalizedCidr = address.contains(":")
                ? address.substring(0, address.length() - 1) + "/" + prefix + "]"
                : address + "/" + prefix;
        return new Cidr(normalizedCidr, normalizedBytes, prefix);
    }

    private record HostAndPort(String host, Integer port) {}
    private record Cidr(String normalized, byte[] network, int prefixLength) {}
    private enum Kind { HOSTNAME, ADDRESS, CIDR }

    private static final class Rule {
        private final String normalized;
        private final String host;
        private final Integer port;
        private final boolean wildcard;
        private final Kind kind;
        private final byte[] address;
        private final int prefixLength;

        private Rule(String normalized, String host, Integer port, boolean wildcard, Kind kind, byte[] address, int prefixLength) {
            this.normalized = normalized; this.host = host; this.port = port; this.wildcard = wildcard; this.kind = kind;
            this.address = address == null ? null : address.clone(); this.prefixLength = prefixLength;
        }
        static Rule hostname(String host, Integer port, boolean wildcard) { return new Rule(host + formatPort(port), host, port, wildcard, Kind.HOSTNAME, null, -1); }
        static Rule address(String host, Integer port, byte[] address) { return new Rule(host + formatPort(port), host, port, false, Kind.ADDRESS, address, address.length * 8); }
        static Rule cidr(String host, Integer port, byte[] network, int prefixLength) { return new Rule(host + formatPort(port), host, port, false, Kind.CIDR, network, prefixLength); }
        String normalized() { return normalized; }
        boolean portMatches(int targetPort, int defaultPort) { return port == null ? targetPort == defaultPort : port == targetPort; }
        boolean matchesHost(String targetHost) {
            if (kind == Kind.HOSTNAME) {
                if (!wildcard) return host.equals(targetHost);
                String suffix = host.substring(1); // .example.com
                if (!targetHost.endsWith(suffix) || targetHost.length() <= suffix.length()) return false;
                String prefix = targetHost.substring(0, targetHost.length() - suffix.length());
                return prefix.length() > 0 && prefix.indexOf('.') < 0;
            }
            if (!looksLikeAddress(targetHost)) return false;
            return matchesAddress(parseLiteralAddress(targetHost));
        }
        boolean matchesAddress(InetAddress candidate) {
            if (kind == Kind.HOSTNAME) return false;
            byte[] candidateBytes = candidate.getAddress();
            if (candidateBytes.length != address.length) return false;
            int fullBytes = prefixLength / 8;
            int remaining = prefixLength % 8;
            for (int index = 0; index < fullBytes; index++) if (candidateBytes[index] != address[index]) return false;
            if (remaining == 0) return true;
            int mask = 0xff << (8 - remaining);
            return (candidateBytes[fullBytes] & mask) == (address[fullBytes] & mask);
        }
        private static String formatPort(Integer port) { return port == null ? "" : ":" + port; }
    }

    public record Decision(boolean allowed, String reason, String host) {
        private static Decision allow(String host) { return new Decision(true, "允许", host); }
        private static Decision reject(String reason, String host) { return new Decision(false, reason, host); }
    }
}

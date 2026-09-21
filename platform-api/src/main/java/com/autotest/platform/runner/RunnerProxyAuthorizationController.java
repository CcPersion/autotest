package com.autotest.platform.runner;

import com.autotest.contracts.network.TargetAllowlist;
import com.autotest.platform.run.RunRecord;
import com.autotest.platform.run.RunRepository;
import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Issues short-lived proxy capabilities from the persisted run policy snapshot. */
@RestController
public final class RunnerProxyAuthorizationController {
    private static final long CAPABILITY_SECONDS = 30;

    @FunctionalInterface
    interface AddressLookup {
        InetAddress[] lookup(String host) throws UnknownHostException;
    }

    private final RunRepository runs;
    private final ObjectMapper json;
    private final String callbackToken;
    private final AddressLookup addressLookup;

    @Autowired
    public RunnerProxyAuthorizationController(RunRepository runs, ObjectMapper json,
                                               @Value("${autotest.runner.callback-token:}") String callbackToken) {
        this(runs, json, callbackToken, InetAddress::getAllByName);
    }

    RunnerProxyAuthorizationController(RunRepository runs, ObjectMapper json, String callbackToken,
                                       AddressLookup addressLookup) {
        this.runs = runs;
        this.json = json;
        this.callbackToken = callbackToken == null ? "" : callbackToken;
        this.addressLookup = addressLookup == null ? InetAddress::getAllByName : addressLookup;
    }

    @PostMapping("/api/v1/internal/runs/{runId}/proxy-authorizations")
    public ResponseEntity<CapabilityResponse> authorize(
            @PathVariable UUID runId,
            @RequestHeader(value = "X-Runner-Token", required = false) String suppliedToken,
            @RequestBody AuthorizationRequest request) {
        verifyToken(suppliedToken);
        if (request == null || request.targetUrl() == null || request.proxyUrl() == null) {
            throw denied("代理授权请求不完整");
        }
        RunRecord run = runs.findById(runId);
        if (run == null || run.executionPlan() == null) throw denied("运行不存在");
        JsonNode snapshot = run.executionPlan().path("targetPolicySnapshot");
        JsonNode ruleNode = snapshot.path("rules");
        if (!ruleNode.isArray()) throw denied("运行缺少目标策略快照");
        List<String> rules = new ArrayList<>();
        ruleNode.forEach(value -> { if (value.isTextual()) rules.add(value.asText()); });
        TargetAllowlist policy;
        try { policy = TargetAllowlist.parse(rules); }
        catch (IllegalArgumentException exception) { throw denied("目标策略快照不合法"); }
        URI target = parseHttpUri(request.targetUrl());
        URI proxy = parseHttpUri(request.proxyUrl());
        if (!policy.evaluate(target).allowed() || !policy.evaluate(proxy).allowed()
                || !"http".equalsIgnoreCase(proxy.getScheme())) throw denied("代理目标未获准");
        // The Runner request can carry resolver observations for diagnostics, but
        // it is never trusted as an authorization source. Platform resolves both
        // endpoints against the persisted run snapshot before signing the token.
        List<String> normalizedAddresses = resolveAddresses(target, policy);
        List<String> normalizedProxyAddresses = resolveAddresses(proxy, policy);
        Instant expiresAt = Instant.now().plusSeconds(CAPABILITY_SECONDS);
        ObjectNode claims = json.createObjectNode();
        claims.put("runId", runId.toString());
        claims.put("targetUrl", target.toString());
        claims.put("proxyUrl", proxy.toString());
        claims.put("targetCanonical", canonicalUri(target));
        claims.put("proxyCanonical", canonicalUri(proxy));
        claims.put("nonce", UUID.randomUUID().toString());
        claims.put("expiresAt", expiresAt.toEpochMilli());
        ArrayNode addresses = claims.putArray("addresses");
        normalizedAddresses.forEach(addresses::add);
        ArrayNode proxyAddresses = claims.putArray("proxyAddresses");
        normalizedProxyAddresses.forEach(proxyAddresses::add);
        ArrayNode snapshotRules = claims.putArray("rules");
        rules.forEach(snapshotRules::add);
        String payload;
        try { payload = Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(claims)); }
        catch (Exception exception) { throw denied("代理授权生成失败"); }
        String signature = sign(payload);
        return ResponseEntity.ok(new CapabilityResponse(payload + "." + signature, expiresAt.toString(),
                normalizedAddresses, normalizedProxyAddresses));
    }

    private List<String> resolveAddresses(URI target, TargetAllowlist policy) {
        InetAddress[] supplied;
        if (isAddressLiteral(target.getHost())) {
            try {
                supplied = new InetAddress[]{InetAddress.getByName(stripBrackets(target.getHost()))};
            } catch (UnknownHostException exception) {
                throw denied("代理地址未获准");
            }
        } else {
            try {
                supplied = addressLookup.lookup(target.getHost());
            } catch (UnknownHostException exception) {
                throw denied("代理 DNS 解析失败");
            }
        }
        List<String> normalized = new ArrayList<>();
        for (InetAddress address : supplied == null ? new InetAddress[0] : supplied) {
            try {
                if (address == null || address.getHostAddress().contains("%")) throw new IllegalArgumentException();
                if (isRestricted(address) && !policy.matchesAddress(target, address)) throw new IllegalArgumentException();
                String value = address.getHostAddress().toLowerCase(java.util.Locale.ROOT);
                if (!normalized.contains(value)) normalized.add(value);
            } catch (IllegalArgumentException exception) {
                throw denied("代理地址未获准");
            }
        }
        if (normalized.isEmpty()) throw denied("代理 DNS 解析为空");
        normalized.sort(String::compareTo);
        return List.copyOf(normalized);
    }

    private static boolean isAddressLiteral(String host) {
        String value = stripBrackets(host);
        if (value.contains("%")) return false;
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

    private static String stripBrackets(String host) {
        return host != null && host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
    }

    private URI parseHttpUri(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getUserInfo() != null || uri.getFragment() != null || uri.getHost() == null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
            TargetAllowlist.effectivePort(uri);
            return uri;
        } catch (RuntimeException exception) { throw denied("代理 URL 不合法"); }
    }

    private static String canonicalUri(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.contains(":")) host = "[" + host + "]";
        int port = TargetAllowlist.effectivePort(uri);
        String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return scheme + "://" + host + ":" + port + path + query;
    }

    private void verifyToken(String supplied) {
        if (callbackToken.isBlank() || supplied == null
                || !MessageDigest.isEqual(callbackToken.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiDomainException(HttpStatus.UNAUTHORIZED.value(), "RUNNER_AUTHENTICATION_REQUIRED", "Runner 认证失败");
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(callbackToken.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw denied("代理授权签名失败"); }
    }

    private static boolean isRestricted(InetAddress address) {
        return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || (address instanceof Inet6Address ipv6 && ipv6.isLinkLocalAddress());
    }

    private static ApiDomainException denied(String message) {
        return new ApiDomainException(HttpStatus.FORBIDDEN.value(), "PROXY_AUTHORIZATION_DENIED", message);
    }

    public record AuthorizationRequest(String targetUrl, String proxyUrl, List<String> approvedAddresses) {}
    public record CapabilityResponse(String capability, String expiresAt, List<String> addresses,
                                     List<String> proxyAddresses) {
        public CapabilityResponse(String capability, String expiresAt, List<String> addresses) {
            this(capability, expiresAt, addresses, List.of());
        }
    }
}

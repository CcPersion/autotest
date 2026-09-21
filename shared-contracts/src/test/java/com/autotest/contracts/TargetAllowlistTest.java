package com.autotest.contracts;

import com.autotest.contracts.network.TargetAllowlist;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetAllowlistTest {

    @Test
    void allowsExactAndOnlyOneLevelSubdomainRules() {
        TargetAllowlist policy = TargetAllowlist.parse(List.of("example.test", "*.internal.test"));

        assertTrue(policy.evaluate(URI.create("https://example.test/api")).allowed());
        assertTrue(policy.evaluate(URI.create("https://api.internal.test/v1")).allowed());
        assertFalse(policy.evaluate(URI.create("https://deep.api.internal.test/v1")).allowed());
        assertFalse(policy.evaluate(URI.create("https://internal.test/v1")).allowed());
        assertFalse(policy.evaluate(URI.create("https://other.test/v1")).allowed());
    }

    @Test
    void rejectsNonHttpTargetsCredentialsAndFragments() {
        TargetAllowlist policy = TargetAllowlist.parse(List.of("example.test"));

        assertFalse(policy.evaluate(URI.create("ftp://example.test/file")).allowed());
        assertFalse(policy.evaluate(URI.create("https://user:pass@example.test/api")).allowed());
        assertFalse(policy.evaluate(URI.create("https://example.test/api#secret")).allowed());
        assertFalse(policy.evaluate(URI.create("https:///api")).allowed());
    }

    @Test
    void normalizesRulesAndRejectsInvalidOrOversizedConfiguration() {
        TargetAllowlist policy = TargetAllowlist.parse(List.of(" Example.TEST. ", "example.test", "*.internal.test"));

        assertEquals(2, policy.rules().size());
        assertTrue(policy.evaluate(URI.create("https://example.test")).allowed());
        assertThrows(IllegalArgumentException.class,
                () -> TargetAllowlist.parse(List.of("https://example.test/path")));
        assertThrows(IllegalArgumentException.class,
                () -> TargetAllowlist.parse(java.util.stream.IntStream.range(0, 101)
                        .mapToObj(index -> "host-" + index + ".test").toList()));
    }

    @Test
    void explicitPrivateIpCanBeAllowedButUnlistedPrivateIpIsDenied() {
        TargetAllowlist policy = TargetAllowlist.parse(List.of("127.0.0.1", "[::1]"));

        assertTrue(policy.evaluate(URI.create("http://127.0.0.1/health")).allowed());
        assertFalse(policy.evaluate(URI.create("http://127.0.0.2/health")).allowed());
        assertTrue(policy.evaluate(URI.create("http://[::1]/health")).allowed());
    }

    @Test
    void appliesDefaultAndExplicitPortsToHostnameIpAndCidrRules() {
        TargetAllowlist policy = TargetAllowlist.parse(List.of(
                "api.example.test:8443", "127.0.0.0/8:8080", "[::1]:9090"));

        assertTrue(policy.evaluate(URI.create("https://api.example.test:8443/health")).allowed());
        assertFalse(policy.evaluate(URI.create("https://api.example.test/health")).allowed());
        assertTrue(policy.evaluate(URI.create("http://127.0.0.1:8080/health")).allowed());
        assertFalse(policy.evaluate(URI.create("http://127.0.0.1:8081/health")).allowed());
        assertTrue(policy.evaluate(URI.create("http://[::1]:9090/health")).allowed());
    }

    @Test
    void unportedRuleOnlyAuthorizesSchemeDefaultPort() {
        TargetAllowlist policy = TargetAllowlist.parse(List.of("example.test", "127.0.0.1"));

        assertTrue(policy.evaluate(URI.create("http://example.test/health")).allowed());
        assertFalse(policy.evaluate(URI.create("http://example.test:8080/health")).allowed());
        assertTrue(policy.evaluate(URI.create("https://example.test/health")).allowed());
        assertFalse(policy.evaluate(URI.create("https://example.test:80/health")).allowed());
        assertTrue(policy.evaluate(URI.create("http://127.0.0.1/health")).allowed());
        assertFalse(policy.evaluate(URI.create("http://127.0.0.1:8080/health")).allowed());
    }

    @Test
    void rejectsAmbiguousOrDeepWildcardRules() {
        assertThrows(IllegalArgumentException.class, () -> TargetAllowlist.parse(List.of("*")));
        assertThrows(IllegalArgumentException.class, () -> TargetAllowlist.parse(List.of("*.*.example.test")));
        assertThrows(IllegalArgumentException.class, () -> TargetAllowlist.parse(List.of("a.*.example.test")));
        assertThrows(IllegalArgumentException.class, () -> TargetAllowlist.parse(List.of("example.test:0")));
        assertThrows(IllegalArgumentException.class, () -> TargetAllowlist.parse(List.of("example.test:65536")));
        assertThrows(IllegalArgumentException.class, () -> TargetAllowlist.parse(List.of("[fe80::1%eth0]")));
    }

    @Test
    void roundTripsBracketedIpv6CidrRulesWithPorts() {
        TargetAllowlist policy = TargetAllowlist.parse(List.of("[2001:db8::/32]:443"));

        assertEquals(List.of("[2001:db8::/32]:443"), policy.rules());
        assertEquals(policy.rules(), TargetAllowlist.parse(policy.rules()).rules());
        assertTrue(policy.evaluate(URI.create("https://[2001:db8::1]:443/health")).allowed());
    }

}

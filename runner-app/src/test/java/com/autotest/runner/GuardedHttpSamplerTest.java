package com.autotest.runner;

import com.autotest.contracts.network.TargetAllowlist;
import org.apache.jmeter.testelement.property.BooleanProperty;
import org.apache.jmeter.testelement.property.StringProperty;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardedHttpSamplerTest {

    @Test
    void allowsRealJmxTargetPolicyForOrdersTemplateUrl() throws Exception {
        GuardedHttpSampler sampler = configuredSampler();
        URL target = new URL("http", "target", 8080, "/orders/{orderId}");

        TargetAllowlist.Decision decision = evaluateTarget(sampler, target);

        assertTrue(decision.allowed(), () -> "decision=" + decision
                + ", url=" + target
                + ", allowlist=" + sampler.getPropertyAsString(GuardedHttpSampler.TARGET_ALLOWLIST)
                + ", policyRequired=" + sampler.getPropertyAsBoolean(GuardedHttpSampler.TARGET_POLICY_REQUIRED, false)
                + ", dnsRequired=" + sampler.getPropertyAsBoolean(GuardedHttpSampler.TARGET_DNS_REQUIRED, false)
                + ", protocol=" + sampler.getProtocol()
                + ", domain=" + sampler.getDomain()
                + ", port=" + sampler.getPort()
                + ", path=" + sampler.getPath());
    }

    @Test
    void rejectsCredentialFragmentWrongTargetAndNonHttpUrls() throws Exception {
        GuardedHttpSampler sampler = configuredSampler();

        TargetAllowlist.Decision credential = evaluateTarget(sampler,
                new URL("http://user:secret@target:8080/orders/{orderId}"));
        TargetAllowlist.Decision fragment = evaluateTarget(sampler,
                new URL("http", "target", 8080, "/orders/{orderId}#fragment"));
        TargetAllowlist.Decision wrongHost = evaluateTarget(sampler,
                new URL("http", "other", 8080, "/orders/{orderId}"));
        TargetAllowlist.Decision wrongPort = evaluateTarget(sampler,
                new URL("http", "target", 8081, "/orders/{orderId}"));
        TargetAllowlist.Decision nonHttp = evaluateTarget(sampler,
                new URL("ftp", "target", 8080, "/orders/{orderId}"));

        assertFalse(credential.allowed(), () -> "credential URL was allowed: " + credential);
        assertFalse(fragment.allowed(), () -> "fragment URL was allowed: " + fragment);
        assertEquals(GuardedHttpSampler.TARGET_NOT_ALLOWED, fragment.reason());
        assertFalse(wrongHost.allowed(), () -> "wrong host was allowed: " + wrongHost);
        assertFalse(wrongPort.allowed(), () -> "wrong port was allowed: " + wrongPort);
        assertFalse(nonHttp.allowed(), () -> "non-HTTP URL was allowed: " + nonHttp);
    }

    @Test
    void usesOriginForDnsPinningAndRejectsFragmentInPinTarget() throws Exception {
        InetAddress approvedAddress = InetAddress.getByName("172.31.90.10");
        GuardedHttpSampler sampler = configuredSampler();
        Field resolverField = GuardedHttpSampler.class.getDeclaredField("dnsResolver");
        resolverField.setAccessible(true);
        resolverField.set(sampler, new ApprovedDnsResolver(host -> new InetAddress[]{approvedAddress}));

        URL target = new URL("http", "target", 8080, "/orders/{orderId}?expand=items");
        Method originMethod = GuardedHttpSampler.class.getDeclaredMethod("policyOrigin", URL.class);
        originMethod.setAccessible(true);
        URI origin = (URI) originMethod.invoke(null, target);

        assertEquals("http", origin.getScheme());
        assertEquals("target", origin.getHost());
        assertEquals(8080, origin.getPort());
        assertNull(origin.getUserInfo());
        assertEquals("/", origin.getPath());
        assertNull(origin.getQuery());
        assertNull(origin.getFragment());

        Method pinTarget = GuardedHttpSampler.class.getDeclaredMethod("pinTarget", URI.class, boolean.class);
        pinTarget.setAccessible(true);
        pinTarget.invoke(sampler, origin, false);

        URI fragmentTarget = URI.create("http://target:8080/orders#fragment");
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> pinTarget.invoke(sampler, fragmentTarget, false));
        assertEquals(java.net.URISyntaxException.class, failure.getCause().getClass(),
                () -> "unexpected cause: " + failure.getCause());
    }

    @Test
    void rejectsAddressChangeWhenTheSecondLookupChangesThenReturnsToTheOriginalAddress() throws Exception {
        InetAddress first = InetAddress.getByName("203.0.113.10");
        InetAddress changed = InetAddress.getByName("198.51.100.20");
        AtomicInteger lookups = new AtomicInteger();
        ApprovedDnsResolver resolver = new ApprovedDnsResolver(host -> switch (lookups.getAndIncrement()) {
            case 0 -> new InetAddress[]{first};
            case 1 -> new InetAddress[]{changed};
            default -> new InetAddress[]{first};
        });
        GuardedHttpSampler sampler = new GuardedHttpSampler();
        sampler.setProperty(new StringProperty(GuardedHttpSampler.TARGET_ALLOWLIST, "api.example.test"));
        Field resolverField = GuardedHttpSampler.class.getDeclaredField("dnsResolver");
        resolverField.setAccessible(true);
        resolverField.set(sampler, resolver);
        Method pinTarget = GuardedHttpSampler.class.getDeclaredMethod("pinTarget", URI.class, boolean.class);
        pinTarget.setAccessible(true);
        URI target = URI.create("https://api.example.test/health");

        pinTarget.invoke(sampler, target, false);

        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> pinTarget.invoke(sampler, target, false));
        assertEquals(ApprovedDnsResolver.DnsPolicyException.class, failure.getCause().getClass(),
                () -> "unexpected cause: " + failure.getCause());
    }

    private static TargetAllowlist.Decision evaluateTarget(GuardedHttpSampler sampler, URL target) throws Exception {
        Method evaluateTarget = GuardedHttpSampler.class.getDeclaredMethod("evaluateTarget", URL.class);
        evaluateTarget.setAccessible(true);
        return (TargetAllowlist.Decision) evaluateTarget.invoke(sampler, target);
    }

    private static GuardedHttpSampler configuredSampler() {
        GuardedHttpSampler sampler = new GuardedHttpSampler();
        sampler.setProperty(new StringProperty(GuardedHttpSampler.TARGET_ALLOWLIST,
                String.join("\n", "target:8080", "172.31.90.10:8080", "missing-target:8080")));
        sampler.setProperty(new BooleanProperty(GuardedHttpSampler.TARGET_POLICY_REQUIRED, true));
        sampler.setProperty(new BooleanProperty(GuardedHttpSampler.TARGET_DNS_REQUIRED, true));
        sampler.setProtocol("http");
        sampler.setDomain("target");
        sampler.setPort(8080);
        sampler.setPath("/orders/{orderId}");
        return sampler;
    }
}

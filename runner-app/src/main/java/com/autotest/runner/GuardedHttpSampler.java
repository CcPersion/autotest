package com.autotest.runner;

import com.autotest.contracts.network.TargetAllowlist;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.engine.event.LoopIterationEvent;
import org.apache.jmeter.samplers.Interruptible;
import org.apache.jmeter.samplers.SampleResult;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 平台审核的 HTTP Sampler。
 *
 * <p>JMeter 的 followRedirects 会在每一跳再次调用本 Sampler 的 protected
 * sample 方法，因此策略检查不会被 JMeter 内部重定向循环绕过。底层
 * HTTPHC4Impl 只负责单跳网络访问，自动重定向由本类明确关闭。</p>
 */
public final class GuardedHttpSampler extends HTTPSamplerBase implements Interruptible {

    public static final String TARGET_ALLOWLIST = "autotest.target_allowlist";
    public static final String TARGET_POLICY_REQUIRED = "autotest.target_policy_required";
    public static final String TARGET_DNS_REQUIRED = "autotest.target_dns_required";
    public static final String TARGET_NOT_ALLOWED = "TARGET_NOT_ALLOWED";
    public static final String TARGET_DNS_NOT_ALLOWED = "TARGET_DNS_NOT_ALLOWED";

    private transient GuardedHttpClient client;
    private transient ApprovedDnsResolver dnsResolver;
    private transient PinnedDnsCacheManager pinnedDns;
    private transient Map<String, ApprovedDnsResolver.Resolution> approvedResolutions;

    @Override
    protected HTTPSampleResult sample(URL url, String method, boolean followingRedirect, int depth) {
        TargetAllowlist.Decision decision = evaluateTarget(url);
        if (!decision.allowed()) {
            return rejectedResult(url, method);
        }
        if (getPropertyAsBoolean(TARGET_DNS_REQUIRED, false)) {
            try {
                pinTarget(policyOrigin(url), false);
                pinProxyIfConfigured();
            } catch (ApprovedDnsResolver.DnsPolicyException | URISyntaxException | java.net.UnknownHostException exception) {
                return rejectedResult(url, method, TARGET_DNS_NOT_ALLOWED);
            }
        }
        HTTPSampleResult result = client().send(url, method, followingRedirect, depth);
        if (getPropertyAsBoolean(TARGET_DNS_REQUIRED, false)) {
            try {
                String marker = "X-Autotest-Pinned-Address: "
                        + pinned().selectedAddress(url.getHost()).getHostAddress();
                String headers = result.getResponseHeaders();
                result.setResponseHeaders((headers == null || headers.isBlank() ? "" : headers + "\n") + marker);
            } catch (java.net.UnknownHostException exception) {
                return rejectedResult(url, method, TARGET_DNS_NOT_ALLOWED);
            }
        }
        return result;
    }

    @Override
    public void threadFinished() {
        if (client != null) {
            client.threadFinishedSafely();
            client = null;
        }
        dnsResolver = null;
        if (pinnedDns != null) pinnedDns.clearPins();
        pinnedDns = null;
        approvedResolutions = null;
        super.threadFinished();
    }

    @Override
    public void testIterationStart(LoopIterationEvent event) {
        if (client != null) {
            client.notifyFirstSampleAfterLoopRestartSafely();
        }
    }

    public boolean interrupt() {
        return client != null && client.interruptSafely();
    }

    private GuardedHttpClient client() {
        if (client == null) {
            client = new GuardedHttpClient(this);
        }
        return client;
    }

    private TargetAllowlist.Decision evaluateTarget(URL url) {
        if (url != null && url.getRef() != null) {
            return new TargetAllowlist.Decision(false, TARGET_NOT_ALLOWED, url.getHost());
        }
        if (!getPropertyAsBoolean(TARGET_POLICY_REQUIRED, false)) {
            return new TargetAllowlist.Decision(false, "目标策略缺失", null);
        }
        String rawRules = getPropertyAsString(TARGET_ALLOWLIST, "");
        if (rawRules.isBlank()) {
            return new TargetAllowlist.Decision(false, "目标白名单为空", null);
        }
        try {
            TargetAllowlist allowlist = TargetAllowlist.parse(rules());
            return allowlist.evaluate(policyOrigin(url));
        } catch (IllegalArgumentException | java.net.URISyntaxException exception) {
            return new TargetAllowlist.Decision(false, "目标策略不合法", null);
        }
    }

    private HTTPSampleResult rejectedResult(URL target, String method) {
        return rejectedResult(target, method, TARGET_NOT_ALLOWED);
    }

    private ApprovedDnsResolver dns() {
        if (dnsResolver == null) dnsResolver = new ApprovedDnsResolver();
        return dnsResolver;
    }

    private PinnedDnsCacheManager pinned() {
        if (pinnedDns == null) {
            pinnedDns = new PinnedDnsCacheManager();
            setDNSResolver(pinnedDns);
        } else if (getDNSResolver() != pinnedDns) {
            setDNSResolver(pinnedDns);
        }
        return pinnedDns;
    }

    private Map<String, ApprovedDnsResolver.Resolution> approved() {
        if (approvedResolutions == null) approvedResolutions = new HashMap<>();
        return approvedResolutions;
    }

    private void pinTarget(URI target, boolean proxy) throws URISyntaxException, java.net.UnknownHostException {
        ApprovedDnsResolver.Resolution current = dns().resolve(policyOrigin(target), rules());
        String key = (proxy ? "proxy:" : "target:") + current.host() + ":" + current.port();
        ApprovedDnsResolver.Resolution previous = approved().get(key);
        if (previous != null) dns().verifyStable(current, previous);
        else approved().put(key, current);
        pinned().pin(current.host(), current.port(), current.addresses(), Instant.now().plus(30, ChronoUnit.SECONDS));
        pinned().activate(current.host(), current.port());
    }

    private static URI policyOrigin(URL url) throws URISyntaxException {
        if (url == null) return null;
        if (url.getRef() != null) throw new URISyntaxException(url.toString(), "URL fragment is not allowed");
        return new URI(url.getProtocol(), url.getUserInfo(), url.getHost(), url.getPort(), "/", null, null);
    }

    private static URI policyOrigin(URI target) throws URISyntaxException {
        if (target == null) return null;
        if (target.getRawFragment() != null) {
            throw new URISyntaxException(target.toString(), "URI fragment is not allowed");
        }
        return new URI(target.getScheme(), target.getUserInfo(), target.getHost(), target.getPort(), "/", null, null);
    }

    private void pinProxyIfConfigured() throws URISyntaxException, java.net.UnknownHostException {
        String proxyHost = getProxyHost();
        if (proxyHost == null || proxyHost.isBlank()) return;
        String scheme = getProxyScheme() == null || getProxyScheme().isBlank() ? "http" : getProxyScheme();
        int port = getProxyPortInt();
        if (port < 1) throw new ApprovedDnsResolver.DnsPolicyException("代理端口不合法");
        URI proxy = new URI(scheme, null, proxyHost, port, "/", null, null);
        pinTarget(proxy, true);
    }

    private List<String> rules() {
        return Arrays.stream(getPropertyAsString(TARGET_ALLOWLIST, "").split("\\R"))
                .map(String::strip).filter(value -> !value.isBlank()).toList();
    }

    private HTTPSampleResult rejectedResult(URL target, String method, String responseCode) {
        HTTPSampleResult result = new HTTPSampleResult();
        result.sampleStart();
        result.setSampleLabel(getName());
        result.setHTTPMethod(method);
        result.setURL(safeResultUrl(target));
        result.setResponseCode(responseCode);
        result.setResponseMessage(responseCode);
        result.setSuccessful(false);
        result.setResponseData(new byte[0]);
        result.setDataType(SampleResult.TEXT);
        result.sampleEnd();
        return result;
    }

    private static URL safeResultUrl(URL target) {
        if (target == null) return null;
        try {
            return new URL(target.getProtocol(), target.getHost(), target.getPort(), "/");
        } catch (MalformedURLException ignored) {
            return null;
        }
    }
}

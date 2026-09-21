package com.autotest.runner;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunnerImageContractTest {

    private static final String JMETER_VERSION = "5.6.3";
    private static final String JMETER_SHA512 =
            "5978a1a35edb5a7d428e270564ff49d2b1b257a65e17a759d259a9283fc17093e522fe46f474a043864aea6910683486340706d745fcdf3db1505fd71e689083";

    @Test
    void dockerfilePinsOfficialJmeterChecksumJava17NonRootAndHeadlessRuntime() throws IOException {
        String dockerfile = read("runner-app/Dockerfile");
        String normalized = dockerfile.toLowerCase(Locale.ROOT);

        assertTrue(dockerfile.contains("eclipse-temurin:17-jre"));
        assertTrue(dockerfile.contains("JMETER_VERSION=" + JMETER_VERSION));
        assertTrue(dockerfile.contains("apache-jmeter-5.6.3.tgz"));
        assertTrue(dockerfile.contains("mirrors.cloud.tencent.com/apache/jmeter/binaries/apache-jmeter-5.6.3.tgz"));
        assertTrue(dockerfile.contains(JMETER_SHA512));
        assertTrue(normalized.contains("sha512sum") && normalized.contains("-c"));
        assertTrue(normalized.contains("-c -"));
        assertTrue(normalized.contains("--connect-timeout"));
        assertTrue(normalized.contains("--max-time"));
        assertTrue(normalized.contains("wc -c"));
        assertTrue(normalized.contains("max_attempts"));
        assertFalse(normalized.contains("--retry-all-errors"));
        assertFalse(normalized.contains("--silent"));
        assertFalse(normalized.contains("apt-get install"));
        assertTrue(dockerfile.contains("/work/runs"));
        assertTrue(dockerfile.contains("target/runner-app-0.1.0-SNAPSHOT.jar"));
        assertTrue(dockerfile.contains("target/runner-app-0.1.0-SNAPSHOT-components.jar"),
                "组件扩展包必须由 Maven 单独产出");
        assertTrue(dockerfile.contains("/opt/jmeter/lib/ext/autotest-runner-components.jar"),
                "自定义 JMeter 组件必须进入 JMeter 扩展类路径");
        assertTrue(read("runner-app/pom.xml").contains("JmeterSecretFileFunction.class"));
        assertTrue(dockerfile.contains("/opt/runner/runner.jar"));
        assertTrue(normalized.contains("headless"));
        assertTrue(normalized.contains("user runner"));
        assertFalse(normalized.contains("jmeter-plugins"));
        assertFalse(normalized.contains("x11"));
        assertFalse(normalized.contains("openbox"));
        assertFalse(normalized.contains("desktop"));
    }

    @Test
    void componentsJarIncludesAllApprovedDnsResolverRuntimeClasses() throws IOException {
        String pom = read("runner-app/pom.xml");

        assertTrue(pom.contains("com/autotest/runner/ApprovedDnsResolver.class"));
        assertTrue(pom.contains("com/autotest/runner/ApprovedDnsResolver$AddressLookup.class"));
        assertTrue(pom.contains("com/autotest/runner/ApprovedDnsResolver$Resolution.class"));
        assertTrue(pom.contains("com/autotest/runner/ApprovedDnsResolver$DnsPolicyException.class"));
        assertTrue(pom.contains("com/autotest/runner/ApprovedDnsResolver$DirectDnsLookup.class"));
        assertTrue(pom.contains("com/autotest/runner/ApprovedDnsResolver$LazyDirectDnsLookup.class"));
        assertTrue(pom.contains("com/autotest/runner/ApprovedDnsResolver$DirectDnsLookup$NameRead.class"));
        assertTrue(pom.contains("com/autotest/runner/PinnedDnsCacheManager.class"));
        assertTrue(pom.contains("com/autotest/runner/PinnedDnsCacheManager$Pin.class"));
        assertTrue(pom.contains("com/autotest/runner/PinnedDnsCacheManager$Bytes.class"));
        assertTrue(pom.contains("com/autotest/contracts/network/TargetAllowlist.class"));
        assertTrue(pom.contains("com/autotest/contracts/network/TargetAllowlist$Decision.class"));
        assertTrue(pom.contains("com/autotest/contracts/network/TargetAllowlist$HostAndPort.class"));
        assertTrue(pom.contains("com/autotest/contracts/network/TargetAllowlist$Cidr.class"));
        assertTrue(pom.contains("com/autotest/contracts/network/TargetAllowlist$Rule.class"));
        assertTrue(pom.contains("com/autotest/contracts/network/TargetAllowlist$Kind.class"));
    }

    @Test
    void smokeJmxUsesOnlyBuiltInNonNetworkComponents() throws IOException {
        String smoke = read("runner-app/src/test/resources/smoke.jmx");
        String normalized = smoke.toLowerCase(Locale.ROOT);

        assertTrue(smoke.contains("jmeter=\"5.6.3\""));
        assertTrue(smoke.contains("TestPlan"));
        assertTrue(smoke.contains("ThreadGroup"));
        assertTrue(smoke.contains("DebugSampler"));
        assertFalse(normalized.contains("jsr223"));
        assertFalse(normalized.contains("beanshell"));
        assertFalse(normalized.contains("osprocess"));
        assertFalse(normalized.contains("httpsamplerproxy"));
        assertFalse(normalized.contains("jdbcrequest"));
    }

    @Test
    void composeDefinesRunnerImageWithDedicatedRunVolumeAndHealthcheck() throws IOException {
        String compose = read("deployment/docker-compose.yml");
        String normalized = compose.toLowerCase(Locale.ROOT);

        assertTrue(compose.contains("runner-app:"));
        assertTrue(compose.contains("context: ../runner-app"));
        assertTrue(compose.contains("dockerfile: Dockerfile"));
        assertTrue(compose.contains("/work/runs"));
        assertTrue(normalized.contains("healthcheck"));
        assertTrue(normalized.contains("jmeter"));
        assertTrue(normalized.contains("--version"));
        assertTrue(normalized.contains("java"));
        assertTrue(normalized.contains("/opt/runner/runner.jar"));
    }

    @Test
    void wslScriptsBuildAndSmokeThePinnedRunner() throws IOException {
        String buildScript = read("deployment/scripts/build-runner-image.sh");
        String smokeScript = read("deployment/scripts/smoke-runner.sh");

        assertTrue(buildScript.contains("docker build"));
        assertTrue(buildScript.contains("runner-app/Dockerfile"));
        assertTrue(smokeScript.contains("docker run"));
        assertTrue(smokeScript.contains("jmeter -n"));
        assertTrue(smokeScript.contains("smoke.jmx"));
        assertTrue(smokeScript.contains("result.jtl"));
        assertTrue(smokeScript.contains("id -u"));
        assertTrue(smokeScript.contains("java -version"));
    }

    private String read(String relativePath) throws IOException {
        Path path = Path.of(relativePath);
        if (!Files.exists(path)) {
            path = Path.of("..", relativePath);
        }
        return Files.readString(path);
    }
}

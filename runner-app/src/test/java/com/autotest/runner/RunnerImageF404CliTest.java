package com.autotest.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 Runner 镜像回归。默认不执行；设置 AUTOTEST_F404_IMAGE 后由 WSL/Docker 门禁显式运行。
 * 该测试必须检查 JTL 样本和 TARGET_NOT_ALLOWED，不能把 JMeter 退出码 0 当作成功。
 */
@EnabledIfEnvironmentVariable(named = "AUTOTEST_F404_IMAGE", matches = ".+")
class RunnerImageF404CliTest {

    @Test
    void cliLoadsGuardedSamplerAndRecordsFailClosedSample() throws Exception {
        Path root = Files.createTempDirectory("f4-04-cli-");
        Path jmx = root.resolve("missing-policy.jmx");
        String image = System.getenv("AUTOTEST_F404_IMAGE");
        try {
            JmeterPlan plan = new JmeterPlan("f4-04-cli", "f4-04-cli-step",
                    "http://127.0.0.1:1", "GET", "/must-not-run",
                    List.of(), List.of(), JmeterBody.none(), Map.of(), List.of());
            new JmeterPlanCompiler().compile(plan, jmx);
            String missingPolicy = Files.readString(jmx)
                    .replace("<boolProp name=\"autotest.target_policy_required\">true</boolProp>",
                            "<boolProp name=\"autotest.target_policy_required\">false</boolProp>")
                    .replace("<stringProp name=\"autotest.target_allowlist\">127.0.0.1</stringProp>",
                            "<stringProp name=\"autotest.target_allowlist\"></stringProp>");
            Files.writeString(jmx, missingPolicy);

            Process process = new ProcessBuilder("docker", "run", "--rm",
                    "-v", jmx.toAbsolutePath() + ":/tmp/missing-policy.jmx:ro", image,
                    "sh", "-lc",
                    "jmeter -n -Jjmeter.save.saveservice.output_format=xml "
                            + "-t /tmp/missing-policy.jmx -l /tmp/missing-policy.jtl "
                            + ">/tmp/jmeter-cli.log 2>&1; "
                            + "status=$?; cat /tmp/jmeter-cli.log; "
                            + "test -s /tmp/missing-policy.jtl; cat /tmp/missing-policy.jtl; exit $status")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int exit = process.waitFor();

            assertTrue(exit == 0, "JMeter CLI 退出码=" + exit + "\n" + output);
            assertTrue(output.contains("TARGET_NOT_ALLOWED"),
                    "CLI 必须记录 fail-closed 样本，而不是仅退出 0：\n" + output);
            assertTrue(output.contains("<httpSample") || output.contains("samples=\"1\""),
                    "CLI JTL 必须包含至少一个样本：\n" + output);
        } finally {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (java.io.IOException ignored) {
                        // 测试临时文件清理失败不应覆盖 CLI 断言结果。
                    }
                });
            }
        }
    }
}

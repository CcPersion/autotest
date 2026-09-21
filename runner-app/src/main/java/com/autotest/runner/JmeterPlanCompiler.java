package com.autotest.runner;

import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.IfController;
import org.apache.jmeter.control.WhileController;
import org.apache.jmeter.control.ForeachController;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.ThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.ListedHashTree;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将单接口平台计划编译为可由固定 JMeter CLI 执行的 JMX。 */
public final class JmeterPlanCompiler {

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");
    private static final Pattern CONTROL_VARIABLE = Pattern.compile("\\$\\{([^}]*)}");
    private static final Pattern CONTROL_VARIABLE_NAME = Pattern.compile("[A-Za-z0-9_.-]+");
    private static final String JDBC_DATA_SOURCE = "autotest_jdbc";

    private static boolean jmeterPropertiesInitialized;
    private final JmeterComponentMapper components = new JmeterComponentMapper();

    public JmeterPlanCompiler() {
        initializeJmeterProperties();
    }

    public Path compile(JmeterPlan plan, Path output) throws IOException {
        if (plan == null) {
            throw new IllegalArgumentException("执行计划不能为空");
        }
        if (!plan.targetPolicyRequired() || plan.targetAllowlist().isEmpty()) {
            throw new IllegalArgumentException("执行计划缺少项目目标白名单");
        }
        if (output == null) {
            throw new IllegalArgumentException("output 不能为空");
        }
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        ListedHashTree root = new ListedHashTree();
        TestPlan testPlan = JmeterComponentMapper.configure(
                new TestPlan("Platform Test Plan [" + plan.planId() + "]"),
                "TestPlanGui", "TestPlan");
        testPlan.setName("Platform Test Plan [" + plan.planId() + "]");
        testPlan.setUserDefinedVariables(components.userVariables(plan.variables()));
        ListedHashTree planTree = root.add(testPlan);

        LoopController loopController = JmeterComponentMapper.configure(
                new LoopController(), "LoopControlPanel", "LoopController");
        loopController.setName("Single Iteration [" + plan.stepId() + "]");
        loopController.setLoops(1);
        loopController.setFirst(true);

        ThreadGroup threadGroup = JmeterComponentMapper.configure(
                new ThreadGroup(), "ThreadGroupGui", "ThreadGroup");
        threadGroup.setName("Single Thread Group [" + plan.stepId() + "]");
        threadGroup.setNumThreads(1);
        threadGroup.setRampUp(1);
        threadGroup.setSamplerController(loopController);
        ListedHashTree threadTree = planTree.add(threadGroup);
        threadTree.add(components.cookies(plan));

        var sampler = components.httpSampler(plan);
        ListedHashTree samplerTree = threadTree.add(sampler);
        for (int ruleIndex = 0; ruleIndex < plan.extractors().size(); ruleIndex++) {
            JmeterExtractor input = plan.extractors().get(ruleIndex);
            samplerTree.add(namedRule(components.extractor(input, plan.stepId()), ruleIndex));
            if (input.failIfMissing()) {
                samplerTree.add(namedRule(components.extractorPresenceAssertion(input, plan.stepId()), ruleIndex));
            }
        }
        if (!plan.extractors().isEmpty()) {
            samplerTree.add(components.extractionReporter(plan.extractors(), plan.stepId()));
        }
        samplerTree.add(components.headers(plan));
        for (int ruleIndex = 0; ruleIndex < plan.assertions().size(); ruleIndex++) {
            JmeterAssertion input = plan.assertions().get(ruleIndex);
            samplerTree.add(namedRule(components.assertion(input, plan.stepId()), ruleIndex));
        }

        try (OutputStream stream = Files.newOutputStream(output)) {
            SaveService.saveTree(root, stream);
        }
        return output;
    }

    private static org.apache.jmeter.testelement.TestElement namedRule(
            org.apache.jmeter.testelement.TestElement element, int ruleIndex) {
        element.setName(element.getName() + " ruleIndex=" + ruleIndex);
        return element;
    }

    /**
     * 将受控 SQL 步骤编译为 JMeter 原生 JDBC DataSource/JDBCSampler 组件。
     * 当前运行链仍由 JdbcSqlExecutor 负责结果语义；该 JMX 作为可审计、可复现的原生执行计划产物。
     */
    public Path compileJdbc(JsonNode plan, SecretFileMaterializer secrets,
                            Map<String, JsonNode> extractedOverride, Path output) throws IOException {
        if (plan == null || !plan.isObject()) throw new IllegalArgumentException("JDBC SQL 计划必须是对象");
        if (output == null) throw new IllegalArgumentException("output 不能为空");
        String stepId = required(plan, "stepId");
        String sql = required(plan, "sql");
        String type = required(plan, "databaseType");
        String jdbcUrl = jdbcUrl(plan);
        String passwordExpression = secrets == null ? required(plan, "credentialRef")
                : secrets.materialize(TextNode.valueOf(required(plan, "credentialRef"))).asText();
        List<String> argumentNames = new ArrayList<>();
        String preparedSql = prepareSql(sql, argumentNames);
        Map<String, String> variables = variables(plan.path("variableScopes"), extractedOverride);

        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        ListedHashTree root = new ListedHashTree();
        TestPlan testPlan = JmeterComponentMapper.configure(
                new TestPlan("Platform JDBC Test Plan [" + stepId + "]"), "TestPlanGui", "TestPlan");
        testPlan.setName("Platform JDBC Test Plan [" + stepId + "]");
        testPlan.setUserDefinedVariables(components.userVariables(variables));
        ListedHashTree planTree = root.add(testPlan);
        LoopController loop = JmeterComponentMapper.configure(new LoopController(), "LoopControlPanel", "LoopController");
        loop.setName("Single Iteration [" + stepId + "]");
        loop.setLoops(1);
        loop.setFirst(true);
        ThreadGroup threadGroup = JmeterComponentMapper.configure(new ThreadGroup(), "ThreadGroupGui", "ThreadGroup");
        threadGroup.setName("Single Thread Group [" + stepId + "]");
        threadGroup.setNumThreads(1);
        threadGroup.setRampUp(1);
        threadGroup.setSamplerController(loop);
        ListedHashTree threadTree = planTree.add(threadGroup);
        String password = passwordExpression;
        threadTree.add(components.jdbcDataSource(stepId, JDBC_DATA_SOURCE, type, jdbcUrl,
                required(plan, "username"), password));
        threadTree.add(components.jdbcSampler(stepId, JDBC_DATA_SOURCE, preparedSql,
                !"SELECT".equals(firstKeyword(sql)), argumentNames.stream().map(name -> "${" + name + "}").toList()));
        try (OutputStream stream = Files.newOutputStream(output)) {
            SaveService.saveTree(root, stream);
        }
        return output;
    }

    /** 生成平台自研 Redis Sampler 的受控 JMX 产物；运行主链仍由 RedisCommandExecutor 保证密钥隔离。 */
    public Path compileRedis(JsonNode plan, Path output) throws IOException {
        if (plan == null || !plan.isObject()) throw new IllegalArgumentException("Redis 计划必须是对象");
        String stepId = required(plan, "stepId");
        String command = required(plan, "command").toUpperCase(Locale.ROOT);
        if (!List.of("GET", "SET", "DEL", "EXISTS").contains(command)) throw new IllegalArgumentException("Redis 命令不在白名单");
        if ((command.equals("SET") || command.equals("DEL")) && !(plan.path("allowWrite").asBoolean(false) && plan.path("confirmed").asBoolean(false))) throw new IllegalArgumentException("Redis 写操作未完成二次确认");
        Path parent = output.toAbsolutePath().getParent(); if (parent != null) Files.createDirectories(parent);
        ListedHashTree root = new ListedHashTree();
        TestPlan testPlan = JmeterComponentMapper.configure(new TestPlan("Platform Redis Test Plan [" + stepId + "]"), "TestPlanGui", "TestPlan");
        testPlan.setName("Platform Redis Test Plan [" + stepId + "]");
        ListedHashTree planTree = root.add(testPlan);
        LoopController loop = JmeterComponentMapper.configure(new LoopController(), "LoopControlPanel", "LoopController");
        loop.setName("Single Iteration [" + stepId + "]"); loop.setLoops(1); loop.setFirst(true);
        ThreadGroup threadGroup = JmeterComponentMapper.configure(new ThreadGroup(), "ThreadGroupGui", "ThreadGroup");
        threadGroup.setName("Single Thread Group [" + stepId + "]"); threadGroup.setNumThreads(1); threadGroup.setRampUp(1); threadGroup.setSamplerController(loop);
        ListedHashTree threadTree = planTree.add(threadGroup); threadTree.add(components.redisSampler(stepId, plan));
        try (OutputStream stream = Files.newOutputStream(output)) { SaveService.saveTree(root, stream); }
        return output;
    }

    /** 生成受控控制流 JMX；不添加脚本、函数或用户可注入的任意组件。 */
    public Path compileControlFlow(JsonNode plan, Path output) throws IOException {
        if (plan == null || !plan.isObject()) throw new IllegalArgumentException("控制流计划必须是对象");
        String kind = required(plan, "kind").toUpperCase(Locale.ROOT);
        String stepId = required(plan, "stepId");
        ListedHashTree root = new ListedHashTree();
        TestPlan testPlan = JmeterComponentMapper.configure(
                new TestPlan("Platform Control Flow [" + stepId + "]"), "TestPlanGui", "TestPlan");
        testPlan.setName("Platform Control Flow [" + stepId + "]");
        ListedHashTree planTree = root.add(testPlan);
        LoopController outer = JmeterComponentMapper.configure(new LoopController(), "LoopControlPanel", "LoopController");
        outer.setName("Single Iteration [" + stepId + "]"); outer.setLoops(1); outer.setFirst(true);
        org.apache.jmeter.threads.ThreadGroup group = JmeterComponentMapper.configure(
                new org.apache.jmeter.threads.ThreadGroup(), "ThreadGroupGui", "ThreadGroup");
        group.setName("Control Flow Thread Group [" + stepId + "]"); group.setNumThreads(1); group.setRampUp(1); group.setSamplerController(outer);
        ListedHashTree groupTree = planTree.add(group);
        switch (kind) {
            case "CONDITION" -> {
                IfController controller = JmeterComponentMapper.configure(new IfController(), "IfControllerGui", "IfController");
                controller.setName("Condition [" + stepId + "]"); controller.setCondition(conditionExpression(plan)); controller.setUseExpression(true); controller.setEvaluateAll(true);
                groupTree.add(controller);
            }
            case "LOOP" -> {
                String mode = required(plan, "mode").toUpperCase(Locale.ROOT);
                if ("FIXED".equals(mode)) {
                    int count = plan.path("count").asInt(0); if (count < 1 || count > 1000) throw new IllegalArgumentException("固定循环次数不合法");
                    org.apache.jmeter.control.LoopController controller = JmeterComponentMapper.configure(new org.apache.jmeter.control.LoopController(), "LoopControlPanel", "LoopController");
                    controller.setName("Fixed Loop [" + stepId + "]"); controller.setLoops(count); controller.setFirst(true); groupTree.add(controller);
                } else if ("LIST".equals(mode)) {
                    ForeachController controller = JmeterComponentMapper.configure(new ForeachController(), "ForeachControlPanel", "ForeachController");
                    String items = required(plan, "items"); String itemVariable = required(plan, "itemVariable");
                    safeControlTemplate(items); safeControlTemplate(itemVariable);
                    controller.setName("List Loop [" + stepId + "]"); controller.setInputVal(items); controller.setReturnVal(itemVariable); controller.setStartIndex("0"); controller.setEndIndex("-1"); controller.setUseSeparator(true); groupTree.add(controller);
                } else if ("WHILE".equals(mode)) {
                    WhileController controller = JmeterComponentMapper.configure(new WhileController(), "WhileControllerGui", "WhileController");
                    controller.setName("While Loop [" + stepId + "]"); controller.setCondition(conditionExpression(plan)); groupTree.add(controller);
                } else {
                    throw new IllegalArgumentException("循环模式不支持: " + mode);
                }
            }
            default -> throw new IllegalArgumentException("控制流类型不支持: " + kind);
        }
        try (OutputStream stream = Files.newOutputStream(output)) { SaveService.saveTree(root, stream); }
        return output;
    }

    private static String conditionExpression(JsonNode plan) {
        String operator = required(plan, "operator").toUpperCase(Locale.ROOT);
        String left = required(plan, "left");
        safeControlTemplate(left);
        if ("EXISTS".equals(operator)) return left;
        if ("NOT_EXISTS".equals(operator)) return "";
        String right = plan.path("right").asText("");
        safeControlTemplate(right);
        return switch (operator) {
            case "EQUALS" -> left + " == " + right;
            case "NOT_EQUALS" -> left + " != " + right;
            case "CONTAINS" -> left + " contains " + right;
            case "NOT_CONTAINS" -> left + " not_contains " + right;
            case "GREATER_THAN" -> left + " > " + right;
            case "LESS_THAN" -> left + " < " + right;
            default -> throw new IllegalArgumentException("条件运算符不支持: " + operator);
        };
    }

    private static void safeControlTemplate(String value) {
        var matcher = CONTROL_VARIABLE.matcher(value);
        while (matcher.find()) {
            if (!CONTROL_VARIABLE_NAME.matcher(matcher.group(1)).matches()) {
                throw new IllegalArgumentException("控制流 JMX 不允许 JMeter 函数或脚本");
            }
        }
        if (CONTROL_VARIABLE.matcher(value).replaceAll("").contains("${")) {
            throw new IllegalArgumentException("控制流变量表达式格式不正确");
        }
    }

    private static String prepareSql(String sql, List<String> names) {
        Matcher matcher = VARIABLE.matcher(sql);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            names.add(matcher.group(1));
            matcher.appendReplacement(result, "?");
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static Map<String, String> variables(JsonNode scopes, Map<String, JsonNode> extractedOverride) {
        Map<String, String> values = new LinkedHashMap<>();
        if (extractedOverride != null) extractedOverride.forEach((name, value) -> values.put(name, scalar(value)));
        collect(scopes, "extracted", values);
        collect(scopes, "dataRow", values);
        collect(scopes, "caseVariables", values);
        collect(scopes, "scenario", values);
        collect(scopes, "environment", values);
        return values;
    }

    private static void collect(JsonNode scopes, String name, Map<String, String> values) {
        JsonNode scope = scopes == null ? null : scopes.get(name);
        if (scope != null && scope.isObject()) scope.fields().forEachRemaining(entry -> values.putIfAbsent(entry.getKey(), scalar(entry.getValue())));
    }

    private static String scalar(JsonNode value) {
        return value == null || value.isNull() ? "" : value.isTextual() ? value.textValue() : value.toString();
    }

    private static String jdbcUrl(JsonNode plan) {
        String type = required(plan, "databaseType");
        String scheme = "MYSQL".equalsIgnoreCase(type) ? "jdbc:mysql" : "jdbc:postgresql";
        return scheme + "://" + required(plan, "host") + ":" + plan.path("port").asInt() + "/" + required(plan, "databaseName");
    }

    private static String firstKeyword(String sql) {
        String value = sql.replaceAll("(?s)^\\s*(--[^\\n]*(\\n|$)|/\\*.*?\\*/\\s*)+", "").strip();
        int index = 0;
        while (index < value.length() && Character.isLetter(value.charAt(index))) index++;
        return value.substring(0, index).toUpperCase();
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("").strip();
        if (value.isBlank()) throw new IllegalArgumentException("JDBC SQL 计划缺少 " + field);
        return value;
    }

    private static synchronized void initializeJmeterProperties() {
        if (jmeterPropertiesInitialized) {
            return;
        }
        try {
            Path saveServiceProperties = resolveSaveServiceProperties();
            Path properties = Files.createTempFile("autotest-jmeter-", ".properties");
            properties.toFile().deleteOnExit();
            Files.writeString(properties,
                    "language=en\nfile_encoding=UTF-8\nsaveservice_properties="
                            + saveServiceProperties.toString().replace(java.io.File.separatorChar, '/') + "\n",
                    StandardCharsets.UTF_8);
            JMeterUtils.loadJMeterProperties(properties.toString());
            JMeterUtils.setLocale(Locale.ENGLISH);
        } catch (IOException exception) {
            throw new IllegalStateException("无法初始化 JMeter 属性", exception);
        }
        JMeterUtils.setJMeterHome(configuredJmeterHome());
        jmeterPropertiesInitialized = true;
    }

    private static String configuredJmeterHome() {
        String configuredHome = System.getenv("JMETER_HOME");
        if (configuredHome == null || configuredHome.isBlank()) {
            configuredHome = System.getProperty("jmeter.home");
        }
        return configuredHome == null || configuredHome.isBlank()
                ? System.getProperty("user.dir")
                : configuredHome;
    }

    private static Path resolveSaveServiceProperties() throws IOException {
        String configuredHome = configuredJmeterHome();
        if (configuredHome != null && !configuredHome.isBlank()) {
            Path candidate = Path.of(configuredHome, "bin", "saveservice.properties");
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath();
            }
        }

        InputStream resource = JmeterPlanCompiler.class.getResourceAsStream("/jmeter/saveservice.properties");
        if (resource == null) {
            resource = JmeterPlanCompiler.class.getResourceAsStream("/saveservice.properties");
        }
        try (InputStream saveServiceResource = resource) {
            if (saveServiceResource == null) {
                throw new IOException("缺少 JMeter 5.6.3 saveservice.properties");
            }
            Path fallback = Files.createTempFile("autotest-saveservice-", ".properties");
            fallback.toFile().deleteOnExit();
            Files.copy(saveServiceResource, fallback, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return fallback;
        }
    }
}

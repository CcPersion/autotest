package com.autotest.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.apache.jmeter.assertions.JSONPathAssertion;
import org.apache.jmeter.assertions.ResponseAssertion;
import org.apache.jmeter.assertions.DurationAssertion;
import org.apache.jmeter.assertions.XPath2Assertion;
import org.apache.jmeter.assertions.jmespath.JMESPathAssertion;
import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.config.KeystoreConfig;
import org.apache.jmeter.protocol.http.control.Cookie;
import org.apache.jmeter.protocol.http.control.CookieManager;
import org.apache.jmeter.protocol.http.control.Header;
import org.apache.jmeter.protocol.http.control.HeaderManager;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;
import org.apache.jmeter.protocol.http.util.HTTPArgument;
import org.apache.jmeter.protocol.http.util.HTTPFileArg;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.extractor.json.jsonpath.JSONPostProcessor;
import org.apache.jmeter.extractor.json.jmespath.JMESPathExtractor;
import org.apache.jmeter.extractor.RegexExtractor;
import org.apache.jmeter.extractor.XPath2Extractor;
import org.apache.jmeter.protocol.jdbc.config.DataSourceElement;
import org.apache.jmeter.protocol.jdbc.sampler.JDBCSampler;
import org.apache.jmeter.testbeans.TestBeanHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 将平台单接口结构映射为固定的 JMeter 白名单组件。 */
final class JmeterComponentMapper {

    private static final String JDBC_SELECT = "Prepared Select Statement";
    private static final String JDBC_UPDATE = "Prepared Update Statement";

    DataSourceElement jdbcDataSource(String stepId, String dataSourceName, String databaseType,
                                     String jdbcUrl, String username, String passwordExpression) {
        DataSourceElement element = configure(new DataSourceElement(), "TestBeanGUI", DataSourceElement.class.getName());
        element.setName("JDBC Data Source [" + stepId + "]");
        element.setDataSource(dataSourceName);
        element.setDbUrl(jdbcUrl);
        element.setDriver("MYSQL".equalsIgnoreCase(databaseType)
                ? "com.mysql.cj.jdbc.Driver" : "org.postgresql.Driver");
        element.setUsername(username);
        element.setPassword(passwordExpression);
        element.setPoolMax("1");
        element.setTimeout("10000");
        element.setTrimInterval("60000");
        element.setAutocommit(true);
        element.setPreinit(true);
        element.setKeepAlive(true);
        element.setConnectionAge("5000");
        element.setCheckQuery("SELECT 1");
        element.setTransactionIsolation("DEFAULT");
        element.setPoolPreparedStatements("-1");
        element.setInitQuery("");
        element.setConnectionProperties("");
        element.setProperty("dataSource", dataSourceName);
        element.setProperty("dbUrl", jdbcUrl);
        element.setProperty("driver", "MYSQL".equalsIgnoreCase(databaseType)
                ? "com.mysql.cj.jdbc.Driver" : "org.postgresql.Driver");
        element.setProperty("username", username);
        element.setProperty("password", passwordExpression);
        element.setProperty("poolMax", "1");
        element.setProperty("timeout", "10000");
        element.setProperty("trimInterval", "60000");
        element.setProperty("autocommit", true);
        element.setProperty("preinit", true);
        element.setProperty("keepAlive", true);
        element.setProperty("connectionAge", "5000");
        element.setProperty("checkQuery", "SELECT 1");
        element.setProperty("transactionIsolation", "DEFAULT");
        element.setProperty("poolPreparedStatements", "-1");
        element.setProperty("initQuery", "");
        element.setProperty("connectionProperties", "");
        TestBeanHelper.prepare(element);
        return element;
    }

    JDBCSampler jdbcSampler(String stepId, String dataSourceName, String sql,
                            boolean write, List<String> arguments) {
        JDBCSampler sampler = configure(new JDBCSampler(), "TestBeanGUI", JDBCSampler.class.getName());
        sampler.setName("JDBC SQL [" + stepId + "]");
        sampler.setDataSource(dataSourceName);
        sampler.setQuery(sql);
        sampler.setQueryType(write ? JDBC_UPDATE : JDBC_SELECT);
        sampler.setQueryArguments(String.join(",", arguments));
        sampler.setQueryArgumentsTypes(arguments.stream().map(ignored -> "VARCHAR")
                .reduce((left, right) -> left + "," + right).orElse(""));
        sampler.setVariableNames("");
        sampler.setResultVariable("autotest_sql_result");
        sampler.setResultSetHandler("Store as String");
        sampler.setQueryTimeout("0");
        sampler.setResultSetMaxRows("0");
        sampler.setProperty("dataSource", dataSourceName);
        sampler.setProperty("query", sql);
        sampler.setProperty("queryType", write ? JDBC_UPDATE : JDBC_SELECT);
        sampler.setProperty("queryArguments", String.join(",", arguments));
        sampler.setProperty("queryArgumentsTypes", arguments.stream().map(ignored -> "VARCHAR")
                .reduce((left, right) -> left + "," + right).orElse(""));
        sampler.setProperty("variableNames", "");
        sampler.setProperty("resultVariable", "autotest_sql_result");
        sampler.setProperty("resultSetHandler", "Store as String");
        sampler.setProperty("queryTimeout", "0");
        sampler.setProperty("resultSetMaxRows", "0");
        TestBeanHelper.prepare(sampler);
        return sampler;
    }

    RedisSampler redisSampler(String stepId, JsonNode plan) {
        RedisSampler sampler = configure(new RedisSampler(), "TestBeanGUI", RedisSampler.class.getName());
        sampler.setName("Redis " + plan.path("command").asText("COMMAND") + " [" + stepId + "]");
        sampler.setProperty(RedisSampler.HOST, plan.path("host").asText());
        sampler.setProperty(RedisSampler.PORT, plan.path("port").asInt());
        sampler.setProperty(RedisSampler.DATABASE, plan.path("databaseNumber").asInt(0));
        sampler.setProperty(RedisSampler.USERNAME, plan.path("username").asText(""));
        sampler.setProperty(RedisSampler.PASSWORD, "${__P(redis.password,)}");
        sampler.setProperty(RedisSampler.TLS, plan.path("options").path("tls").asBoolean(false));
        sampler.setProperty(RedisSampler.COMMAND, plan.path("command").asText());
        sampler.setProperty(RedisSampler.KEY, plan.path("key").asText());
        sampler.setProperty(RedisSampler.VALUE, plan.path("value").asText(""));
        sampler.setProperty(RedisSampler.ALLOW_WRITE, plan.path("allowWrite").asBoolean(false));
        sampler.setProperty(RedisSampler.CONFIRMED, plan.path("confirmed").asBoolean(false));
        return sampler;
    }

    HTTPSamplerBase httpSampler(JmeterPlan plan) {
        GuardedHttpSampler sampler = new GuardedHttpSampler();
        configure(sampler, "HttpTestSampleGui", "GuardedHttpSampler");
        sampler.setName("HTTP Request [" + plan.stepId() + "]");
        sampler.setMethod(plan.method());
        sampler.setFollowRedirects(plan.followRedirects());
        sampler.setAutoRedirects(false);
        sampler.setProperty(GuardedHttpSampler.TARGET_ALLOWLIST,
                String.join("\n", plan.targetAllowlist()));
        sampler.setProperty(GuardedHttpSampler.TARGET_POLICY_REQUIRED, plan.targetPolicyRequired());
        sampler.setProperty(GuardedHttpSampler.TARGET_DNS_REQUIRED, plan.targetDnsRequired());
        // The component is replaced by GuardedHttpSampler with the run-local
        // PinnedDnsCacheManager immediately before HTTPHC4Impl construction.
        // Keeping an explicit resolver property in the JMX prevents the
        // sampler from silently relying on JMeter's global DNS configuration.
        if (plan.targetDnsRequired()) {
            sampler.setDNSResolver(new org.apache.jmeter.protocol.http.control.DNSCacheManager());
        } else {
            sampler.setDNSResolver(null);
        }
        sampler.setUseKeepAlive(false);
        sampler.setPostBodyRaw(plan.body().type().equals("JSON") || plan.body().type().equals("TEXT"));
        if (plan.clientCertificate() != null) {
            KeystoreConfig keystore = configure(new KeystoreConfig(), "KeystoreConfigGui", "KeystoreConfig");
            keystore.setStartIndex("0");
            keystore.setEndIndex("-1");
            keystore.setPreload("true");
            sampler.setKeystoreConfig(keystore);
        }
        if (plan.connectTimeoutMillis() > 0) {
            sampler.setConnectTimeout(Integer.toString(plan.connectTimeoutMillis()));
        }
        if (plan.responseTimeoutMillis() > 0) {
            sampler.setResponseTimeout(Integer.toString(plan.responseTimeoutMillis()));
        }
        if (plan.proxy() != null) {
            sampler.setProxyScheme(plan.proxy().scheme());
            sampler.setProxyHost(plan.proxy().host());
            sampler.setProxyPortInt(Integer.toString(plan.proxy().port()));
            if (!plan.proxy().capability().isBlank()) {
                // JMeter emits these credentials as Proxy-Authorization only;
                // the controlled proxy validates the signed, run-bound token
                // and never forwards it to the target.
                sampler.setProxyUser("__autotest_capability__");
                sampler.setProxyPass(plan.proxy().capability());
            } else if (plan.proxy().authenticated()) {
                sampler.setProxyUser(plan.proxy().username());
                sampler.setProxyPass(plan.proxy().password());
            }
        }

        java.net.URI base = java.net.URI.create(plan.baseUrl());
        sampler.setProtocol(base.getScheme());
        sampler.setDomain(base.getHost());
        if (base.getPort() >= 0) {
            sampler.setPort(base.getPort());
        }
        sampler.setPath(joinPath(base.getPath(), plan.urlTemplate()));

        Arguments query = new Arguments();
        for (JmeterParameter parameter : plan.query()) {
            if (parameter.enabled()) {
                HTTPArgument argument = new HTTPArgument(parameter.name(), parameter.value(), true);
                argument.setAlwaysEncoded(true);
                query.addArgument(argument);
            }
        }
        addBodyArguments(sampler, query, plan.body());
        sampler.setArguments(query);
        return sampler;
    }

    CookieManager cookies(JmeterPlan plan) {
        CookieManager manager = new CookieManager();
        configure(manager, "CookiePanel", "CookieManager");
        manager.setName("Cookie Manager [" + plan.stepId() + "]");
        manager.setClearEachIteration(false);
        for (JmeterCookie input : plan.cookies()) {
            if (!input.enabled()) {
                continue;
            }
            java.net.URI base = java.net.URI.create(plan.baseUrl());
            String domain = input.domain().isBlank() ? base.getHost() : input.domain();
            manager.add(new Cookie(input.name(), input.value(), domain, input.path(), input.secure(), 0L));
        }
        return manager;
    }

    HeaderManager headers(JmeterPlan plan) {
        HeaderManager manager = new HeaderManager();
        configure(manager, "HeaderPanel", "HeaderManager");
        manager.setName("Header Manager [" + plan.stepId() + "]");
        for (JmeterParameter parameter : plan.headers()) {
            if (parameter.enabled()) {
                manager.add(new Header(parameter.name(), parameter.value()));
            }
        }
        return manager;
    }

    org.apache.jmeter.testelement.TestElement assertion(JmeterAssertion input, String stepId) {
        return switch (input.type()) {
            case "STATUS" -> statusAssertion(input, stepId);
            case "JSON_PATH" -> numericComparison(input)
                    ? controlledAssertion(input, stepId) : jsonPathAssertion(input, stepId);
            case "JMES_PATH" -> numericComparison(input)
                    ? controlledAssertion(input, stepId) : jmesPathAssertion(input, stepId);
            case "XPATH" -> xpathAssertion(input, stepId);
            case "BODY" -> responseAssertion(input, stepId);
            case "HEADER", "COOKIE" -> targetedHeaderCookie(input)
                    ? controlledAssertion(input, stepId) : responseAssertion(input, stepId);
            case "SCHEMA" -> schemaAssertion(input, stepId);
            case "RESPONSE_TIME" -> durationAssertion(input, stepId);
            case "VARIABLE" -> controlledAssertion(input, stepId);
            default -> throw new IllegalArgumentException("不支持的断言类型: " + input.type());
        };
    }

    private static boolean numericComparison(JmeterAssertion input) {
        return "GREATER_THAN".equals(input.operator()) || "LESS_THAN".equals(input.operator());
    }

    private static boolean targetedHeaderCookie(JmeterAssertion input) {
        return input.expression() != null && !input.expression().isBlank();
    }

    JmeterControlledAssertion controlledAssertion(JmeterAssertion input, String stepId) {
        JmeterControlledAssertion assertion = JmeterComponentMapper.configure(
                new JmeterControlledAssertion(), "AssertionGui", "JmeterControlledAssertion");
        assertion.setName(input.type() + " Assertion [" + stepId + "]");
        assertion.setRule(input);
        return assertion;
    }

    ResponseAssertion statusAssertion(JmeterAssertion input, String stepId) {
        ResponseAssertion assertion = new ResponseAssertion();
        configure(assertion, "AssertionGui", "ResponseAssertion");
        assertion.setName("Status Assertion [" + stepId + "]");
        assertion.setTestFieldResponseCode();
        assertion.setToEqualsType();
        assertion.addTestString(input.expected().asText());
        return assertion;
    }

    JSONPathAssertion jsonPathAssertion(JmeterAssertion input, String stepId) {
        JSONPathAssertion assertion = new JSONPathAssertion();
        configure(assertion, "JSONPathAssertionGui", "JSONPathAssertion");
        assertion.setName("JSONPath Assertion [" + stepId + "]");
        assertion.setJsonPath(input.expression());
        assertion.setIsRegex(false);
        if (input.operator().equals("EXISTS") || input.operator().equals("NOT_EXISTS")) {
            assertion.setJsonValidationBool(false);
            assertion.setInvert(input.operator().equals("NOT_EXISTS"));
        } else {
            assertion.setJsonValidationBool(true);
            assertion.setInvert(input.operator().equals("NOT_EQUALS") || input.operator().equals("NOT_CONTAINS"));
            if (input.operator().equals("CONTAINS") || input.operator().equals("NOT_CONTAINS")) {
                assertion.setIsRegex(true);
                assertion.setExpectedValue(".*" + java.util.regex.Pattern.quote(input.expected().asText()) + ".*");
            } else {
                assertion.setExpectedValue(input.expected().toString());
            }
        }
        return assertion;
    }

    JMESPathAssertion jmesPathAssertion(JmeterAssertion input, String stepId) {
        JMESPathAssertion assertion = new JMESPathAssertion();
        configure(assertion, "JMESPathAssertionGui", "JMESPathAssertion");
        assertion.setName("JMESPath Assertion [" + stepId + "]");
        assertion.setJmesPath(input.expression());
        if (input.operator().equals("EXISTS") || input.operator().equals("NOT_EXISTS")) {
            assertion.setJsonValidationBool(false);
            assertion.setInvert(input.operator().equals("NOT_EXISTS"));
        } else {
            assertion.setJsonValidationBool(true);
            assertion.setInvert(input.operator().equals("NOT_EQUALS") || input.operator().equals("NOT_CONTAINS"));
            if (input.operator().equals("CONTAINS") || input.operator().equals("NOT_CONTAINS")) {
                assertion.setIsRegex(true);
                assertion.setExpectedValue(".*" + java.util.regex.Pattern.quote(input.expected().asText()) + ".*");
            } else {
                assertion.setExpectedValue(input.expected().toString());
            }
        }
        return assertion;
    }

    XPath2Assertion xpathAssertion(JmeterAssertion input, String stepId) {
        XPath2Assertion assertion = new XPath2Assertion();
        configure(assertion, "XPath2AssertionGui", "XPath2Assertion");
        assertion.setName("XPath Assertion [" + stepId + "]");
        assertion.setXPathString(input.expression());
        assertion.setNegated(input.operator().equals("NOT_EXISTS"));
        return assertion;
    }

    ResponseAssertion responseAssertion(JmeterAssertion input, String stepId) {
        ResponseAssertion assertion = new ResponseAssertion();
        configure(assertion, "AssertionGui", "ResponseAssertion");
        assertion.setName(input.type() + " Assertion [" + stepId + "]");
        if (input.type().equals("HEADER") || input.type().equals("COOKIE")) {
            assertion.setTestFieldResponseHeaders();
        } else {
            assertion.setTestFieldResponseData();
        }
        switch (input.operator()) {
            case "EQUALS" -> assertion.setToEqualsType();
            case "CONTAINS" -> assertion.setToContainsType();
            case "NOT_CONTAINS" -> {
                assertion.setToContainsType();
                assertion.setToNotType();
            }
            case "MATCHES" -> assertion.setToMatchType();
            default -> throw new IllegalArgumentException("不支持的响应断言操作符: " + input.operator());
        }
        assertion.addTestString(input.expected().isTextual()
                ? input.expected().textValue() : input.expected().toString());
        return assertion;
    }

    DurationAssertion durationAssertion(JmeterAssertion input, String stepId) {
        DurationAssertion assertion = new DurationAssertion();
        configure(assertion, "DurationAssertionGui", "DurationAssertion");
        assertion.setName("Response Time Assertion [" + stepId + "]");
        assertion.setAllowedDuration(input.expected().asLong());
        return assertion;
    }

    JmeterJsonSchemaAssertion schemaAssertion(JmeterAssertion input, String stepId) {
        JmeterJsonSchemaAssertion assertion = JmeterComponentMapper.configure(
                new JmeterJsonSchemaAssertion(), "JSONSchemaAssertionGui", "JmeterJsonSchemaAssertion");
        assertion.setName("JSON Schema Assertion [" + stepId + "]");
        assertion.setSchema(input.expected().toString());
        return assertion;
    }

    org.apache.jmeter.testelement.TestElement extractor(JmeterExtractor input, String stepId) {
        return switch (input.type()) {
            case "JSON_PATH" -> jsonPathExtractor(input, stepId);
            case "JMESPATH" -> jmesPathExtractor(input, stepId);
            case "XPATH" -> xpathExtractor(input, stepId);
            case "REGEX", "HEADER", "COOKIE" -> regexExtractor(input, stepId);
            default -> throw new IllegalArgumentException("不支持的提取器类型: " + input.type());
        };
    }

    org.apache.jmeter.testelement.TestElement extractorPresenceAssertion(JmeterExtractor input, String stepId) {
        org.apache.jmeter.testelement.TestElement assertion = switch (input.type()) {
            case "JSON_PATH" -> jsonPathAssertion(
                    new JmeterAssertion("JSON_PATH", "EXISTS", input.expression(), null), stepId);
            case "JMESPATH" -> jmesPathAssertion(
                    new JmeterAssertion("JMES_PATH", "EXISTS", input.expression(), null), stepId);
            case "XPATH" -> xpathAssertion(
                    new JmeterAssertion("XPATH", "EXISTS", input.expression(), null), stepId);
            case "REGEX" -> regexPresenceAssertion(input, stepId);
            case "HEADER", "COOKIE" -> presenceAssertion(input, stepId);
            default -> throw new IllegalArgumentException("不支持的提取器类型: " + input.type());
        };
        assertion.setName("Extractor Presence [" + stepId + "] " + input.type());
        return assertion;
    }

    private ResponseAssertion regexPresenceAssertion(JmeterExtractor input, String stepId) {
        ResponseAssertion assertion = new ResponseAssertion();
        configure(assertion, "AssertionGui", "ResponseAssertion");
        assertion.setName("Extractor Presence [" + stepId + "] REGEX");
        assertion.setTestFieldResponseData();
        assertion.setToMatchType();
        assertion.addTestString("(?s).*" + input.expression() + ".*");
        return assertion;
    }

    JmeterExtractionReporter extractionReporter(List<JmeterExtractor> inputs, String stepId) {
        JmeterExtractionReporter reporter = JmeterComponentMapper.configure(
                new JmeterExtractionReporter(inputs), "TestBeanGUI", "JmeterExtractionReporter");
        reporter.setName("Extraction Results [" + stepId + "]");
        return reporter;
    }

    JmeterHeaderCookiePresenceAssertion presenceAssertion(JmeterExtractor input, String stepId) {
        JmeterHeaderCookiePresenceAssertion assertion = JmeterComponentMapper.configure(
                new JmeterHeaderCookiePresenceAssertion(), "AssertionGui", "JmeterHeaderCookiePresenceAssertion");
        assertion.setName("Extractor Presence [" + stepId + "] " + input.type());
        assertion.setKind(input.type());
        assertion.setTargetName(input.expression());
        return assertion;
    }

    JSONPostProcessor jsonPathExtractor(JmeterExtractor input, String stepId) {
        JSONPostProcessor extractor = new JSONPostProcessor();
        configure(extractor, "JSONPostProcessorGui", "JSONPostProcessor");
        extractor.setName("JSONPath Extractor [" + stepId + "]");
        extractor.setJsonPathExpressions(input.expression());
        extractor.setRefNames(input.variable());
        if (input.defaultConfigured() && input.defaultValue() != null) {
            extractor.setDefaultValues(input.defaultValue());
        }
        extractor.setMatchNumbers("1");
        extractor.setComputeConcatenation(false);
        return extractor;
    }

    JMESPathExtractor jmesPathExtractor(JmeterExtractor input, String stepId) {
        JMESPathExtractor extractor = new JMESPathExtractor();
        configure(extractor, "JMESPathExtractorGui", "JMESPathExtractor");
        extractor.setName("JMESPath Extractor [" + stepId + "]");
        extractor.setJmesPathExpression(input.expression());
        extractor.setRefName(input.variable());
        if (input.defaultConfigured() && input.defaultValue() != null) {
            extractor.setDefaultValue(input.defaultValue());
        }
        extractor.setMatchNumber("1");
        return extractor;
    }

    XPath2Extractor xpathExtractor(JmeterExtractor input, String stepId) {
        XPath2Extractor extractor = new XPath2Extractor();
        configure(extractor, "XPath2ExtractorGui", "XPath2Extractor");
        extractor.setName("XPath Extractor [" + stepId + "]");
        extractor.setXPathQuery(input.expression());
        extractor.setRefName(input.variable());
        if (input.defaultConfigured() && input.defaultValue() != null) {
            extractor.setDefaultValue(input.defaultValue());
        }
        extractor.setMatchNumber("1");
        extractor.setFragment(false);
        return extractor;
    }

    RegexExtractor regexExtractor(JmeterExtractor input, String stepId) {
        RegexExtractor extractor = new RegexExtractor();
        configure(extractor, "RegexExtractorGui", "RegexExtractor");
        extractor.setName(input.type() + " Extractor [" + stepId + "]");
        extractor.setRegex(input.type().equals("HEADER")
                ? headerPattern(input.expression())
                : input.type().equals("COOKIE") ? cookiePattern(input.expression()) : input.expression());
        extractor.setRefName(input.variable());
        extractor.setTemplate("$1$");
        extractor.setMatchNumber("1");
        if (input.defaultConfigured() && input.defaultValue() != null) {
            extractor.setDefaultValue(input.defaultValue());
        }
        extractor.setUseField(input.type().equals("REGEX") ? RegexExtractor.USE_BODY : RegexExtractor.USE_HDRS);
        return extractor;
    }

    private static String headerPattern(String name) {
        return "(?im)^" + java.util.regex.Pattern.quote(name) + "\\s*:\\s*([^\\r\\n]*)";
    }

    private static String cookiePattern(String name) {
        return "(?im)^Set-Cookie:\\s*" + java.util.regex.Pattern.quote(name) + "=([^;\\r\\n]*)";
    }

    Arguments userVariables(Map<String, String> variables) {
        Arguments arguments = new Arguments();
        configure(arguments, "ArgumentsPanel", "Arguments");
        arguments.setName("Platform Variables");
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            arguments.addArgument(new org.apache.jmeter.config.Argument(entry.getKey(), entry.getValue()));
        }
        return arguments;
    }

    static <T extends TestElement> T configure(T element, String guiClass, String testClass) {
        element.setProperty(TestElement.GUI_CLASS, guiClass);
        element.setProperty(TestElement.TEST_CLASS, testClass);
        element.setEnabled(true);
        return element;
    }

    private static void addBodyArguments(HTTPSamplerBase sampler, Arguments arguments, JmeterBody body) {
        switch (body.type()) {
            case "NONE" -> {
                // no request body
            }
            case "JSON", "TEXT" -> {
                // HTTPArgument 的三参数构造器会把 alwaysEncoded 固定为 true；原始 JSON/TEXT
                // 必须显式关闭 URL 编码，否则花括号、引号和密钥函数会被编码成非法请求体。
                HTTPArgument argument = new HTTPArgument("", bodyText(body), "", false);
                argument.setAlwaysEncoded(false);
                arguments.addArgument(argument);
            }
            case "URLENCODED" -> {
                if (!body.value().isArray()) {
                    throw new IllegalArgumentException("URLENCODED Body 必须是参数数组");
                }
                for (var item : body.value()) {
                    if (item.path("enabled").asBoolean(true)) {
                        String name = requiredText(item, "name");
                        var value = item.get("value");
                        if (value == null || !value.isTextual()) {
                            throw new IllegalArgumentException("Body 部分 value 必须是字符串");
                        }
                        HTTPArgument argument = new HTTPArgument(name, value.textValue(), true);
                        argument.setAlwaysEncoded(true);
                        arguments.addArgument(argument);
                    }
                }
            }
            case "MULTIPART" -> {
                if (!body.value().isObject() && !body.value().isArray()) {
                    throw new IllegalArgumentException("MULTIPART Body 必须是对象或数组");
                }
                Iterable<com.fasterxml.jackson.databind.JsonNode> fields = body.value().isArray()
                        ? body.value() : body.value().path("fields");
                List<com.fasterxml.jackson.databind.JsonNode> fileItems = new ArrayList<>();
                for (var item : fields) {
                    if (!item.path("enabled").asBoolean(true)) continue;
                    if ("FILE".equalsIgnoreCase(item.path("kind").asText())) {
                        fileItems.add(item);
                        continue;
                    }
                    HTTPArgument argument = new HTTPArgument(requiredText(item, "name"),
                            requiredText(item, "value"), true);
                    argument.setAlwaysEncoded(true);
                    arguments.addArgument(argument);
                }
                List<HTTPFileArg> files = new ArrayList<>();
                Iterable<com.fasterxml.jackson.databind.JsonNode> configuredFiles = body.value().isArray()
                        ? fileItems : body.value().path("files");
                for (var item : configuredFiles) {
                    if (!item.path("enabled").asBoolean(true)) {
                        continue;
                    }
                    String path = requiredText(item, "path");
                    String name = requiredText(item, "name");
                    if (path.contains("..") || name.contains("..") || name.contains("/") || name.contains("\\")) {
                        throw new IllegalArgumentException("文件路径不能包含 ..");
                    }
                    files.add(new HTTPFileArg(path, name,
                            optionalText(item, "mimeType", "application/octet-stream")));
                }
                sampler.setDoMultipartPost(true);
                sampler.setDoMultipart(true);
                sampler.setHTTPFiles(files.toArray(HTTPFileArg[]::new));
            }
            default -> throw new IllegalArgumentException("不支持的 Body 类型: " + body.type());
        }
    }

    private static String bodyText(JmeterBody body) {
        return body.value().isTextual() ? body.value().textValue() : body.value().toString();
    }

    private static String requiredText(com.fasterxml.jackson.databind.JsonNode node, String field) {
        String value = optionalText(node, field, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Body 部分缺少 " + field);
        }
        return value;
    }

    private static String optionalText(com.fasterxml.jackson.databind.JsonNode node, String field, String fallback) {
        var value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }

    private static String joinPath(String basePath, String template) {
        String left = basePath == null ? "" : basePath;
        if (left.endsWith("/") && template.startsWith("/")) {
            return left.substring(0, left.length() - 1) + template;
        }
        if (!left.endsWith("/") && !template.startsWith("/")) {
            return left + "/" + template;
        }
        return left + template;
    }
}

package com.autotest.runner;

import org.apache.jmeter.samplers.AbstractSampler;
import org.apache.jmeter.samplers.Entry;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 平台自研 Redis Sampler：只允许四种固定命令，不解析 Lua 或任意命令文本。 */
public final class RedisSampler extends AbstractSampler {
    public static final String HOST = "host";
    public static final String PORT = "port";
    public static final String DATABASE = "databaseNumber";
    public static final String USERNAME = "username";
    public static final String PASSWORD = "password";
    public static final String TLS = "tls";
    public static final String COMMAND = "command";
    public static final String KEY = "key";
    public static final String VALUE = "value";
    public static final String ALLOW_WRITE = "allowWrite";
    public static final String CONFIRMED = "confirmed";
    private static final List<String> COMMANDS = List.of("GET", "SET", "DEL", "EXISTS");

    @Override
    public SampleResult sample(Entry ignored) {
        SampleResult result = new SampleResult();
        result.setSampleLabel(getName());
        result.sampleStart();
        try {
            String command = getPropertyAsString(COMMAND).toUpperCase(java.util.Locale.ROOT);
            if (!COMMANDS.contains(command)) throw new IllegalArgumentException("Redis 命令不在白名单");
            if ((command.equals("SET") || command.equals("DEL"))
                    && !(getPropertyAsBoolean(ALLOW_WRITE) && getPropertyAsBoolean(CONFIRMED))) {
                throw new IllegalArgumentException("Redis 写操作未完成二次确认");
            }
            DefaultJedisClientConfig.Builder config = DefaultJedisClientConfig.builder()
                    .database(getPropertyAsInt(DATABASE)).ssl(getPropertyAsBoolean(TLS));
            String username = getPropertyAsString(USERNAME);
            if (!username.isBlank()) config.user(username);
            String password = resolvePassword(getPropertyAsString(PASSWORD));
            if (!password.isBlank()) config.password(password);
            try (JedisPooled jedis = new JedisPooled(new HostAndPort(getPropertyAsString(HOST), getPropertyAsInt(PORT)), config.build())) {
                String key = getPropertyAsString(KEY);
                String response = switch (command) {
                    case "GET" -> jedis.get(key);
                    case "SET" -> jedis.set(key, getPropertyAsString(VALUE));
                    case "DEL" -> Long.toString(jedis.del(key));
                    case "EXISTS" -> Boolean.toString(jedis.exists(key));
                    default -> throw new IllegalArgumentException("Redis 命令不在白名单");
                };
                result.setResponseData(response == null ? "" : response, StandardCharsets.UTF_8.name());
                result.setResponseCodeOK();
                result.setResponseMessage("REDIS " + command);
                result.setSuccessful(true);
            }
        } catch (Exception exception) {
            result.setResponseCode("500");
            result.setResponseMessage("Redis 执行失败");
            result.setSuccessful(false);
        } finally {
            result.sampleEnd();
        }
        return result;
    }

    private String resolvePassword(String expression) {
        if (expression == null || expression.isBlank()) return "";
        if (expression.contains("__P(redis.password")) return JMeterUtils.getPropDefault("redis.password", "");
        return expression;
    }
}

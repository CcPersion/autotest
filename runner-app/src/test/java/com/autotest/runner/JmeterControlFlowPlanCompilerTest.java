package com.autotest.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JmeterControlFlowPlanCompilerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void emitsIfLoopForeachAndWhileControllerGoldens() throws Exception {
        JmeterPlanCompiler compiler = new JmeterPlanCompiler();
        Path directory = Files.createTempDirectory("autotest-control-jmx-");
        String condition = Files.readString(compiler.compileControlFlow(json.readTree(
                "{\"kind\":\"CONDITION\",\"stepId\":\"condition-1\",\"left\":\"${status}\",\"operator\":\"EQUALS\",\"right\":\"READY\"}"), directory.resolve("condition.jmx")));
        String fixed = Files.readString(compiler.compileControlFlow(json.readTree(
                "{\"kind\":\"LOOP\",\"stepId\":\"loop-1\",\"mode\":\"FIXED\",\"count\":3}"), directory.resolve("fixed.jmx")));
        String list = Files.readString(compiler.compileControlFlow(json.readTree(
                "{\"kind\":\"LOOP\",\"stepId\":\"loop-2\",\"mode\":\"LIST\",\"items\":\"ids\",\"itemVariable\":\"id\"}"), directory.resolve("list.jmx")));
        String loop = Files.readString(compiler.compileControlFlow(json.readTree(
                "{\"kind\":\"LOOP\",\"stepId\":\"loop-3\",\"mode\":\"WHILE\",\"left\":\"${ready}\",\"operator\":\"EQUALS\",\"right\":\"true\"}"), directory.resolve("while.jmx")));
        assertTrue(condition.contains("IfController"));
        assertTrue(fixed.contains("LoopController"));
        assertTrue(list.contains("ForeachController"));
        assertTrue(loop.contains("WhileController"));
        assertFalse(condition.contains("JSR223"));
        assertFalse(condition.contains("BeanShell"));
    }
}

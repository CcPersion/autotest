package com.autotest.runner;

import org.apache.jmeter.engine.util.CompoundVariable;
import org.apache.jmeter.functions.AbstractFunction;
import org.apache.jmeter.functions.InvalidVariableException;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.samplers.Sampler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/** 受控读取 Runner 临时密钥文件，不改变密钥字节内容，也不把内容写入 JMX。 */
public final class JmeterSecretFileFunction extends AbstractFunction {

    private static final String REFERENCE_KEY = "__autotestSecret";
    private CompoundVariable file;

    @Override
    public String execute(SampleResult previousResult, Sampler currentSampler) throws InvalidVariableException {
        if (file == null) {
            throw new InvalidVariableException("密钥文件参数不能为空");
        }
        try {
            return Files.readString(Path.of(file.execute()), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            return "**ERR**";
        }
    }

    @Override
    public void setParameters(Collection<CompoundVariable> parameters) throws InvalidVariableException {
        checkParameterCount(parameters, 1, 1);
        file = parameters.iterator().next();
    }

    @Override
    public String getReferenceKey() {
        return REFERENCE_KEY;
    }

    @Override
    public List<String> getArgumentDesc() {
        return List.of("密钥文件路径");
    }
}

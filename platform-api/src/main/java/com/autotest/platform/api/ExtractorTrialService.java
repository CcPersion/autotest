package com.autotest.platform.api;

import com.autotest.contracts.extraction.ControlledExpressionEvaluator;
import com.autotest.contracts.extraction.ControlledExtractionResult;
import com.autotest.contracts.extraction.ExtractionRule;
import com.autotest.contracts.extraction.HttpResponseSample;
import com.autotest.contracts.extraction.SafeRegex;
import com.autotest.platform.security.ApiDomainException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 无副作用的响应样本试算服务：不联网、不读写项目资产、不解析密钥。 */
@Service
public class ExtractorTrialService {

    private static final int MAX_RULES = 100;
    private static final int MAX_HEADER_VALUE_LENGTH = 16 * 1024;

    public List<ExtractorTrialResult> evaluate(ExtractorTrialRequest request) {
        validate(request);
        ExtractorTrialSample input = request.response();
        HttpResponseSample sample = new HttpResponseSample(input.statusCode(), input.durationMs(), input.body(),
                normalizedMap(input.headers()), normalizedMap(input.cookies()));
        List<ExtractorTrialRule> rules = request.extractors();
        return java.util.stream.IntStream.range(0, rules.size())
                .mapToObj(index -> toResult(index, rules.get(index), sample))
                .toList();
    }

    private static ExtractorTrialResult toResult(int index, ExtractorTrialRule input, HttpResponseSample sample) {
        try {
            ExtractionRule rule = new ExtractionRule(input.type(), input.expression(), input.variable(),
                    input.defaultValue(), input.effectiveFailIfMissing());
            ControlledExtractionResult result = ControlledExpressionEvaluator.extract(index, rule, sample);
            return ExtractorTrialResult.from(result);
        } catch (IllegalArgumentException exception) {
            return new ExtractorTrialResult(index, input.type(), input.expression(), input.variable(),
                    false, false, null, "missing", true, "INVALID_EXPRESSION", "表达式不合法");
        }
    }

    private static void validate(ExtractorTrialRequest request) {
        if (request == null) throw invalid("请求不能为空");
        if (request.response() == null) {
            throw invalid("response 不能为空");
        }
        ExtractorTrialSample response = request.response();
        if (response.statusCode() == null || response.statusCode() < 100 || response.statusCode() > 599) {
            throw invalid("response.statusCode 必须是 100 到 599 的整数");
        }
        if (response.durationMs() == null || response.durationMs() < 0) {
            throw invalid("response.durationMs 必须是非负整数");
        }
        if (response.body() == null || response.body().length() > com.autotest.contracts.extraction.ControlledExtractionEvaluator.MAX_BODY_LENGTH) {
            throw invalid("response.body 不能为空且不能超过 1MB");
        }
        if (request.extractors() == null || request.extractors().size() > MAX_RULES) {
            throw invalid("extractors 数量不能超过 " + MAX_RULES);
        }
        for (ExtractorTrialRule rule : request.extractors()) {
            if (rule == null) throw invalid("extractors 不能包含 null");
            if (rule.expression() != null
                    && rule.expression().length() > com.autotest.contracts.extraction.ControlledExtractionEvaluator.MAX_EXPRESSION_LENGTH) {
                throw invalid("提取表达式超过长度限制");
            }
            if (rule.type() != null && "REGEX".equalsIgnoreCase(rule.type())
                    && rule.expression() != null && rule.expression().length() > SafeRegex.MAX_PATTERN_LENGTH) {
                throw invalid("正则表达式超过长度限制");
            }
            // 结构合同错误（type、variable、必填字段）必须阻断请求；只有
            // 表达式本身的语法求值错误才以单条 INVALID_EXPRESSION 返回。
            try {
                new ExtractionRule(rule.type(), rule.expression(), rule.variable(), rule.defaultValue(),
                        rule.effectiveFailIfMissing());
            } catch (IllegalArgumentException exception) {
                throw invalid("提取规则结构不合法");
            }
        }
        validateMap(response.headers(), "response.headers");
        validateMap(response.cookies(), "response.cookies");
    }

    private static void validateMap(Map<String, String> values, String field) {
        if (values == null) return;
        if (values.size() > MAX_RULES) throw invalid(field + " 项数过多");
        values.forEach((name, value) -> {
            if (name == null || name.isBlank() || name.length() > 256 || value == null
                    || value.length() > MAX_HEADER_VALUE_LENGTH) throw invalid(field + " 不能为空或超过长度限制");
        });
    }

    private static Map<String, String> normalizedMap(Map<String, String> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }

    private static ApiDomainException invalid(String message) {
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "INVALID_REQUEST", message);
    }
}

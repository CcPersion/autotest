package com.autotest.platform.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 仅用于契约测试和本地演示的确定性模型替身。 */
@Component
public final class FakeChatModelClient implements ChatModelClient {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");

    @Override
    public List<AiStreamEvent> complete(AiModelConfigRecord config, List<AiChatMessage> messages,
                                        List<AiToolDefinition> tools) {
        String last = messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
        if (last.contains("项目") && tools.stream().anyMatch(tool -> tool.name().equals("list_projects"))) {
            return List.of(AiStreamEvent.toolCall("list_projects", mapper.createObjectNode()), AiStreamEvent.done());
        }
        if ((last.contains("失败") || last.contains("报告") || last.contains("解释"))
                && tools.stream().anyMatch(tool -> tool.name().equals("get_run_report"))) {
            Matcher matcher = UUID_PATTERN.matcher(last);
            if (matcher.find()) {
                String first = matcher.group();
                String second = matcher.find() ? matcher.group() : first;
                return List.of(AiStreamEvent.toolCall("get_run_report", mapper.createObjectNode()
                        .put("projectId", first).put("runId", second)), AiStreamEvent.done());
            }
        }
        if ((last.toLowerCase().contains("curl") || last.contains("接口"))
                && tools.stream().anyMatch(tool -> tool.name().equals("create_draft_patch"))) {
            var operations = mapper.createArrayNode();
            operations.addObject().put("op", "add").put("path", "/name").put("value", "AI 生成接口");
            operations.addObject().put("op", "add").put("path", "/method").put("value", "GET");
            operations.addObject().put("op", "add").put("path", "/urlTemplate").put("value", "/health");
            var requestSpec = mapper.createObjectNode();
            requestSpec.putArray("pathParams");
            requestSpec.putArray("query");
            requestSpec.putArray("headers");
            requestSpec.putArray("cookies");
            requestSpec.putObject("body").put("type", "NONE");
            requestSpec.putObject("options");
            operations.addObject().put("op", "add").put("path", "/requestSpec").set("value", requestSpec);
            return List.of(AiStreamEvent.toolCall("create_draft_patch", mapper.createObjectNode()
                    .put("targetType", "API_DEFINITION").put("title", "从 Curl 生成接口定义")
                    .set("operations", operations)), AiStreamEvent.done());
        }
        return List.of(AiStreamEvent.text("已收到请求，等待人工审阅。"), AiStreamEvent.done());
    }
}

package com.autotest.platform.ai;

import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.security.ApiDomainException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.UUID;

@Service
public class AiSessionService {
    private final AiSessionRepository sessions;
    private final AiModelConfigService modelConfigs;
    private final ProjectRepository projects;
    private final AiToolRegistry tools;
    private final ChatModelClient openAiClient;
    private final FakeChatModelClient fakeClient;

    public AiSessionService(AiSessionRepository sessions, AiModelConfigService modelConfigs,
                            ProjectRepository projects, AiToolRegistry tools,
                            @Qualifier("openAiCompatibleChatClient") ChatModelClient openAiClient,
                            FakeChatModelClient fakeClient) {
        this.sessions = sessions;
        this.modelConfigs = modelConfigs;
        this.projects = projects;
        this.tools = tools;
        this.openAiClient = openAiClient;
        this.fakeClient = fakeClient;
    }

    public List<AiSessionRecord> list(UUID projectId) {
        requireProject(projectId);
        return sessions.findAll(projectId);
    }

    public AiSessionResponse get(UUID projectId, UUID sessionId) {
        AiSessionRecord session = requireSession(projectId, sessionId);
        return AiSessionResponse.from(session, sessions.findMessages(sessionId));
    }

    @Transactional
    public AiSessionRecord create(UUID projectId, AiSessionWrite write, UUID actorId) {
        requireProject(projectId);
        if (write == null || write.modelConfigId() == null || write.title() == null || write.title().isBlank()) {
            throw validation();
        }
        AiModelConfigRecord model = modelConfigs.get(write.modelConfigId());
        if (!model.enabled()) {
            throw conflict("MODEL_DISABLED", "模型配置已停用");
        }
        return sessions.insert(projectId, model.id(), write.title().trim(), actorId);
    }

    public List<AiStreamEvent> send(UUID projectId, UUID sessionId, String content) {
        AiSessionRecord session = requireSession(projectId, sessionId);
        if (content == null || content.isBlank() || content.length() > 20000) {
            throw validation();
        }
        String sanitized = AiPromptSanitizer.sanitize(content);
        sessions.appendMessage(session.id(), "USER", "TEXT", sanitized, null);
        AiModelConfigRecord model = modelConfigs.get(session.modelConfigId());
        ChatModelClient client = "FAKE".equals(model.providerType()) ? fakeClient : openAiClient;
        List<AiChatMessage> messages = boundedContext(sessions.findMessages(session.id()));
        List<AiStreamEvent> events;
        try {
            events = client.complete(model, messages, tools.definitions());
        } catch (RuntimeException exception) {
            AiStreamEvent error = AiStreamEvent.error("模型服务暂时不可用");
            sessions.appendMessage(session.id(), "ASSISTANT", "ERROR", error.content(), null);
            return List.of(error, AiStreamEvent.done());
        }
        List<AiStreamEvent> enriched = new ArrayList<>();
        for (AiStreamEvent event : events) {
            if ("TEXT".equals(event.type())) {
                sessions.appendMessage(session.id(), "ASSISTANT", "TEXT",
                        AiPromptSanitizer.sanitize(event.content()), null);
            } else if ("TOOL_CALL".equals(event.type())) {
                sessions.appendMessage(session.id(), "ASSISTANT", "TOOL_CALL", "", event.toolName());
                try {
                    var result = tools.invoke(event.toolName(), event.arguments());
                    sessions.appendMessage(session.id(), "TOOL", "TOOL_RESULT",
                            AiPromptSanitizer.sanitize(result.toString()), event.toolName());
                    enriched.add(event);
                    enriched.add(AiStreamEvent.toolResult(event.toolName(), result));
                } catch (RuntimeException exception) {
                    AiStreamEvent toolError = AiStreamEvent.error("工具调用失败");
                    sessions.appendMessage(session.id(), "TOOL", "ERROR", toolError.content(), event.toolName());
                    enriched.add(event);
                    enriched.add(toolError);
                }
                continue;
            }
            enriched.add(event);
        }
        return enriched;
    }

    private static List<AiChatMessage> boundedContext(List<AiMessageRecord> records) {
        final int limit = 20_000;
        LinkedList<AiChatMessage> result = new LinkedList<>();
        int total = 0;
        for (int index = records.size() - 1; index >= 0; index--) {
            AiMessageRecord record = records.get(index);
            if (!"USER".equals(record.role()) && !"ASSISTANT".equals(record.role())) continue;
            String content = AiPromptSanitizer.sanitize(record.content());
            if (total + content.length() <= limit) {
                result.addFirst(new AiChatMessage(record.role().toLowerCase(), content));
                total += content.length();
                continue;
            }
            if (result.isEmpty() && !content.isEmpty()) {
                result.addFirst(new AiChatMessage(record.role().toLowerCase(), content.substring(0, limit)));
            }
            break;
        }
        return List.copyOf(result);
    }

    private AiSessionRecord requireSession(UUID projectId, UUID sessionId) {
        requireProject(projectId);
        AiSessionRecord session = sessions.findById(projectId, sessionId);
        if (session == null) {
            throw new ApiDomainException(HttpStatus.NOT_FOUND.value(), "RESOURCE_NOT_FOUND", "AI 会话不存在");
        }
        return session;
    }

    private void requireProject(UUID projectId) {
        if (projectId == null || projects.findById(projectId) == null) {
            throw new ApiDomainException(HttpStatus.NOT_FOUND.value(), "RESOURCE_NOT_FOUND", "项目不存在");
        }
    }

    private static ApiDomainException validation() {
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", "AI 请求参数不合法");
    }

    private static ApiDomainException conflict(String code, String message) {
        return new ApiDomainException(HttpStatus.CONFLICT.value(), code, message);
    }
}

package com.autotest.platform.ai;

import com.autotest.platform.project.ProjectRecord;
import com.autotest.platform.project.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiSessionServiceTest {
    @Test
    void fakeSessionInvokesOnlyRegisteredStructuredTool() {
        UUID projectId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        Instant now = Instant.now();
        ProjectRepository projects = mock(ProjectRepository.class);
        AiSessionRepository sessions = mock(AiSessionRepository.class);
        AiModelConfigService models = mock(AiModelConfigService.class);
        AiToolRegistry tools = mock(AiToolRegistry.class);
        FakeChatModelClient fake = new FakeChatModelClient();
        when(projects.findById(projectId)).thenReturn(new ProjectRecord(projectId, "项目", "", 0, false, now, now));
        AiModelConfigRecord config = new AiModelConfigRecord(modelId, "本地", "fake://model", "fixture", null,
                true, 0, now, now, "FAKE");
        when(models.get(modelId)).thenReturn(config);
        AiSessionRecord session = new AiSessionRecord(sessionId, projectId, modelId, "会话", actorId, now, now);
        when(sessions.findById(projectId, sessionId)).thenReturn(session);
        when(sessions.findMessages(sessionId)).thenReturn(List.of(
                new AiMessageRecord(UUID.randomUUID(), sessionId, "USER", "TEXT", "请列出当前项目", null, now)));
        when(tools.definitions()).thenReturn(List.of(new AiToolDefinition("list_projects", "列项目",
                new ObjectMapper().createObjectNode())));
        when(tools.invoke(any(), any())).thenReturn(new ObjectMapper().createArrayNode());

        AiSessionService service = new AiSessionService(sessions, models, projects, tools,
                mock(ChatModelClient.class), fake);
        List<AiStreamEvent> events = service.send(projectId, sessionId, "请列出当前项目");

        assertTrue(events.stream().anyMatch(event -> "TOOL_RESULT".equals(event.type())));
        verify(tools).invoke("list_projects", new ObjectMapper().createObjectNode());
    }
}

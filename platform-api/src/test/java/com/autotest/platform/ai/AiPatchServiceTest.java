package com.autotest.platform.ai;

import com.autotest.platform.api.ApiCaseRecord;
import com.autotest.platform.api.ApiCaseRepository;
import com.autotest.platform.api.ApiCaseService;
import com.autotest.platform.api.ApiDefinitionRecord;
import com.autotest.platform.api.ApiDefinitionRepository;
import com.autotest.platform.api.ApiDefinitionService;
import com.autotest.platform.project.ProjectRecord;
import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.scenario.ScenarioRepository;
import com.autotest.platform.scenario.ScenarioService;
import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiPatchServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ApiDefinitionRepository definitions = mock(ApiDefinitionRepository.class);
    private final ApiCaseRepository cases = mock(ApiCaseRepository.class);
    private final ScenarioRepository scenarios = mock(ScenarioRepository.class);
    private final ApiDefinitionService definitionService = mock(ApiDefinitionService.class);
    private final ApiCaseService caseService = mock(ApiCaseService.class);
    private final ScenarioService scenarioService = mock(ScenarioService.class);
    private final AiPatchService service = new AiPatchService(mapper, projects, definitions, cases, scenarios,
            definitionService, caseService, scenarioService);
    private final UUID projectId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @Test
    void previewProducesFieldDiffWithoutWriting() {
        ApiDefinitionRecord current = definition(0, "登录接口");
        when(projects.findById(projectId)).thenReturn(project(0));
        when(definitions.findById(projectId, current.id())).thenReturn(current);

        AiPatchPreviewResponse preview = service.preview(projectId, new AiPatchWrite(
                "调整名称", "API_DEFINITION", current.id(), null, 0,
                List.of(new AiPatchOperation("replace", "/name", mapper.getNodeFactory().textNode("登录接口 v2")))), actorId);

        assertTrue(preview.canConfirm());
        assertEquals("MODIFIED", preview.changes().get(0).changeType());
        assertEquals("登录接口", preview.changes().get(0).oldValue().asText());
        assertEquals("登录接口 v2", preview.changes().get(0).newValue().asText());
        verify(definitionService).validateDraft(eq(projectId), any());
        verify(definitionService, never()).create(any(), any(), any());
        verify(definitionService, never()).update(any(), any(), any(), any());
    }

    @Test
    void rejectsProtectedPathAndScriptContentWithFieldError() {
        when(projects.findById(projectId)).thenReturn(project(0));
        ApiDefinitionRecord current = definition(0, "登录接口");
        when(definitions.findById(projectId, current.id())).thenReturn(current);

        ApiDomainException protectedPath = assertThrows(ApiDomainException.class, () -> service.preview(projectId,
                new AiPatchWrite("非法", "API_DEFINITION", current.id(), null, 0,
                        List.of(new AiPatchOperation("replace", "/revision", mapper.getNodeFactory().numberNode(9)))), actorId));
        assertEquals("PATCH_PATH_NOT_ALLOWED", protectedPath.code());

        ApiDomainException script = assertThrows(ApiDomainException.class, () -> service.preview(projectId,
                new AiPatchWrite("非法", "API_DEFINITION", current.id(), null, 0,
                        List.of(new AiPatchOperation("replace", "/name", mapper.getNodeFactory().textNode("$(whoami)")))), actorId));
        assertEquals("UNTRUSTED_PATCH_VALUE", script.code());
        verifyNoInteractions(definitionService, caseService, scenarioService);
    }

    @Test
    void staleRevisionIsRejectedBeforePreview() {
        ApiDefinitionRecord current = definition(2, "登录接口");
        when(projects.findById(projectId)).thenReturn(project(0));
        when(definitions.findById(projectId, current.id())).thenReturn(current);

        ApiDomainException exception = assertThrows(ApiDomainException.class, () -> service.preview(projectId,
                new AiPatchWrite("过期", "API_DEFINITION", current.id(), null, 1,
                        List.of(new AiPatchOperation("replace", "/name", mapper.getNodeFactory().textNode("旧版本")))), actorId));
        assertEquals("REVISION_CONFLICT", exception.code());
    }

    @Test
    void confirmUsesCasAndCannotReplayToken() {
        ApiDefinitionRecord current = definition(0, "登录接口");
        ApiDefinitionRecord updated = definition(1, "登录接口 v2");
        when(projects.findById(projectId)).thenReturn(project(0));
        when(projects.findByIdForUpdate(projectId)).thenReturn(project(0));
        when(definitions.findById(projectId, current.id())).thenReturn(current);
        when(definitions.findActiveByIdForUpdate(projectId, current.id())).thenReturn(current);
        when(definitionService.update(eq(projectId), eq(current.id()), any(), eq(actorId))).thenReturn(updated);

        AiPatchPreviewResponse preview = service.preview(projectId, new AiPatchWrite(
                "调整名称", "API_DEFINITION", current.id(), null, 0,
                List.of(new AiPatchOperation("replace", "/name", mapper.getNodeFactory().textNode("登录接口 v2")))), actorId);

        AiPatchConfirmResponse confirmed = service.confirm(projectId,
                new AiPatchConfirmRequest(preview.previewId()), actorId);
        assertEquals("API_DEFINITION", confirmed.targetType());
        verify(definitionService).update(eq(projectId), eq(current.id()), argThat(write ->
                "登录接口 v2".equals(write.name()) && write.revision() == 0), eq(actorId));

        ApiDomainException replay = assertThrows(ApiDomainException.class, () -> service.confirm(projectId,
                new AiPatchConfirmRequest(preview.previewId()), actorId));
        assertEquals("PATCH_NOT_FOUND", replay.code());
    }

    @Test
    void createCaseRequiresParentDefinitionAndValidatesThroughCaseService() {
        UUID definitionId = UUID.randomUUID();
        when(projects.findById(projectId)).thenReturn(project(0));
        when(definitions.findById(projectId, definitionId)).thenReturn(definition(0, "登录接口", definitionId));
        var caseSpec = mapper.createObjectNode().putObject("pathParams").putObject("query");
        caseSpec.putObject("headers");
        caseSpec.putObject("cookies");
        caseSpec.putObject("body").put("type", "NONE");
        caseSpec.set("extractors", mapper.createArrayNode());
        caseSpec.set("dataRows", mapper.createArrayNode());
        caseSpec.putObject("dataRowOptions").put("continueOnFailure", true);

        AiPatchPreviewResponse preview = service.preview(projectId, new AiPatchWrite(
                "新用例", "API_CASE", null, definitionId, null,
                List.of(new AiPatchOperation("add", "/name", mapper.getNodeFactory().textNode("AI 用例")),
                        new AiPatchOperation("add", "/caseSpec", caseSpec),
                        new AiPatchOperation("add", "/variables", mapper.createObjectNode()),
                        new AiPatchOperation("add", "/assertions", mapper.createArrayNode()))), actorId);

        assertTrue(preview.canConfirm());
        verify(caseService).validateDraft(eq(projectId), eq(definitionId), any());
    }

    private ProjectRecord project(int revision) {
        return new ProjectRecord(projectId, "demo", "", revision, false, null, null);
    }

    private ApiDefinitionRecord definition(int revision, String name) {
        return definition(revision, name, UUID.randomUUID());
    }

    private ApiDefinitionRecord definition(int revision, String name, UUID id) {
        var requestSpec = mapper.createObjectNode();
        requestSpec.putArray("pathParams");
        requestSpec.putArray("query");
        requestSpec.putArray("headers");
        requestSpec.putArray("cookies");
        requestSpec.putObject("body").put("type", "NONE");
        requestSpec.putObject("options");
        return new ApiDefinitionRecord(id, projectId, null, name, "GET", "/health", requestSpec,
                revision, false, null, null);
    }
}

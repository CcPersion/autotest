package com.autotest.platform.importer;

import com.autotest.platform.api.ApiCaseRecord;
import com.autotest.platform.api.ApiCaseRepository;
import com.autotest.platform.api.ApiCaseService;
import com.autotest.platform.api.ApiDefinitionRecord;
import com.autotest.platform.api.ApiDefinitionRepository;
import com.autotest.platform.api.ApiDefinitionService;
import com.autotest.platform.project.ProjectRecord;
import com.autotest.platform.project.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ImportServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ApiDefinitionRepository definitions = mock(ApiDefinitionRepository.class);
    private final ApiCaseRepository cases = mock(ApiCaseRepository.class);
    private final ApiDefinitionService definitionService = mock(ApiDefinitionService.class);
    private final ApiCaseService caseService = mock(ApiCaseService.class);
    private final ImportService service = new ImportService(mapper, projects, definitions, cases, definitionService, caseService);
    private final UUID projectId = UUID.randomUUID();
    private final UUID actorId = UUID.randomUUID();

    @Test
    void previewDoesNotWriteAndConfirmCreatesOnlyAfterExplicitChoice() {
        when(projects.findById(projectId)).thenReturn(project(0));
        when(definitions.findAll(eq(projectId), isNull(), eq(false))).thenReturn(List.of());
        when(cases.findAll(any(), any(), eq(false))).thenReturn(List.of());
        ApiDefinitionRecord definition = definition();
        ApiCaseRecord apiCase = apiCase(definition.id());
        when(projects.findByIdForUpdate(projectId)).thenReturn(project(0));
        when(definitionService.create(eq(projectId), any(), eq(actorId))).thenReturn(definition);
        when(caseService.create(eq(projectId), eq(definition.id()), any(), eq(actorId))).thenReturn(apiCase);

        ImportPreviewResponse preview = service.previewCurl(projectId,
                new ImportPreviewRequest(null, "curl https://example.test/health"), actorId);

        assertEquals(1, preview.items().size());
        assertEquals(ImportAction.CREATE, preview.items().get(0).recommendedAction());
        verify(definitionService, never()).create(any(), any(), any());

        ImportConfirmResponse response = service.confirm(projectId,
                new ImportConfirmRequest(preview.previewId(),
                        List.of(new ImportChoice(0, ImportAction.CREATE))), actorId);

        assertEquals(1, response.definitions().size());
        assertEquals(1, response.cases().size());
        verify(definitionService).create(eq(projectId), any(), eq(actorId));
        verify(caseService).create(eq(projectId), eq(definition.id()), any(), eq(actorId));
    }

    @Test
    void conflictingPreviewDefaultsToSkipAndDoesNotOverwrite() {
        when(projects.findById(projectId)).thenReturn(project(0));
        ApiDefinitionRecord existing = definition();
        when(definitions.findAll(eq(projectId), isNull(), eq(false))).thenReturn(List.of(existing));
        when(cases.findAll(projectId, existing.id(), false)).thenReturn(List.of());

        ImportPreviewResponse preview = service.previewCurl(projectId,
                new ImportPreviewRequest(null, "curl https://example.test/health"), actorId);

        assertEquals("EXACT_MATCH", preview.items().get(0).conflictType());
        assertEquals(ImportAction.SKIP, preview.items().get(0).recommendedAction());
        when(projects.findByIdForUpdate(projectId)).thenReturn(project(0));
        ImportConfirmResponse response = service.confirm(projectId,
                new ImportConfirmRequest(preview.previewId(), List.of()), actorId);

        assertTrue(response.definitions().isEmpty());
        verifyNoInteractions(definitionService, caseService);
    }

    private ProjectRecord project(int revision) {
        return new ProjectRecord(projectId, "demo", "", revision, false, null, null);
    }

    private ApiDefinitionRecord definition() {
        var requestSpec = mapper.createObjectNode();
        requestSpec.putArray("pathParams");
        requestSpec.putArray("query");
        requestSpec.putArray("headers");
        requestSpec.putArray("cookies");
        requestSpec.putObject("body").put("type", "NONE");
        return new ApiDefinitionRecord(UUID.randomUUID(), projectId, null, "GET /health", "GET",
                "https://example.test/health", requestSpec, 0, false, null, null);
    }

    private ApiCaseRecord apiCase(UUID definitionId) {
        return new ApiCaseRecord(UUID.randomUUID(), projectId, definitionId, "GET /health 默认用例",
                mapper.createObjectNode(), mapper.createObjectNode(), mapper.createArrayNode(), 0, false, null, null);
    }
}

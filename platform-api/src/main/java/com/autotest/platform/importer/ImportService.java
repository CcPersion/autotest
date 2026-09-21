package com.autotest.platform.importer;

import com.autotest.platform.api.ApiCaseRecord;
import com.autotest.platform.api.ApiCaseResponse;
import com.autotest.platform.api.ApiCaseService;
import com.autotest.platform.api.ApiCaseWrite;
import com.autotest.platform.api.ApiDefinitionRecord;
import com.autotest.platform.api.ApiDefinitionResponse;
import com.autotest.platform.api.ApiDefinitionService;
import com.autotest.platform.api.ApiDefinitionWrite;
import com.autotest.platform.api.ApiCaseRepository;
import com.autotest.platform.api.ApiDefinitionRepository;
import com.autotest.platform.project.ProjectRecord;
import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ImportService {
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(10);

    private final ObjectMapper mapper;
    private final ProjectRepository projects;
    private final ApiDefinitionRepository definitions;
    private final ApiCaseRepository cases;
    private final ApiDefinitionService definitionService;
    private final ApiCaseService caseService;
    private final Map<UUID, PendingImport> pending = new ConcurrentHashMap<>();

    public ImportService(ObjectMapper mapper, ProjectRepository projects, ApiDefinitionRepository definitions,
                         ApiCaseRepository cases, ApiDefinitionService definitionService,
                         ApiCaseService caseService) {
        this.mapper = mapper;
        this.projects = projects;
        this.definitions = definitions;
        this.cases = cases;
        this.definitionService = definitionService;
        this.caseService = caseService;
    }

    public ImportPreviewResponse previewCurl(UUID projectId, ImportPreviewRequest request, UUID actorId) {
        return preview(projectId, request, actorId, "CURL");
    }

    public ImportPreviewResponse previewOpenApi(UUID projectId, ImportPreviewRequest request, UUID actorId) {
        return preview(projectId, request, actorId, "OPENAPI");
    }

    private ImportPreviewResponse preview(UUID projectId, ImportPreviewRequest request, UUID actorId, String type) {
        ProjectRecord project = requireProject(projectId);
        if (project.archived()) throw conflict("PROJECT_ARCHIVED", "项目已归档");
        if (request == null || request.source() == null || request.source().isBlank()) {
            throw invalid("source", "导入内容不能为空");
        }
        ImportDocument document;
        try {
            document = "CURL".equals(type) ? new CurlImportParser(mapper).parse(request.source())
                    : new OpenApiImportParser(mapper).parse(request.source());
        } catch (ImportParseException exception) {
            throw invalid(exception.path(), exception.getMessage());
        }
        UUID previewId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(PREVIEW_TTL);
        List<PendingItem> pendingItems = new ArrayList<>();
        List<ImportPreviewItem> items = new ArrayList<>();
        HashSet<String> seenKeys = new HashSet<>();
        for (int index = 0; index < document.candidates().size(); index++) {
            ImportCandidate candidate = document.candidates().get(index);
            Match match = findMatch(projectId, request.moduleId(), candidate);
            List<String> errors = new ArrayList<>();
            String batchKey = candidate.method() + "\n" + candidate.urlTemplate() + "\n" + candidate.definitionName();
            if (!seenKeys.add(batchKey)) errors.add("导入文档内存在重复接口");
            String conflictType = match.definition() == null ? null
                    : sameDefinition(match.definition(), candidate) ? "EXACT_MATCH" : "NAME_CONFLICT";
            ImportAction recommended = conflictType == null && errors.isEmpty() ? ImportAction.CREATE : ImportAction.SKIP;
            items.add(new ImportPreviewItem(index, candidate.sourcePath(), candidate.definitionName(), candidate.caseName(),
                    candidate.method(), candidate.urlTemplate(), candidate.requestSpec(), candidate.caseSpec(),
                    candidate.variables(), candidate.assertions(), conflictType,
                    match.definition() == null ? null : match.definition().id(),
                    match.apiCase() == null ? null : match.apiCase().id(), recommended,
                    candidate.warnings(), errors));
            pendingItems.add(new PendingItem(candidate, match.definition(), match.apiCase()));
        }
        PendingImport value = new PendingImport(previewId, projectId, request.moduleId(), actorId, project.revision(),
                type, expiresAt, pendingItems);
        pending.put(previewId, value);
        return new ImportPreviewResponse(previewId, projectId, type, project.revision(), items,
                document.warnings(), expiresAt);
    }

    @Transactional
    public ImportConfirmResponse confirm(UUID projectId, ImportConfirmRequest request, UUID actorId) {
        if (request == null || request.previewId() == null) throw invalid("previewId", "预览 ID 不能为空");
        PendingImport value = pending.get(request.previewId());
        if (value == null || value.expiresAt().isBefore(Instant.now())) {
            pending.remove(request.previewId());
            throw conflict("IMPORT_PREVIEW_EXPIRED", "导入预览已过期");
        }
        if (!projectId.equals(value.projectId()) || !actorId.equals(value.actorId())) {
            throw new ApiDomainException(HttpStatus.FORBIDDEN.value(), "IMPORT_PREVIEW_FORBIDDEN", "不能确认其他用户的导入预览");
        }
        ProjectRecord project = projects.findByIdForUpdate(projectId);
        if (project == null) throw notFound();
        if (project.archived()) throw conflict("PROJECT_ARCHIVED", "项目已归档");
        if (project.revision() != value.projectRevision()) {
            throw conflict("REVISION_CONFLICT", "项目版本已变化，请重新预览");
        }
        Map<Integer, ImportAction> choices = new HashMap<>();
        if (request.choices() != null) {
            for (ImportChoice choice : request.choices()) {
                if (choice == null || choice.action() == null || choice.index() < 0
                        || choice.index() >= value.items().size() || choices.put(choice.index(), choice.action()) != null) {
                    throw invalid("choices", "导入选择项不合法或重复");
                }
            }
        }
        List<ApiDefinitionResponse> importedDefinitions = new ArrayList<>();
        List<ApiCaseResponse> importedCases = new ArrayList<>();
        for (int index = 0; index < value.items().size(); index++) {
            PendingItem item = value.items().get(index);
            ImportAction action = choices.getOrDefault(index, defaultAction(item));
            if (action == ImportAction.SKIP) continue;
            Match current = findMatch(projectId, value.moduleId(), item.candidate());
            ensureUnchanged(item, current);
            if (action == ImportAction.CREATE && current.definition() != null) {
                throw conflict("IMPORT_CONFLICT", "确认时发现接口已存在，请重新预览");
            }
            if (action == ImportAction.UPDATE && (current.definition() == null || !sameDefinition(current.definition(), item.candidate()))) {
                throw conflict("IMPORT_CONFLICT", "只有精确匹配的接口才允许更新");
            }
            ApiDefinitionRecord definition;
            if (action == ImportAction.UPDATE) {
                definition = definitionService.update(projectId, current.definition().id(),
                        new ApiDefinitionWrite(value.moduleId(), item.candidate().definitionName(), item.candidate().method(),
                                item.candidate().urlTemplate(), item.candidate().requestSpec(), current.definition().revision()), actorId);
            } else {
                definition = definitionService.create(projectId,
                        new ApiDefinitionWrite(value.moduleId(), item.candidate().definitionName(), item.candidate().method(),
                                item.candidate().urlTemplate(), item.candidate().requestSpec(), null), actorId);
            }
            ApiCaseRecord apiCase = current.apiCase();
            if (action == ImportAction.UPDATE && apiCase != null) {
                apiCase = caseService.update(projectId, definition.id(), apiCase.id(),
                        new ApiCaseWrite(item.candidate().caseName(), item.candidate().caseSpec(), item.candidate().variables(),
                                item.candidate().assertions(), apiCase.revision()), actorId);
            } else {
                apiCase = caseService.create(projectId, definition.id(),
                        new ApiCaseWrite(item.candidate().caseName(), item.candidate().caseSpec(), item.candidate().variables(),
                                item.candidate().assertions(), null), actorId);
            }
            importedDefinitions.add(ApiDefinitionResponse.from(definition));
            importedCases.add(ApiCaseResponse.from(apiCase));
        }
        pending.remove(request.previewId());
        return new ImportConfirmResponse(importedDefinitions, importedCases);
    }

    private void ensureUnchanged(PendingItem item, Match current) {
        UUID expectedDefinitionId = item.definition() == null ? null : item.definition().id();
        UUID actualDefinitionId = current.definition() == null ? null : current.definition().id();
        Integer expectedDefinitionRevision = item.definition() == null ? null : item.definition().revision();
        Integer actualDefinitionRevision = current.definition() == null ? null : current.definition().revision();
        UUID expectedCaseId = item.apiCase() == null ? null : item.apiCase().id();
        UUID actualCaseId = current.apiCase() == null ? null : current.apiCase().id();
        Integer expectedCaseRevision = item.apiCase() == null ? null : item.apiCase().revision();
        Integer actualCaseRevision = current.apiCase() == null ? null : current.apiCase().revision();
        if (!java.util.Objects.equals(expectedDefinitionId, actualDefinitionId)
                || !java.util.Objects.equals(expectedDefinitionRevision, actualDefinitionRevision)
                || !java.util.Objects.equals(expectedCaseId, actualCaseId)
                || !java.util.Objects.equals(expectedCaseRevision, actualCaseRevision)) {
            throw conflict("REVISION_CONFLICT", "导入预览对应的接口或用例已变化，请重新预览");
        }
    }

    private ImportAction defaultAction(PendingItem item) {
        return item.definition() == null ? ImportAction.CREATE : ImportAction.SKIP;
    }

    private Match findMatch(UUID projectId, UUID moduleId, ImportCandidate candidate) {
        ApiDefinitionRecord nameMatch = null;
        for (ApiDefinitionRecord definition : definitions.findAll(projectId, null, false)) {
            if (!java.util.Objects.equals(moduleId, definition.moduleId())) continue;
            if (sameDefinition(definition, candidate)) {
                List<ApiCaseRecord> matchingCases = cases.findAll(projectId, definition.id(), false).stream()
                        .filter(item -> item.name().equalsIgnoreCase(candidate.caseName())).toList();
                return new Match(definition, matchingCases.isEmpty() ? null : matchingCases.get(0));
            }
            if (definition.name().equalsIgnoreCase(candidate.definitionName())) nameMatch = definition;
        }
        if (nameMatch == null) return new Match(null, null);
        List<ApiCaseRecord> matchingCases = cases.findAll(projectId, nameMatch.id(), false).stream()
                .filter(item -> item.name().equalsIgnoreCase(candidate.caseName())).toList();
        return new Match(nameMatch, matchingCases.isEmpty() ? null : matchingCases.get(0));
    }

    private boolean sameDefinition(ApiDefinitionRecord definition, ImportCandidate candidate) {
        return definition.method().equalsIgnoreCase(candidate.method())
                && definition.urlTemplate().equals(candidate.urlTemplate());
    }

    private ProjectRecord requireProject(UUID projectId) {
        ProjectRecord project = projects.findById(projectId);
        if (project == null) throw notFound();
        return project;
    }

    private ApiDomainException invalid(String path, String message) {
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "IMPORT_INVALID",
                "导入内容不合法", Map.of("fieldErrors", List.of(Map.of("path", path, "code", "INVALID_IMPORT", "message", message))));
    }

    private static ApiDomainException notFound() {
        return new ApiDomainException(HttpStatus.NOT_FOUND.value(), "RESOURCE_NOT_FOUND", "资源不存在");
    }

    private static ApiDomainException conflict(String code, String message) {
        return new ApiDomainException(HttpStatus.CONFLICT.value(), code, message);
    }

    private record PendingImport(UUID previewId, UUID projectId, UUID moduleId, UUID actorId, int projectRevision,
                                 String type, Instant expiresAt, List<PendingItem> items) {
    }

    private record PendingItem(ImportCandidate candidate, ApiDefinitionRecord definition, ApiCaseRecord apiCase) {
    }

    private record Match(ApiDefinitionRecord definition, ApiCaseRecord apiCase) {
    }
}

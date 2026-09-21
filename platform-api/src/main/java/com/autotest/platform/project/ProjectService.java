package com.autotest.platform.project;

import com.autotest.contracts.network.TargetAllowlist;
import com.autotest.platform.security.ApiDomainException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository projects;

    public ProjectService(ProjectRepository projects) {
        this.projects = projects;
    }

    public List<ProjectRecord> list(boolean includeArchived) {
        return projects.findAll(includeArchived);
    }

    public ProjectRecord get(UUID id) {
        return require(id);
    }

    @Transactional
    public ProjectRecord create(String name, String description, UUID actorId) {
        return create(name, description, List.of(), actorId);
    }

    @Transactional
    public ProjectRecord create(String name, String description, List<String> targetAllowlist, UUID actorId) {
        String normalizedName = name(name);
        List<String> normalizedAllowlist = allowlist(targetAllowlist);
        if (projects.existsActiveName(normalizedName)) {
            throw conflict("NAME_CONFLICT", "项目名称已存在");
        }
        try {
            return projects.insert(normalizedName, description, normalizedAllowlist, actorId);
        } catch (DuplicateKeyException exception) {
            throw conflict("NAME_CONFLICT", "项目名称已存在");
        }
    }

    @Transactional
    public ProjectRecord update(UUID id, String name, String description, Integer revision, UUID actorId) {
        return update(id, name, description, List.of(), revision, actorId);
    }

    @Transactional
    public ProjectRecord update(UUID id, String name, String description, List<String> targetAllowlist,
                                Integer revision, UUID actorId) {
        ProjectRecord current = lock(id);
        checkRevision(current, revision);
        String normalizedName = name(name);
        List<String> normalizedAllowlist = allowlist(targetAllowlist);
        if (projects.existsActiveNameExcluding(normalizedName, id)) {
            throw conflict("NAME_CONFLICT", "项目名称已存在");
        }
        try {
            updateOrConflict(projects.updateDetails(id, normalizedName, description, normalizedAllowlist, revision, actorId),
                    current, revision);
        } catch (DuplicateKeyException exception) {
            throw conflict("NAME_CONFLICT", "项目名称已存在");
        }
        return require(id);
    }

    @Transactional
    public ProjectRecord archive(UUID id, Integer revision, UUID actorId) {
        ProjectRecord current = lock(id);
        checkRevision(current, revision);
        updateOrConflict(projects.updateArchived(id, true, revision, actorId), current, revision);
        return require(id);
    }

    @Transactional
    public ProjectRecord restore(UUID id, Integer revision, UUID actorId) {
        ProjectRecord current = lock(id);
        checkRevision(current, revision);
        if (!current.archived()) {
            throw conflict("REVISION_CONFLICT", "项目版本已变化");
        }
        if (projects.existsActiveNameExcluding(current.name(), id)) {
            throw conflict("NAME_CONFLICT", "项目名称已存在");
        }
        updateOrConflict(projects.updateArchived(id, false, revision, actorId), current, revision);
        return require(id);
    }

    private ProjectRecord require(UUID id) {
        ProjectRecord project = projects.findById(id);
        if (project == null) {
            throw notFound();
        }
        return project;
    }

    private ProjectRecord lock(UUID id) {
        ProjectRecord project = projects.findByIdForUpdate(id);
        if (project == null) {
            throw notFound();
        }
        return project;
    }

    private static void checkRevision(ProjectRecord current, Integer revision) {
        if (revision == null || revision != current.revision()) {
            throw new ApiDomainException(HttpStatus.CONFLICT.value(), "REVISION_CONFLICT",
                    "项目版本已变化", Map.of("currentRevision", current.revision(),
                            "requestedRevision", revision == null ? -1 : revision));
        }
    }

    private static void updateOrConflict(int updated, ProjectRecord current, int revision) {
        if (updated != 1) {
            throw new ApiDomainException(HttpStatus.CONFLICT.value(), "REVISION_CONFLICT",
                    "项目版本已变化", Map.of("currentRevision", current.revision(),
                            "requestedRevision", revision));
        }
    }

    private static String name(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", "项目名称不能为空");
        }
        return normalized;
    }

    private static List<String> allowlist(List<String> value) {
        try {
            return TargetAllowlist.parse(value).rules();
        } catch (IllegalArgumentException exception) {
            throw new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED",
                    exception.getMessage() == null ? "目标白名单不合法" : exception.getMessage());
        }
    }

    private static ApiDomainException notFound() {
        return new ApiDomainException(HttpStatus.NOT_FOUND.value(), "RESOURCE_NOT_FOUND", "资源不存在");
    }

    private static ApiDomainException conflict(String code, String message) {
        return new ApiDomainException(HttpStatus.CONFLICT.value(), code, message);
    }
}

package com.autotest.platform.module;

import com.autotest.platform.project.ProjectRecord;
import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.security.ApiDomainException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ModuleService {

    private final ModuleRepository modules;
    private final ProjectRepository projects;

    public ModuleService(ModuleRepository modules, ProjectRepository projects) {
        this.modules = modules;
        this.projects = projects;
    }

    public List<ModuleNode> tree(UUID projectId) {
        requireProject(projectId);
        List<ModuleRecord> records = modules.findActiveByProject(projectId);
        Map<UUID, List<ModuleRecord>> children = new HashMap<>();
        for (ModuleRecord record : records) {
            children.computeIfAbsent(record.parentId(), ignored -> new ArrayList<>()).add(record);
        }
        return nodes(children, null);
    }

    @Transactional
    public ModuleNode create(UUID projectId, String name, UUID parentId, Integer position, UUID actorId) {
        ProjectRecord project = lockProject(projectId);
        validateWritable(project);
        String normalized = name(name);
        validatePosition(position);
        if (parentId != null && modules.findActiveById(projectId, parentId) == null) {
            throw notFound();
        }
        if (modules.existsActiveSiblingName(projectId, parentId, normalized, null)) {
            throw conflict("NAME_CONFLICT", "模块名称已存在");
        }
        List<ModuleRecord> siblings = modules.findActiveSiblings(projectId, parentId);
        int target = position == null ? siblings.size() : Math.min(position, siblings.size());
        shiftForInsert(siblings, target, projectId, actorId);
        try {
            return ModuleNode.leaf(modules.insert(projectId, parentId, normalized, target, actorId));
        } catch (DuplicateKeyException exception) {
            throw conflict("NAME_CONFLICT", "模块名称已存在");
        }
    }

    @Transactional
    public ModuleNode rename(UUID projectId, UUID moduleId, String name, Integer revision, UUID actorId) {
        ProjectRecord project = lockProject(projectId);
        validateWritable(project);
        ModuleRecord current = requireActive(projectId, moduleId, true);
        checkRevision(current, revision);
        String normalized = name(name);
        if (modules.existsActiveSiblingName(projectId, current.parentId(), normalized, moduleId)) {
            throw conflict("NAME_CONFLICT", "模块名称已存在");
        }
        try {
            updateOrConflict(modules.updateName(projectId, moduleId, normalized, revision, actorId), current, revision);
        } catch (DuplicateKeyException exception) {
            throw conflict("NAME_CONFLICT", "模块名称已存在");
        }
        return ModuleNode.leaf(requireActive(projectId, moduleId, false));
    }

    @Transactional
    public ModuleNode move(UUID projectId, UUID moduleId, UUID parentId, Integer position,
                           Integer revision, UUID actorId) {
        ProjectRecord project = lockProject(projectId);
        validateWritable(project);
        ModuleRecord current = requireActive(projectId, moduleId, true);
        checkRevision(current, revision);
        validatePosition(position);
        if (parentId != null && modules.findActiveById(projectId, parentId) == null) {
            throw notFound();
        }
        if (modules.existsActiveSiblingName(projectId, parentId, current.name(), moduleId)) {
            throw conflict("NAME_CONFLICT", "模块名称已存在");
        }
        ensureNoCycle(projectId, moduleId, parentId);

        List<ModuleRecord> oldSiblings = new ArrayList<>(modules.findActiveSiblings(projectId, current.parentId()));
        List<ModuleRecord> targetSiblings = current.parentId() != null && current.parentId().equals(parentId)
                ? oldSiblings : new ArrayList<>(modules.findActiveSiblings(projectId, parentId));
        oldSiblings.removeIf(item -> item.id().equals(moduleId));
        if (targetSiblings != oldSiblings) {
            targetSiblings.removeIf(item -> item.id().equals(moduleId));
        }
        int target = position == null ? targetSiblings.size() : Math.min(position, targetSiblings.size());
        targetSiblings.add(target, current);
        normalize(oldSiblings, projectId, actorId);
        if (targetSiblings != oldSiblings) {
            normalize(targetSiblings, projectId, actorId);
        }
        int newOrder = target;
        try {
            updateOrConflict(modules.updateParentAndPosition(projectId, moduleId, parentId, newOrder,
                    revision, actorId), current, revision);
        } catch (DuplicateKeyException exception) {
            throw conflict("NAME_CONFLICT", "模块名称已存在");
        }
        return ModuleNode.leaf(requireActive(projectId, moduleId, false));
    }

    @Transactional
    public void delete(UUID projectId, UUID moduleId, Integer revision, UUID actorId) {
        ProjectRecord project = lockProject(projectId);
        validateWritable(project);
        ModuleRecord current = requireActive(projectId, moduleId, true);
        checkRevision(current, revision);
        int childCount = modules.activeChildCount(projectId, moduleId);
        int apiCount = modules.activeApiCount(projectId, moduleId);
        if (childCount != 0 || apiCount != 0) {
            throw new ApiDomainException(HttpStatus.CONFLICT.value(), "MODULE_NOT_EMPTY",
                    "模块不为空", Map.of("childCount", childCount, "apiCount", apiCount));
        }
        List<ModuleRecord> siblings = new ArrayList<>(modules.findActiveSiblings(projectId, current.parentId()));
        siblings.removeIf(item -> item.id().equals(moduleId));
        normalize(siblings, projectId, actorId);
        updateOrConflict(modules.archive(projectId, moduleId, revision, actorId), current, revision);
    }

    private void ensureNoCycle(UUID projectId, UUID moduleId, UUID parentId) {
        UUID cursor = parentId;
        Set<UUID> seen = new HashSet<>();
        while (cursor != null && seen.add(cursor)) {
            if (cursor.equals(moduleId)) {
                throw conflict("MODULE_CYCLE", "模块不能移动到自身或后代");
            }
            ModuleRecord parent = modules.findActiveById(projectId, cursor);
            if (parent == null) {
                throw notFound();
            }
            cursor = parent.parentId();
        }
    }

    private void shiftForInsert(List<ModuleRecord> siblings, int target, UUID projectId, UUID actorId) {
        for (int index = target; index < siblings.size(); index++) {
            modules.updateSortOrder(projectId, siblings.get(index).id(), index + 1, actorId);
        }
    }

    private void normalize(List<ModuleRecord> siblings, UUID projectId, UUID actorId) {
        for (int index = 0; index < siblings.size(); index++) {
            if (siblings.get(index).sortOrder() != index) {
                modules.updateSortOrder(projectId, siblings.get(index).id(), index, actorId);
            }
        }
    }

    private List<ModuleNode> nodes(Map<UUID, List<ModuleRecord>> children, UUID parentId) {
        List<ModuleNode> result = new ArrayList<>();
        for (ModuleRecord record : children.getOrDefault(parentId, List.of())) {
            List<ModuleNode> descendants = nodes(children, record.id());
            result.add(new ModuleNode(record.id(), record.projectId(), record.parentId(), record.name(),
                    record.sortOrder(), record.revision(), descendants));
        }
        return result;
    }

    private ProjectRecord lockProject(UUID projectId) {
        ProjectRecord project = projects.findByIdForUpdate(projectId);
        if (project == null) {
            throw notFound();
        }
        return project;
    }

    private ProjectRecord requireProject(UUID projectId) {
        ProjectRecord project = projects.findById(projectId);
        if (project == null) {
            throw notFound();
        }
        return project;
    }

    private void validateWritable(ProjectRecord project) {
        if (project.archived()) {
            throw conflict("PROJECT_ARCHIVED", "项目已归档");
        }
    }

    private ModuleRecord requireActive(UUID projectId, UUID moduleId, boolean forUpdate) {
        ModuleRecord module = forUpdate
                ? modules.findActiveByIdForUpdate(projectId, moduleId)
                : modules.findActiveById(projectId, moduleId);
        if (module == null) {
            throw notFound();
        }
        return module;
    }

    private static void checkRevision(ModuleRecord current, Integer revision) {
        if (revision == null || revision != current.revision()) {
            throw new ApiDomainException(HttpStatus.CONFLICT.value(), "REVISION_CONFLICT",
                    "模块版本已变化", Map.of("currentRevision", current.revision(),
                            "requestedRevision", revision == null ? -1 : revision));
        }
    }

    private static void updateOrConflict(int updated, ModuleRecord current, int revision) {
        if (updated != 1) {
            throw new ApiDomainException(HttpStatus.CONFLICT.value(), "REVISION_CONFLICT",
                    "模块版本已变化", Map.of("currentRevision", current.revision(),
                            "requestedRevision", revision));
        }
    }

    private static void validatePosition(Integer position) {
        if (position != null && position < 0) {
            throw new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", "位置不能为负数");
        }
    }

    private static String name(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", "模块名称不能为空");
        }
        return normalized;
    }

    private static ApiDomainException notFound() {
        return new ApiDomainException(HttpStatus.NOT_FOUND.value(), "RESOURCE_NOT_FOUND", "资源不存在");
    }

    private static ApiDomainException conflict(String code, String message) {
        return new ApiDomainException(HttpStatus.CONFLICT.value(), code, message);
    }
}

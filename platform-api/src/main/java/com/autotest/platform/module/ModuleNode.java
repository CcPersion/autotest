package com.autotest.platform.module;

import java.util.List;
import java.util.UUID;

public record ModuleNode(UUID id, UUID projectId, UUID parentId, String name,
                         int sortOrder, int revision, List<ModuleNode> children) {
    static ModuleNode leaf(ModuleRecord module) {
        return new ModuleNode(module.id(), module.projectId(), module.parentId(), module.name(),
                module.sortOrder(), module.revision(), List.of());
    }
}

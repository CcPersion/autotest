package com.autotest.platform.module;

import com.autotest.platform.auth.UserAccount;
import com.autotest.platform.auth.UserRepository;
import com.autotest.platform.security.ApiDomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/modules")
public class ModuleController {

    private final ModuleService service;
    private final UserRepository users;

    public ModuleController(ModuleService service, UserRepository users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping("/tree")
    public List<ModuleNode> tree(@PathVariable UUID projectId) {
        return service.tree(projectId);
    }

    @PostMapping
    public ResponseEntity<ModuleNode> create(@PathVariable UUID projectId,
                                             @RequestBody CreateModuleRequest request,
                                             Authentication authentication) {
        ModuleNode module = service.create(projectId, request == null ? null : request.name(),
                request == null ? null : request.parentId(), request == null ? null : request.position(), actor(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(module);
    }

    @PutMapping("/{moduleId}")
    public ModuleNode rename(@PathVariable UUID projectId, @PathVariable UUID moduleId,
                             @RequestBody RenameModuleRequest request, Authentication authentication) {
        return service.rename(projectId, moduleId, request == null ? null : request.name(),
                request == null ? null : request.revision(), actor(authentication));
    }

    @PostMapping("/{moduleId}/move")
    public ModuleNode move(@PathVariable UUID projectId, @PathVariable UUID moduleId,
                           @RequestBody MoveModuleRequest request, Authentication authentication) {
        return service.move(projectId, moduleId, request == null ? null : request.parentId(),
                request == null ? null : request.position(), request == null ? null : request.revision(), actor(authentication));
    }

    @DeleteMapping("/{moduleId}")
    public ResponseEntity<Void> delete(@PathVariable UUID projectId, @PathVariable UUID moduleId,
                                       @RequestParam Integer revision, Authentication authentication) {
        service.delete(projectId, moduleId, revision, actor(authentication));
        return ResponseEntity.noContent().build();
    }

    private UUID actor(Authentication authentication) {
        UserAccount user = authentication == null ? null : users.findByUsername(authentication.getName());
        if (user == null) {
            throw new ApiDomainException(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED", "请先登录");
        }
        return user.id();
    }

    public record CreateModuleRequest(String name, UUID parentId, Integer position) {
    }

    public record RenameModuleRequest(String name, Integer revision) {
    }

    public record MoveModuleRequest(UUID parentId, Integer position, Integer revision) {
    }
}

package com.autotest.platform.project;

import com.autotest.platform.auth.UserAccount;
import com.autotest.platform.auth.UserRepository;
import com.autotest.platform.security.ApiDomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService service;
    private final UserRepository users;

    public ProjectController(ProjectService service, UserRepository users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping
    public List<ProjectResponse> list(@RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.list(includeArchived).stream().map(ProjectResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(@RequestBody CreateProjectRequest request,
                                                   Authentication authentication) {
        ProjectRecord project = service.create(request == null ? null : request.name(),
                request == null ? null : request.description(),
                request == null ? null : request.targetAllowlist(), actor(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectResponse.from(project));
    }

    @GetMapping("/{projectId}")
    public ProjectResponse get(@PathVariable UUID projectId) {
        return ProjectResponse.from(service.get(projectId));
    }

    @PutMapping("/{projectId}")
    public ProjectResponse update(@PathVariable UUID projectId,
                                  @RequestBody UpdateProjectRequest request,
                                  Authentication authentication) {
        ProjectRecord project = service.update(projectId,
                request == null ? null : request.name(),
                request == null ? null : request.description(),
                request == null ? null : request.targetAllowlist(),
                request == null ? null : request.revision(),
                actor(authentication));
        return ProjectResponse.from(project);
    }

    @PostMapping("/{projectId}/archive")
    public ProjectResponse archive(@PathVariable UUID projectId,
                                   @RequestBody RevisionRequest request,
                                   Authentication authentication) {
        return ProjectResponse.from(service.archive(projectId,
                request == null ? null : request.revision(), actor(authentication)));
    }

    @PostMapping("/{projectId}/restore")
    public ProjectResponse restore(@PathVariable UUID projectId,
                                   @RequestBody RevisionRequest request,
                                   Authentication authentication) {
        return ProjectResponse.from(service.restore(projectId,
                request == null ? null : request.revision(), actor(authentication)));
    }

    private UUID actor(Authentication authentication) {
        UserAccount user = authentication == null ? null : users.findByUsername(authentication.getName());
        if (user == null) {
            throw new ApiDomainException(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED", "请先登录");
        }
        return user.id();
    }

    public record CreateProjectRequest(String name, String description, List<String> targetAllowlist) {
        public CreateProjectRequest(String name, String description) {
            this(name, description, List.of());
        }
    }

    public record UpdateProjectRequest(String name, String description, List<String> targetAllowlist, Integer revision) {
        public UpdateProjectRequest(String name, String description, Integer revision) {
            this(name, description, List.of(), revision);
        }
    }

    public record RevisionRequest(Integer revision) {
    }

    public record ProjectResponse(UUID id, String name, String description, int revision,
                                 boolean archived, Instant createdAt, Instant updatedAt,
                                 List<String> targetAllowlist) {
        private static ProjectResponse from(ProjectRecord project) {
            return new ProjectResponse(project.id(), project.name(), project.description(), project.revision(),
                    project.archived(), project.createdAt(), project.updatedAt(), project.targetAllowlist());
        }
    }
}

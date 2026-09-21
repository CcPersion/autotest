package com.autotest.platform.api;

import com.autotest.platform.auth.UserAccount;
import com.autotest.platform.auth.UserRepository;
import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/api-definitions")
public class ApiDefinitionController {

    private final ApiDefinitionService service;
    private final UserRepository users;

    public ApiDefinitionController(ApiDefinitionService service, UserRepository users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping
    public List<ApiDefinitionResponse> list(@PathVariable UUID projectId,
                                            @RequestParam(required = false) UUID moduleId,
                                            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.list(projectId, moduleId, includeArchived).stream()
                .map(ApiDefinitionResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<ApiDefinitionResponse> create(@PathVariable UUID projectId,
                                                        @RequestBody ApiDefinitionWrite request,
                                                        Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiDefinitionResponse.from(service.create(projectId, request, actor(authentication))));
    }

    @GetMapping("/{definitionId}")
    public ApiDefinitionResponse get(@PathVariable UUID projectId, @PathVariable UUID definitionId) {
        return ApiDefinitionResponse.from(service.get(projectId, definitionId));
    }

    @PutMapping("/{definitionId}")
    public ApiDefinitionResponse update(@PathVariable UUID projectId, @PathVariable UUID definitionId,
                                        @RequestBody ApiDefinitionWrite request,
                                        Authentication authentication) {
        return ApiDefinitionResponse.from(service.update(projectId, definitionId, request, actor(authentication)));
    }

    @PostMapping("/{definitionId}/archive")
    public ApiDefinitionResponse archive(@PathVariable UUID projectId, @PathVariable UUID definitionId,
                                         @RequestBody RevisionRequest request,
                                         Authentication authentication) {
        return ApiDefinitionResponse.from(service.archive(projectId, definitionId,
                request == null ? null : request.revision(), actor(authentication)));
    }

    private UUID actor(Authentication authentication) {
        UserAccount user = authentication == null ? null : users.findByUsername(authentication.getName());
        if (user == null) {
            throw new ApiDomainException(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED", "请先登录");
        }
        return user.id();
    }

    public record RevisionRequest(Integer revision) {

        @JsonAnySetter
        public void rejectUnknownField(String ignoredName, JsonNode ignoredValue) {
            throw new IllegalArgumentException("请求格式不正确");
        }
    }
}

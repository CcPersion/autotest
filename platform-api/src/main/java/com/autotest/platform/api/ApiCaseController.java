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
@RequestMapping("/api/v1/projects/{projectId}/api-definitions/{definitionId}/cases")
public class ApiCaseController {

    private final ApiCaseService service;
    private final UserRepository users;

    public ApiCaseController(ApiCaseService service, UserRepository users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping
    public List<ApiCaseResponse> list(@PathVariable UUID projectId, @PathVariable UUID definitionId,
                                      @RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.list(projectId, definitionId, includeArchived).stream()
                .map(ApiCaseResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<ApiCaseResponse> create(@PathVariable UUID projectId, @PathVariable UUID definitionId,
                                                  @RequestBody ApiCaseWrite request,
                                                  Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiCaseResponse.from(
                service.create(projectId, definitionId, request, actor(authentication))));
    }

    @GetMapping("/{caseId}")
    public ApiCaseResponse get(@PathVariable UUID projectId, @PathVariable UUID definitionId,
                               @PathVariable UUID caseId) {
        return ApiCaseResponse.from(service.get(projectId, definitionId, caseId));
    }

    @PutMapping("/{caseId}")
    public ApiCaseResponse update(@PathVariable UUID projectId, @PathVariable UUID definitionId,
                                  @PathVariable UUID caseId, @RequestBody ApiCaseWrite request,
                                  Authentication authentication) {
        return ApiCaseResponse.from(service.update(projectId, definitionId, caseId, request, actor(authentication)));
    }

    @PostMapping("/{caseId}/archive")
    public ApiCaseResponse archive(@PathVariable UUID projectId, @PathVariable UUID definitionId,
                                   @PathVariable UUID caseId, @RequestBody RevisionRequest request,
                                   Authentication authentication) {
        return ApiCaseResponse.from(service.archive(projectId, definitionId, caseId,
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

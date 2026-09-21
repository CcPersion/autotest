package com.autotest.platform.suite;

import com.autotest.platform.auth.UserAccount;
import com.autotest.platform.auth.UserRepository;
import com.autotest.platform.run.RunController;
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
@RequestMapping("/api/v1/projects/{projectId}/test-suites")
public class TestSuiteController {
    private final TestSuiteService service;
    private final TestSuiteRunService runs;
    private final UserRepository users;

    public TestSuiteController(TestSuiteService service, TestSuiteRunService runs, UserRepository users) {
        this.service = service;
        this.runs = runs;
        this.users = users;
    }

    @GetMapping
    public List<TestSuiteResponse> list(@PathVariable UUID projectId,
                                        @RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.list(projectId, includeArchived).stream().map(TestSuiteResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<TestSuiteResponse> create(@PathVariable UUID projectId, @RequestBody TestSuiteWrite request,
                                                    Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(TestSuiteResponse.from(
                service.create(projectId, request, actor(authentication))));
    }

    @GetMapping("/{suiteId}")
    public TestSuiteResponse get(@PathVariable UUID projectId, @PathVariable UUID suiteId) {
        return TestSuiteResponse.from(service.get(projectId, suiteId));
    }

    @PutMapping("/{suiteId}")
    public TestSuiteResponse update(@PathVariable UUID projectId, @PathVariable UUID suiteId,
                                    @RequestBody TestSuiteWrite request, Authentication authentication) {
        return TestSuiteResponse.from(service.update(projectId, suiteId, request, actor(authentication)));
    }

    @PostMapping("/{suiteId}/archive")
    public TestSuiteResponse archive(@PathVariable UUID projectId, @PathVariable UUID suiteId,
                                     @RequestBody RevisionRequest request, Authentication authentication) {
        return TestSuiteResponse.from(service.archive(projectId, suiteId,
                request == null ? null : request.revision(), actor(authentication)));
    }

    @PostMapping("/{suiteId}/runs")
    public RunController.RunResponse run(@PathVariable UUID projectId, @PathVariable UUID suiteId,
                                         @RequestBody RunRequest request, Authentication authentication) {
        return RunController.RunResponse.from(runs.create(projectId, suiteId,
                request == null ? null : request.environmentId(),
                request == null ? null : request.idempotencyKey(), actor(authentication)));
    }

    private UUID actor(Authentication authentication) {
        UserAccount user = authentication == null ? null : users.findByUsername(authentication.getName());
        if (user == null) throw new ApiDomainException(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED", "请先登录");
        return user.id();
    }

    public record RevisionRequest(Integer revision) {
        @JsonAnySetter
        public void rejectUnknownField(String ignoredName, JsonNode ignoredValue) {
            throw new IllegalArgumentException("请求格式不正确");
        }
    }

    public record RunRequest(UUID environmentId, String idempotencyKey) {
        @JsonAnySetter
        public void rejectUnknownField(String ignoredName, JsonNode ignoredValue) {
            throw new IllegalArgumentException("请求格式不正确");
        }
    }

}

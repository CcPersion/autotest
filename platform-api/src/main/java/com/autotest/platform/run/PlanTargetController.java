package com.autotest.platform.run;

import com.autotest.platform.audit.AuditService;
import com.autotest.platform.auth.UserAccount;
import com.autotest.platform.auth.UserRepository;
import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class PlanTargetController {
    private final PlanPreviewService service;
    private final UserRepository users;
    private final AuditService audit;

    public PlanTargetController(PlanPreviewService service, UserRepository users, AuditService audit) {
        this.service = service;
        this.users = users;
        this.audit = audit;
    }

    @PostMapping("/preview")
    public PreviewView preview(@PathVariable UUID projectId, @RequestBody JsonNode request) {
        return service.preview(projectId, request);
    }

    @PostMapping("/debug-runs")
    public ResponseEntity<RunController.RunResponse> debug(@PathVariable UUID projectId,
                                                            @RequestBody JsonNode request,
                                                            Authentication authentication,
                                                            HttpServletRequest servletRequest) {
        UserAccount user = user(authentication);
        RunService.CreateResult result = service.debug(projectId, request, user.id());
        return ResponseEntity.status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(RunController.RunResponse.from(result.run()));
    }

    private UserAccount user(Authentication authentication) {
        UserAccount user = authentication == null ? null : users.findByUsername(authentication.getName());
        if (user == null) throw new ApiDomainException(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED", "请先登录");
        return user;
    }
}

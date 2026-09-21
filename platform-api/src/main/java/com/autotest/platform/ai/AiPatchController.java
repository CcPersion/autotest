package com.autotest.platform.ai;

import com.autotest.platform.audit.AuditService;
import com.autotest.platform.auth.UserAccount;
import com.autotest.platform.auth.UserRepository;
import com.autotest.platform.security.ApiDomainException;
import com.autotest.platform.security.TraceIdFilter;
import org.springframework.http.HttpStatus;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/ai/patches")
public class AiPatchController {
    private final AiPatchService patches;
    private final UserRepository users;
    private final AuditService audit;

    public AiPatchController(AiPatchService patches, UserRepository users, AuditService audit) {
        this.patches = patches;
        this.users = users;
        this.audit = audit;
    }

    @PostMapping("/preview")
    public AiPatchPreviewResponse preview(@PathVariable UUID projectId, @RequestBody AiPatchWrite request,
                                           Authentication authentication) {
        return patches.preview(projectId, request, actor(authentication));
    }

    @PostMapping("/confirm")
    public AiPatchConfirmResponse confirm(@PathVariable UUID projectId, @RequestBody AiPatchConfirmRequest request,
                                          Authentication authentication, HttpServletRequest httpRequest) {
        UUID actorId = actor(authentication);
        AiPatchConfirmResponse response = patches.confirm(projectId, request, actorId);
        audit.record(actorId, projectId, "AI_PATCH_CONFIRMED", "AI_PATCH",
                request == null ? null : request.previewId(), TraceIdFilter.traceId(httpRequest), null, null,
                JsonNodeFactory.instance.objectNode().put("targetType", response.targetType()));
        return response;
    }

    private UUID actor(Authentication authentication) {
        UserAccount user = authentication == null ? null : users.findByUsername(authentication.getName());
        if (user == null) {
            throw new ApiDomainException(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED", "请先登录");
        }
        return user.id();
    }
}

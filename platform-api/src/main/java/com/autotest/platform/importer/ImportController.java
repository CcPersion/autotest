package com.autotest.platform.importer;

import com.autotest.platform.auth.UserAccount;
import com.autotest.platform.auth.UserRepository;
import com.autotest.platform.security.ApiDomainException;
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
@RequestMapping("/api/v1/projects/{projectId}/imports")
public class ImportController {
    private final ImportService service;
    private final UserRepository users;

    public ImportController(ImportService service, UserRepository users) {
        this.service = service;
        this.users = users;
    }

    @PostMapping("/curl/preview")
    public ImportPreviewResponse previewCurl(@PathVariable UUID projectId, @RequestBody ImportPreviewRequest request,
                                              Authentication authentication) {
        return service.previewCurl(projectId, request, actor(authentication));
    }

    @PostMapping("/openapi/preview")
    public ImportPreviewResponse previewOpenApi(@PathVariable UUID projectId, @RequestBody ImportPreviewRequest request,
                                                Authentication authentication) {
        return service.previewOpenApi(projectId, request, actor(authentication));
    }

    @PostMapping("/confirm")
    public ResponseEntity<ImportConfirmResponse> confirm(@PathVariable UUID projectId,
                                                         @RequestBody ImportConfirmRequest request,
                                                         Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.confirm(projectId, request, actor(authentication)));
    }

    private UUID actor(Authentication authentication) {
        UserAccount user = authentication == null ? null : users.findByUsername(authentication.getName());
        if (user == null) {
            throw new ApiDomainException(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED", "请先登录");
        }
        return user.id();
    }
}

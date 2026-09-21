package com.autotest.platform.file;

import com.autotest.platform.auth.UserAccount;
import com.autotest.platform.auth.UserRepository;
import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/files")
public class FileAssetController {
    private final FileAssetService service;
    private final UserRepository users;

    public FileAssetController(FileAssetService service, UserRepository users) {
        this.service = service;
        this.users = users;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<FileAssetView> upload(@PathVariable UUID projectId,
                                                @RequestPart("file") MultipartFile file,
                                                @RequestParam("kind") String kind,
                                                Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.upload(projectId, file, kind, actor(authentication)));
    }

    @GetMapping
    public List<FileAssetView> list(@PathVariable UUID projectId,
                                    @RequestParam(required = false) String status) {
        return service.list(projectId, status);
    }

    @PostMapping("/{fileId}/archive")
    public FileAssetView archive(@PathVariable UUID projectId, @PathVariable UUID fileId,
                                 @RequestBody RevisionRequest request, Authentication authentication) {
        return service.archive(projectId, fileId, request == null ? null : request.revision(), actor(authentication));
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

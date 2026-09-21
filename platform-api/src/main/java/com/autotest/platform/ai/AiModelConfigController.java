package com.autotest.platform.ai;

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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ai/models")
public class AiModelConfigController {
    private final AiModelConfigService service;
    private final UserRepository users;

    public AiModelConfigController(AiModelConfigService service, UserRepository users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping
    public List<AiModelConfigResponse> list() {
        return service.list().stream().map(AiModelConfigResponse::from).toList();
    }

    @GetMapping("/{id}")
    public AiModelConfigResponse get(@PathVariable UUID id) {
        return AiModelConfigResponse.from(service.get(id));
    }

    @PostMapping
    public ResponseEntity<AiModelConfigResponse> create(@RequestBody AiModelConfigWrite write,
                                                        Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AiModelConfigResponse.from(service.create(write, actor(authentication))));
    }

    @PutMapping("/{id}")
    public AiModelConfigResponse update(@PathVariable UUID id, @RequestBody AiModelConfigWrite write,
                                        Authentication authentication) {
        return AiModelConfigResponse.from(service.update(id, write, actor(authentication)));
    }

    private UUID actor(Authentication authentication) {
        UserAccount user = authentication == null ? null : users.findByUsername(authentication.getName());
        if (user == null) {
            throw new ApiDomainException(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED", "请先登录");
        }
        return user.id();
    }
}

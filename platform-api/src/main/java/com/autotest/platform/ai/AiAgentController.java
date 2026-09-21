package com.autotest.platform.ai;

import com.autotest.platform.auth.UserAccount;
import com.autotest.platform.auth.UserRepository;
import com.autotest.platform.security.ApiDomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/ai")
public class AiAgentController {
    private final AiSessionService sessions;
    private final UserRepository users;

    public AiAgentController(AiSessionService sessions, UserRepository users) {
        this.sessions = sessions;
        this.users = users;
    }

    @GetMapping("/sessions")
    public List<AiSessionRecord> list(@PathVariable UUID projectId) {
        return sessions.list(projectId);
    }

    @GetMapping("/sessions/{sessionId}")
    public AiSessionResponse get(@PathVariable UUID projectId, @PathVariable UUID sessionId) {
        return sessions.get(projectId, sessionId);
    }

    @PostMapping("/sessions")
    public AiSessionResponse create(@PathVariable UUID projectId, @RequestBody AiSessionWrite write,
                                    Authentication authentication) {
        AiSessionRecord session = sessions.create(projectId, write, actor(authentication));
        return sessions.get(projectId, session.id());
    }

    @RequestMapping(value = "/sessions/{sessionId}/messages", method = RequestMethod.POST,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter message(@PathVariable UUID projectId, @PathVariable UUID sessionId,
                              @RequestBody AiMessageWrite write, Authentication authentication) {
        actor(authentication);
        SseEmitter emitter = new SseEmitter(60_000L);
        CompletableFuture.runAsync(() -> {
            try {
                String content = write == null ? null : write.content();
                for (AiStreamEvent event : sessions.send(projectId, sessionId, content)) {
                    emitter.send(SseEmitter.event().name(event.type()).data(event));
                }
                emitter.complete();
            } catch (IOException exception) {
                emitter.completeWithError(new IllegalStateException("AI 流式响应中断"));
            } catch (RuntimeException exception) {
                emitter.completeWithError(exception);
            }
        });
        return emitter;
    }

    private UUID actor(Authentication authentication) {
        UserAccount user = authentication == null ? null : users.findByUsername(authentication.getName());
        if (user == null) {
            throw new ApiDomainException(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED", "请先登录");
        }
        return user.id();
    }
}

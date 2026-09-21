package com.autotest.platform.schedule;

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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/schedules")
public class ScheduleController {
    private final ScheduleService service;
    private final UserRepository users;

    public ScheduleController(ScheduleService service, UserRepository users) {
        this.service = service;
        this.users = users;
    }

    @GetMapping
    public List<ScheduleResponse> list(@PathVariable UUID projectId) {
        return service.list(projectId).stream().map(ScheduleResponse::from).toList();
    }

    @PostMapping
    public ResponseEntity<ScheduleResponse> create(@PathVariable UUID projectId, @RequestBody ScheduleWrite request,
                                                   Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ScheduleResponse.from(
                service.create(projectId, request, actor(authentication))));
    }

    @PutMapping("/{scheduleId}")
    public ScheduleResponse update(@PathVariable UUID projectId, @PathVariable UUID scheduleId,
                                   @RequestBody ScheduleWrite request, Authentication authentication) {
        return ScheduleResponse.from(service.update(projectId, scheduleId, request, actor(authentication)));
    }

    private UUID actor(Authentication authentication) {
        UserAccount user = authentication == null ? null : users.findByUsername(authentication.getName());
        if (user == null) throw new ApiDomainException(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED", "请先登录");
        return user.id();
    }

    public record ScheduleResponse(UUID id, UUID projectId, UUID suiteId, UUID environmentId, String name,
                                   String cronExpression, String zoneId, boolean enabled, int revision,
                                   Instant nextRunAt, Instant lastRunAt, Instant createdAt, Instant updatedAt) {
        static ScheduleResponse from(ScheduleRecord record) {
            return new ScheduleResponse(record.id(), record.projectId(), record.suiteId(), record.environmentId(),
                    record.name(), record.cronExpression(), record.zoneId(), record.enabled(), record.revision(),
                    record.nextRunAt(), record.lastRunAt(), record.createdAt(), record.updatedAt());
        }
    }
}

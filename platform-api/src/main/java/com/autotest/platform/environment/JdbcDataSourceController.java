package com.autotest.platform.environment;

import com.autotest.platform.auth.UserAccount;
import com.autotest.platform.auth.UserRepository;
import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/environments/{environmentId}/jdbc-data-sources")
public class JdbcDataSourceController {
    private final JdbcDataSourceService service;
    private final UserRepository users;

    public JdbcDataSourceController(JdbcDataSourceService service, UserRepository users) { this.service = service; this.users = users; }

    @GetMapping
    public List<Response> list(@PathVariable UUID projectId, @PathVariable UUID environmentId,
                               @RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.list(projectId, environmentId, includeArchived).stream().map(Response::from).toList();
    }

    @PostMapping
    public ResponseEntity<Response> create(@PathVariable UUID projectId, @PathVariable UUID environmentId,
                                            @RequestBody Write request, Authentication auth) {
        JdbcDataSourceRecord r = service.create(projectId, environmentId, request == null ? null : request.name(),
                request == null ? null : request.databaseType(), request == null ? null : request.host(),
                request == null ? null : request.port(), request == null ? null : request.databaseName(),
                request == null ? null : request.username(), request == null ? null : request.secretRef(),
                request == null ? null : request.options(), actor(auth));
        return ResponseEntity.status(HttpStatus.CREATED).body(Response.from(r));
    }

    @PutMapping("/{id}")
    public Response update(@PathVariable UUID projectId, @PathVariable UUID environmentId, @PathVariable UUID id,
                           @RequestBody Write request, Authentication auth) {
        JdbcDataSourceRecord r = service.update(projectId, id, environmentId, request == null ? null : request.name(),
                request == null ? null : request.databaseType(), request == null ? null : request.host(),
                request == null ? null : request.port(), request == null ? null : request.databaseName(),
                request == null ? null : request.username(), request == null ? null : request.secretRef(),
                request == null ? null : request.options(), request == null ? null : request.revision(), actor(auth));
        return Response.from(r);
    }

    @PostMapping("/{id}/archive")
    public Response archive(@PathVariable UUID projectId, @PathVariable UUID id, @RequestBody Revision revision, Authentication auth) {
        return Response.from(service.archive(projectId, id, revision == null ? null : revision.revision(), actor(auth)));
    }

    @PostMapping("/{id}/restore")
    public Response restore(@PathVariable UUID projectId, @PathVariable UUID id, @RequestBody Revision revision, Authentication auth) {
        return Response.from(service.restore(projectId, id, revision == null ? null : revision.revision(), actor(auth)));
    }

    @PostMapping("/{id}/test-connection")
    public JdbcDataSourceService.ConnectionTestResult testConnection(@PathVariable UUID projectId, @PathVariable UUID id) {
        return service.testConnection(projectId, id);
    }

    private UUID actor(Authentication auth) {
        UserAccount user = auth == null ? null : users.findByUsername(auth.getName());
        if (user == null) throw new ApiDomainException(HttpStatus.UNAUTHORIZED.value(), "AUTHENTICATION_REQUIRED", "请先登录");
        return user.id();
    }

    public record Write(String name, String databaseType, String host, Integer port, String databaseName,
                        String username, String secretRef, JsonNode options, Integer revision) {}
    public record Revision(Integer revision) {}
    public record Response(UUID id, UUID projectId, UUID environmentId, String name, String databaseType,
                           String host, int port, String databaseName, String username, String secretRef,
                           JsonNode options, int revision, boolean archived, Instant createdAt, Instant updatedAt) {
        static Response from(JdbcDataSourceRecord r) { return new Response(r.id(), r.projectId(), r.environmentId(), r.name(), r.databaseType(), r.host(), r.port(), r.databaseName(), r.username(), r.secretRef(), r.options(), r.revision(), r.archived(), r.createdAt(), r.updatedAt()); }
    }
}

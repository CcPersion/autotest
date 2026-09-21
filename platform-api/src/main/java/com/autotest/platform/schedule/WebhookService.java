package com.autotest.platform.schedule;

import com.autotest.platform.audit.AuditService;
import com.autotest.platform.project.ProjectRecord;
import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.secret.SecretRepository;
import com.autotest.platform.secret.SecretService;
import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class WebhookService {
    private static final List<String> DEFAULT_EVENTS = List.of("RUN_FINISHED");
    private final WebhookRepository webhooks;
    private final ProjectRepository projects;
    private final SecretRepository secrets;
    private final SecretService secretService;
    private final ObjectMapper json;
    private final WebhookTransport transport;
    private final AuditService audit;

    public WebhookService(WebhookRepository webhooks, ProjectRepository projects, SecretRepository secrets,
                          SecretService secretService, ObjectMapper json, WebhookTransport transport) {
        this(webhooks, projects, secrets, secretService, json, transport, null);
    }

    @Autowired
    WebhookService(WebhookRepository webhooks, ProjectRepository projects, SecretRepository secrets,
                   SecretService secretService, ObjectMapper json, WebhookTransport transport, AuditService audit) {
        this.webhooks = webhooks;
        this.projects = projects;
        this.secrets = secrets;
        this.secretService = secretService;
        this.json = json;
        this.transport = transport;
        this.audit = audit;
    }

    public List<WebhookRecord> list(UUID projectId) {
        requireProject(projectId);
        return webhooks.findAll(projectId);
    }

    @Transactional
    public WebhookRecord create(UUID projectId, WebhookWrite request, UUID actorId) {
        writableProject(projectId);
        Normalized normalized = normalize(projectId, request, null);
        if (webhooks.existsName(projectId, normalized.name(), null)) throw conflict("NAME_CONFLICT", "Webhook 名称已存在");
        try {
            return webhooks.insert(projectId, normalized.name(), normalized.url(), normalized.secretRef(), normalized.events(),
                    normalized.enabled(), actorId);
        } catch (DuplicateKeyException exception) {
            throw conflict("NAME_CONFLICT", "Webhook 名称已存在");
        }
    }

    @Transactional
    public WebhookRecord update(UUID projectId, UUID webhookId, WebhookWrite request, UUID actorId) {
        writableProject(projectId);
        WebhookRecord current = webhooks.findByIdForUpdate(projectId, webhookId);
        if (current == null) throw notFound();
        if (request == null || request.revision() == null || request.revision() != current.revision()) {
            throw revisionConflict(current.revision(), request == null ? null : request.revision());
        }
        Normalized normalized = normalize(projectId, request, current);
        if (webhooks.existsName(projectId, normalized.name(), webhookId)) throw conflict("NAME_CONFLICT", "Webhook 名称已存在");
        if (webhooks.update(projectId, webhookId, normalized.name(), normalized.url(), normalized.secretRef(), normalized.events(),
                normalized.enabled(), current.revision(), actorId) != 1) {
            throw revisionConflict(current.revision(), request.revision());
        }
        return webhooks.findById(projectId, webhookId);
    }

    /** 发送运行完成摘要；每个 Webhook 最多尝试三次，不外发完整报告。 */
    public int notify(UUID projectId, String event, JsonNode source) {
        String normalizedEvent = event == null ? "" : event.strip().toUpperCase(Locale.ROOT);
        if (normalizedEvent.isEmpty()) return 0;
        String body;
        try {
            body = json.writeValueAsString(WebhookSecurity.sanitizePayload(source));
        } catch (Exception exception) {
            throw new IllegalStateException("Webhook 正文生成失败", exception);
        }
        int delivered = 0;
        for (WebhookRecord webhook : webhooks.findEnabled(projectId, normalizedEvent)) {
            String secret = secretService.resolveForRunner(projectId, webhook.secretRef());
            Map<String, String> headers = Map.of("Content-Type", "application/json", "X-Autotest-Event", normalizedEvent,
                    "X-Autotest-Signature", WebhookSecurity.sign(secret, body));
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    int status = transport.post(webhook.url(), headers, body);
                    if (status >= 200 && status < 300) {
                        delivered++;
                        break;
                    }
                } catch (Exception ignored) {
                    // 单个外部通知失败不能覆盖运行结果；下一次尝试仍使用同一脱敏正文。
                }
            }
        }
        if (audit != null) {
            audit.record(null, projectId, "WEBHOOK_NOTIFIED", "WEBHOOK", null, null, null, null,
                    json.createObjectNode().put("event", normalizedEvent).put("delivered", delivered));
        }
        return delivered;
    }

    private Normalized normalize(UUID projectId, WebhookWrite request, WebhookRecord current) {
        if (request == null) throw validation("Webhook 请求不能为空");
        String name = required(request.name() == null && current != null ? current.name() : request.name(), "Webhook 名称不合法", 128);
        String url = request.url() == null && current != null ? current.url() : request.url();
        validateUrl(url);
        String secretRef = request.secretRef() == null && current != null ? current.secretRef() : request.secretRef();
        if (secretRef == null || secretRef.isBlank() || secrets.findActiveByName(projectId, secretRef.strip()) == null) {
            throw validation("Webhook Secret 必须引用项目内已存在的密钥");
        }
        List<String> events = request.events() == null && current != null ? current.events()
                : (request.events() == null || request.events().isEmpty() ? DEFAULT_EVENTS : request.events());
        if (events.stream().anyMatch(value -> value == null || !value.matches("[A-Z][A-Z0-9_]{1,63}"))) {
            throw validation("Webhook 事件名不合法");
        }
        boolean enabled = request.enabled() == null ? current == null || current.enabled() : request.enabled();
        return new Normalized(name, url.strip(), secretRef.strip(), List.copyOf(events), enabled);
    }

    private static void validateUrl(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.strip());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
        } catch (RuntimeException exception) {
            throw validation("Webhook URL 仅支持合法 HTTP/HTTPS 地址");
        }
    }

    private ProjectRecord writableProject(UUID projectId) {
        ProjectRecord project = projects.findByIdForUpdate(projectId);
        if (project == null) throw notFound();
        if (project.archived()) throw conflict("PROJECT_ARCHIVED", "项目已归档");
        return project;
    }

    private void requireProject(UUID projectId) {
        if (projects.findById(projectId) == null) throw notFound();
    }

    private static String required(String value, String message, int max) {
        String result = value == null ? "" : value.strip();
        if (result.isEmpty() || result.length() > max) throw validation(message);
        return result;
    }

    private static ApiDomainException validation(String message) {
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", message);
    }

    private static ApiDomainException notFound() {
        return new ApiDomainException(HttpStatus.NOT_FOUND.value(), "RESOURCE_NOT_FOUND", "资源不存在");
    }

    private static ApiDomainException conflict(String code, String message) {
        return new ApiDomainException(HttpStatus.CONFLICT.value(), code, message);
    }

    private static ApiDomainException revisionConflict(int current, Integer requested) {
        return new ApiDomainException(HttpStatus.CONFLICT.value(), "REVISION_CONFLICT", "Webhook 版本已变化",
                Map.of("currentRevision", current, "requestedRevision", requested == null ? -1 : requested));
    }

    private record Normalized(String name, String url, String secretRef, List<String> events, boolean enabled) {
    }
}

package com.autotest.platform.environment;

import com.autotest.platform.project.ProjectRecord;
import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.api.ApiSpecValidator;
import com.autotest.platform.secret.SecretRepository;
import com.autotest.platform.security.ApiDomainException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EnvironmentService {

    private static final Pattern SECRET_REFERENCE = Pattern.compile(
            "\\$\\{secret:([A-Za-z0-9][A-Za-z0-9._-]*)\\}");
    private final EnvironmentRepository environments;
    private final ProjectRepository projects;
    private final SecretRepository secrets;

    public EnvironmentService(EnvironmentRepository environments, ProjectRepository projects, SecretRepository secrets) {
        this.environments = environments;
        this.projects = projects;
        this.secrets = secrets;
    }

    public List<EnvironmentRecord> list(UUID projectId, boolean includeArchived) {
        requireProject(projectId);
        return environments.findAll(projectId, includeArchived);
    }

    public EnvironmentRecord get(UUID projectId, UUID environmentId) {
        requireProject(projectId);
        EnvironmentRecord environment = environments.findById(projectId, environmentId);
        if (environment == null) {
            throw notFound();
        }
        return environment;
    }

    @Transactional
    public EnvironmentRecord create(UUID projectId, String name, String baseUrl, JsonNode variables,
                                    JsonNode requestOptions, UUID actorId) {
        ProjectRecord project = lockProject(projectId);
        writable(project);
        String normalizedName = name(name);
        String normalizedBaseUrl = baseUrl(baseUrl);
        variables(projectId, variables);
        JsonNode normalizedOptions = requestOptions(requestOptions, projectId);
        if (environments.existsActiveName(projectId, normalizedName)) {
            throw conflict("NAME_CONFLICT", "环境名称已存在");
        }
        try {
            return environments.insert(projectId, normalizedName, normalizedBaseUrl, variables, normalizedOptions, actorId);
        } catch (DuplicateKeyException exception) {
            throw conflict("NAME_CONFLICT", "环境名称已存在");
        }
    }

    @Transactional
    public EnvironmentRecord update(UUID projectId, UUID environmentId, String name, String baseUrl,
                                    JsonNode variables, JsonNode requestOptions, Integer revision, UUID actorId) {
        ProjectRecord project = lockProject(projectId);
        writable(project);
        EnvironmentRecord current = requireForUpdate(projectId, environmentId);
        checkRevision(current, revision);
        String normalizedName = name(name);
        String normalizedBaseUrl = baseUrl(baseUrl);
        variables(projectId, variables);
        JsonNode normalizedOptions = requestOptions(requestOptions, projectId);
        if (environments.existsActiveNameExcluding(projectId, normalizedName, environmentId)) {
            throw conflict("NAME_CONFLICT", "环境名称已存在");
        }
        try {
            updateOrConflict(environments.updateDetails(projectId, environmentId, normalizedName, normalizedBaseUrl,
                    variables, normalizedOptions, revision, actorId), current, revision);
        } catch (DuplicateKeyException exception) {
            throw conflict("NAME_CONFLICT", "环境名称已存在");
        }
        return get(projectId, environmentId);
    }

    @Transactional
    public EnvironmentRecord archive(UUID projectId, UUID environmentId, Integer revision, UUID actorId) {
        ProjectRecord project = lockProject(projectId);
        writable(project);
        EnvironmentRecord current = requireForUpdate(projectId, environmentId);
        checkRevision(current, revision);
        updateOrConflict(environments.updateArchived(projectId, environmentId, true, revision, actorId), current, revision);
        return get(projectId, environmentId);
    }

    @Transactional
    public EnvironmentRecord restore(UUID projectId, UUID environmentId, Integer revision, UUID actorId) {
        ProjectRecord project = lockProject(projectId);
        writable(project);
        EnvironmentRecord current = requireForUpdate(projectId, environmentId);
        checkRevision(current, revision);
        if (!current.archived()) {
            throw conflict("REVISION_CONFLICT", "环境版本已变化");
        }
        if (environments.existsActiveNameExcluding(projectId, current.name(), environmentId)) {
            throw conflict("NAME_CONFLICT", "环境名称已存在");
        }
        updateOrConflict(environments.updateArchived(projectId, environmentId, false, revision, actorId), current, revision);
        return get(projectId, environmentId);
    }

    private void variables(UUID projectId, JsonNode variables) {
        if (variables == null || !variables.isObject()) {
            throw validation("环境变量必须是 JSON 对象");
        }
        validateReferences(projectId, variables);
    }

    private JsonNode requestOptions(JsonNode value, UUID projectId) {
        JsonNode normalized = value == null || value.isNull()
                ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : value;
        ApiSpecValidator.validateOptions(projectId, normalized, "requestOptions", secrets);
        return normalized;
    }

    private void validateReferences(UUID projectId, JsonNode node) {
        if (node.isTextual()) {
            String text = node.textValue();
            int start = text.indexOf("${secret:");
            while (start >= 0) {
                Matcher matcher = SECRET_REFERENCE.matcher(text.substring(start));
                if (!matcher.lookingAt()) {
                    throw invalidSecretReference();
                }
                String referenceName = matcher.group(1);
                if (secrets.findActiveByName(projectId, referenceName) == null) {
                    throw new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "SECRET_REFERENCE_NOT_FOUND",
                            "密钥引用不存在");
                }
                start = text.indexOf("${secret:", start + matcher.end());
            }
            return;
        }
        if (node.isObject()) {
            Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) {
                validateReferences(projectId, values.next());
            }
        } else if (node.isArray()) {
            for (JsonNode value : node) {
                validateReferences(projectId, value);
            }
        }
    }

    private ProjectRecord lockProject(UUID projectId) {
        ProjectRecord project = projects.findByIdForUpdate(projectId);
        if (project == null) {
            throw notFound();
        }
        return project;
    }

    private ProjectRecord requireProject(UUID projectId) {
        ProjectRecord project = projects.findById(projectId);
        if (project == null) {
            throw notFound();
        }
        return project;
    }

    private EnvironmentRecord requireForUpdate(UUID projectId, UUID environmentId) {
        EnvironmentRecord environment = environments.findByIdForUpdate(projectId, environmentId);
        if (environment == null) {
            throw notFound();
        }
        return environment;
    }

    private static void writable(ProjectRecord project) {
        if (project.archived()) {
            throw conflict("PROJECT_ARCHIVED", "项目已归档");
        }
    }

    private static String name(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw validation("环境名称不能为空");
        }
        return normalized;
    }

    private static String baseUrl(String value) {
        String normalized = value == null ? "" : value.strip();
        try {
            URI uri = new URI(normalized);
            if (!uri.isAbsolute() || uri.getHost() == null
                    || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return normalized;
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw validation("baseUrl 不合法");
        }
    }

    private static void checkRevision(EnvironmentRecord current, Integer revision) {
        if (revision == null || revision != current.revision()) {
            throw new ApiDomainException(HttpStatus.CONFLICT.value(), "REVISION_CONFLICT",
                    "环境版本已变化", Map.of("currentRevision", current.revision(),
                            "requestedRevision", revision == null ? -1 : revision));
        }
    }

    private static void updateOrConflict(int updated, EnvironmentRecord current, int revision) {
        if (updated != 1) {
            throw new ApiDomainException(HttpStatus.CONFLICT.value(), "REVISION_CONFLICT",
                    "环境版本已变化", Map.of("currentRevision", current.revision(),
                            "requestedRevision", revision));
        }
    }

    private static ApiDomainException validation(String message) {
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", message);
    }

    private static ApiDomainException invalidSecretReference() {
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", "密钥引用不合法");
    }

    private static ApiDomainException notFound() {
        return new ApiDomainException(HttpStatus.NOT_FOUND.value(), "RESOURCE_NOT_FOUND", "资源不存在");
    }

    private static ApiDomainException conflict(String code, String message) {
        return new ApiDomainException(HttpStatus.CONFLICT.value(), code, message);
    }
}

package com.autotest.platform.secret;

import com.autotest.platform.project.ProjectRecord;
import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.security.ApiDomainException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class SecretService {

    private static final Pattern SECRET_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private final SecretRepository secrets;
    private final ProjectRepository projects;
    private final SecretCryptoService crypto;

    public SecretService(SecretRepository secrets, ProjectRepository projects, SecretCryptoService crypto) {
        this.secrets = secrets;
        this.projects = projects;
        this.crypto = crypto;
    }

    public List<SecretRecord> list(UUID projectId, boolean includeArchived) {
        requireProject(projectId);
        return secrets.findAll(projectId, includeArchived);
    }

    /** 仅供已通过 Runner 回调令牌校验的内部接口调用，普通用户 API 永不返回明文。 */
    public String resolveForRunner(UUID projectId, String name) {
        requireProject(projectId);
        if (name == null || !SECRET_NAME.matcher(name).matches()) {
            throw notFound();
        }
        SecretRepository.EncryptedSecret material = secrets.findActiveMaterialByName(projectId, name);
        if (material == null) {
            throw notFound();
        }
        return new String(crypto.decrypt(projectId, material.id(), material.ciphertext(), material.nonce()),
                StandardCharsets.UTF_8);
    }

    @Transactional
    public SecretRecord create(UUID projectId, String name, String value, UUID actorId) {
        ProjectRecord project = lockProject(projectId);
        writable(project);
        String normalizedName = name(name);
        value(value);
        if (secrets.existsActiveName(projectId, normalizedName)) {
            throw conflict("NAME_CONFLICT", "密钥名称已存在");
        }
        UUID secretId = UUID.randomUUID();
        SecretCryptoService.EncryptedValue encrypted = crypto.encrypt(projectId, secretId, value);
        try {
            return secrets.insert(projectId, secretId, normalizedName, encrypted.ciphertext(), encrypted.nonce(), actorId);
        } catch (DuplicateKeyException exception) {
            throw conflict("NAME_CONFLICT", "密钥名称已存在");
        }
    }

    @Transactional
    public SecretRecord replace(UUID projectId, UUID secretId, String value, Integer revision, UUID actorId) {
        ProjectRecord project = lockProject(projectId);
        writable(project);
        SecretRecord current = requireActive(projectId, secretId);
        checkRevision(current, revision);
        value(value);
        SecretCryptoService.EncryptedValue encrypted = crypto.encrypt(projectId, secretId, value);
        updateOrConflict(secrets.updateCiphertext(projectId, secretId, encrypted.ciphertext(), encrypted.nonce(),
                revision, actorId), current, revision);
        return requireActive(projectId, secretId);
    }

    @Transactional
    public SecretRecord archive(UUID projectId, UUID secretId, Integer revision, UUID actorId) {
        ProjectRecord project = lockProject(projectId);
        writable(project);
        SecretRecord current = requireActive(projectId, secretId);
        checkRevision(current, revision);
        updateOrConflict(secrets.archive(projectId, secretId, revision, actorId), current, revision);
        return secrets.findById(projectId, secretId);
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

    private SecretRecord requireActive(UUID projectId, UUID secretId) {
        SecretRecord secret = secrets.findActiveByIdForUpdate(projectId, secretId);
        if (secret == null) {
            throw notFound();
        }
        return secret;
    }

    private static void writable(ProjectRecord project) {
        if (project.archived()) {
            throw conflict("PROJECT_ARCHIVED", "项目已归档");
        }
    }

    private static String name(String value) {
        String normalized = value == null ? "" : value;
        if (normalized.length() > 128 || !SECRET_NAME.matcher(normalized).matches()) {
            throw validation();
        }
        return normalized;
    }

    private static void value(String value) {
        if (value == null || value.isEmpty()) {
            throw validation();
        }
    }

    private static void checkRevision(SecretRecord current, Integer revision) {
        if (revision == null || revision != current.revision()) {
            throw new ApiDomainException(HttpStatus.CONFLICT.value(), "REVISION_CONFLICT",
                    "密钥版本已变化", Map.of("currentRevision", current.revision(),
                            "requestedRevision", revision == null ? -1 : revision));
        }
    }

    private static void updateOrConflict(int updated, SecretRecord current, int revision) {
        if (updated != 1) {
            throw new ApiDomainException(HttpStatus.CONFLICT.value(), "REVISION_CONFLICT",
                    "密钥版本已变化", Map.of("currentRevision", current.revision(),
                            "requestedRevision", revision));
        }
    }

    private static ApiDomainException validation() {
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", "密钥参数不合法");
    }

    private static ApiDomainException notFound() {
        return new ApiDomainException(HttpStatus.NOT_FOUND.value(), "RESOURCE_NOT_FOUND", "资源不存在");
    }

    private static ApiDomainException conflict(String code, String message) {
        return new ApiDomainException(HttpStatus.CONFLICT.value(), code, message);
    }
}

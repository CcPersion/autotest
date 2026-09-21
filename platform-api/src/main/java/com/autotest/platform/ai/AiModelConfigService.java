package com.autotest.platform.ai;

import com.autotest.platform.security.ApiDomainException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AiModelConfigService {
    private static final Pattern SECRET_REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private final AiModelConfigRepository repository;

    public AiModelConfigService(AiModelConfigRepository repository) {
        this.repository = repository;
    }

    public List<AiModelConfigRecord> list() {
        return repository.findAll();
    }

    public AiModelConfigRecord get(UUID id) {
        AiModelConfigRecord result = repository.findById(id);
        if (result == null) {
            throw notFound();
        }
        return result;
    }

    @Transactional
    public AiModelConfigRecord create(AiModelConfigWrite write, UUID actorId) {
        ValidConfig config = validate(write);
        if (repository.existsName(config.name())) {
            throw conflict("NAME_CONFLICT", "模型配置名称已存在");
        }
        try {
            return repository.insert(config.name(), config.providerType(), config.baseUrl(), config.modelName(),
                    config.secretRef(), config.enabled(), actorId);
        } catch (DuplicateKeyException exception) {
            throw conflict("NAME_CONFLICT", "模型配置名称已存在");
        }
    }

    @Transactional
    public AiModelConfigRecord update(UUID id, AiModelConfigWrite write, UUID actorId) {
        AiModelConfigRecord current = get(id);
        ValidConfig config = validate(write);
        if (repository.existsNameExcluding(config.name(), id)) {
            throw conflict("NAME_CONFLICT", "模型配置名称已存在");
        }
        if (write.revision() == null || write.revision() != current.revision()) {
            throw new ApiDomainException(HttpStatus.CONFLICT.value(), "REVISION_CONFLICT", "模型配置版本已变化",
                    Map.of("currentRevision", current.revision(), "requestedRevision", write.revision() == null ? -1 : write.revision()));
        }
        if (repository.update(id, config.name(), config.providerType(), config.baseUrl(), config.modelName(),
                config.secretRef(), config.enabled(), current.revision(), actorId) != 1) {
            throw conflict("REVISION_CONFLICT", "模型配置版本已变化");
        }
        return get(id);
    }

    private static ValidConfig validate(AiModelConfigWrite write) {
        if (write == null) {
            throw validation();
        }
        String name = trim(write.name());
        String baseUrl = trim(write.baseUrl());
        String modelName = trim(write.modelName());
        String secretRef = trimNullable(write.apiKeySecretRef());
        if (name.isBlank() || name.length() > 128 || baseUrl.isBlank() || baseUrl.length() > 512
                || modelName.isBlank() || modelName.length() > 128) {
            throw validation();
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException exception) {
            throw validation();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!List.of("https", "http", "fake").contains(scheme)
                || (secretRef != null && !SECRET_REF.matcher(secretRef).matches())) {
            throw validation();
        }
        String provider = "fake".equals(scheme) ? "FAKE" : "OPENAI_COMPATIBLE";
        return new ValidConfig(name, baseUrl, modelName, secretRef, !Boolean.FALSE.equals(write.enabled()), provider);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimNullable(String value) {
        String trimmed = trim(value);
        return trimmed.isBlank() ? null : trimmed;
    }

    private static ApiDomainException validation() {
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", "模型配置参数不合法");
    }

    private static ApiDomainException notFound() {
        return new ApiDomainException(HttpStatus.NOT_FOUND.value(), "RESOURCE_NOT_FOUND", "模型配置不存在");
    }

    private static ApiDomainException conflict(String code, String message) {
        return new ApiDomainException(HttpStatus.CONFLICT.value(), code, message);
    }

    private record ValidConfig(String name, String baseUrl, String modelName, String secretRef,
                               boolean enabled, String providerType) {
    }
}

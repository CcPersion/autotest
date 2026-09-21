package com.autotest.platform.schedule;

import com.autotest.platform.project.ProjectRecord;
import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.security.ApiDomainException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class TriggerTokenService {
    private final TriggerTokenRepository tokens;
    private final ProjectRepository projects;

    public TriggerTokenService(TriggerTokenRepository tokens, ProjectRepository projects) {
        this.tokens = tokens;
        this.projects = projects;
    }

    public List<TriggerTokenRecord> list(UUID projectId) {
        requireProject(projectId);
        return tokens.findAll(projectId);
    }

    @Transactional
    public IssuedToken create(UUID projectId, String name, UUID actorId) {
        ProjectRecord project = projects.findByIdForUpdate(projectId);
        if (project == null) throw notFound();
        if (project.archived()) throw conflict("PROJECT_ARCHIVED", "项目已归档");
        String normalized = name == null ? "" : name.strip();
        if (normalized.isEmpty() || normalized.length() > 128) throw validation("触发令牌名称不合法");
        if (tokens.existsName(projectId, normalized)) throw conflict("NAME_CONFLICT", "触发令牌名称已存在");
        String token = TriggerTokenCodec.issue();
        try {
            return new IssuedToken(tokens.insert(projectId, normalized, TriggerTokenCodec.hash(token), actorId), token);
        } catch (DuplicateKeyException exception) {
            throw conflict("NAME_CONFLICT", "触发令牌名称已存在");
        }
    }

    @Transactional
    public void revoke(UUID projectId, UUID tokenId) {
        requireProject(projectId);
        if (tokens.revoke(projectId, tokenId) != 1) throw notFound();
    }

    @Transactional
    public TriggerTokenRecord authenticate(UUID projectId, String token) {
        requireProject(projectId);
        if (token == null || token.isBlank()) throw unauthorized();
        TriggerTokenRecord match = tokens.findByHash(projectId, TriggerTokenCodec.hash(token));
        if (match == null || !TriggerTokenCodec.matches(token, match.tokenHash())) throw unauthorized();
        tokens.touch(projectId, match.id());
        return match;
    }

    private void requireProject(UUID projectId) {
        if (projects.findById(projectId) == null) throw notFound();
    }

    private static ApiDomainException validation(String message) {
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", message);
    }

    private static ApiDomainException notFound() {
        return new ApiDomainException(HttpStatus.NOT_FOUND.value(), "RESOURCE_NOT_FOUND", "资源不存在");
    }

    private static ApiDomainException unauthorized() {
        return new ApiDomainException(HttpStatus.UNAUTHORIZED.value(), "TRIGGER_TOKEN_INVALID", "触发令牌无效");
    }

    private static ApiDomainException conflict(String code, String message) {
        return new ApiDomainException(HttpStatus.CONFLICT.value(), code, message);
    }

    public record IssuedToken(TriggerTokenRecord token, String plaintext) {
    }
}

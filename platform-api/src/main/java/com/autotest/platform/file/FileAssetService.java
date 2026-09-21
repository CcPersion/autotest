package com.autotest.platform.file;

import com.autotest.platform.project.ProjectRecord;
import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.run.RunRecord;
import com.autotest.platform.run.RunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.autotest.platform.security.ApiDomainException;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class FileAssetService {
    private final FileAssetRepository assets;
    private final ProjectRepository projects;
    private final ObjectStorage storage;
    private final FileAssetOrphanRepository orphans;

    public FileAssetService(FileAssetRepository assets, ProjectRepository projects, ObjectStorage storage) {
        this(assets, projects, storage, null);
    }

    @Autowired
    public FileAssetService(FileAssetRepository assets, ProjectRepository projects, ObjectStorage storage,
                            FileAssetOrphanRepository orphans) {
        this.assets = assets;
        this.projects = projects;
        this.storage = storage;
        this.orphans = orphans;
    }

    public List<FileAssetView> list(UUID projectId, String status) {
        requireProject(projectId);
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (normalizedStatus != null && !List.of("ACTIVE", "ARCHIVED").contains(normalizedStatus)) {
            throw error(HttpStatus.BAD_REQUEST, "STATUS_INVALID", "文件状态不正确");
        }
        return assets.findAll(projectId, normalizedStatus).stream().map(FileAssetView::from).toList();
    }

    @Transactional
    public FileAssetView upload(UUID projectId, MultipartFile file, String kind, UUID actorId) {
        ProjectRecord project = projects.findByIdForUpdate(projectId);
        if (project == null) {
            throw error(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在");
        }
        if (project.archived()) {
            throw error(HttpStatus.CONFLICT, "PROJECT_ARCHIVED", "项目已归档");
        }
        if (file == null) {
            throw error(HttpStatus.BAD_REQUEST, "FILE_EMPTY", "文件不能为空");
        }
        FileAssetPolicy.ValidatedUpload validated;
        try {
            validated = FileAssetPolicy.validateUpload(file.getOriginalFilename(), kind,
                    file.getContentType(), file.getSize());
        } catch (FileAssetPolicy.UploadRejectedException exception) {
            throw error(HttpStatus.BAD_REQUEST, exception.getMessage(), "文件不符合上传策略");
        }
        long reserved = assets.reservedBytes(projectId);
        if (reserved + validated.size() > FileAssetPolicy.PROJECT_QUOTA_BYTES) {
            throw error(HttpStatus.BAD_REQUEST, "QUOTA_EXCEEDED", "项目文件配额已用尽");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw error(HttpStatus.BAD_REQUEST, "FILE_READ_FAILED", "文件读取失败");
        }
        if (bytes.length != validated.size()) {
            throw error(HttpStatus.BAD_REQUEST, "FILE_LIMIT_EXCEEDED", "文件大小不一致");
        }
        String sha256 = sha256(bytes);
        String objectKey = UUID.randomUUID().toString().replace("-", "");
        FileAssetRecord asset = assets.insertUploading(projectId, validated.kind(), validated.originalName(),
                validated.mimeType(), validated.size(), sha256, objectKey, actorId);
        assets.insertReservation(projectId, asset.id(), validated.size());
        try {
            storage.put(objectKey, new ByteArrayInputStream(bytes), bytes.length, validated.mimeType());
            if (assets.activate(projectId, asset.id()) != 1) {
                throw new IOException("文件状态转换失败");
            }
            assets.deleteReservation(asset.id());
            return FileAssetView.from(require(projectId, asset.id()));
        } catch (IOException | RuntimeException exception) {
            try {
                storage.delete(objectKey);
            } catch (IOException ignored) {
                // orphan cleanup is deliberately best effort; the metadata is never API-visible as ACTIVE.
            }
            if (orphans != null) {
                orphans.record(projectId, objectKey);
            }
            throw error(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_UPLOAD_FAILED", "文件上传失败");
        }
    }

    @Transactional
    public FileAssetView archive(UUID projectId, UUID fileId, Integer revision, UUID actorId) {
        ProjectRecord project = projects.findByIdForUpdate(projectId);
        if (project == null) {
            throw error(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在");
        }
        if (project.archived()) {
            throw error(HttpStatus.CONFLICT, "PROJECT_ARCHIVED", "项目已归档");
        }
        FileAssetRecord current = require(projectId, fileId);
        if (!"ACTIVE".equals(current.status())) {
            throw error(HttpStatus.CONFLICT, "FILE_IN_USE", "文件不可归档");
        }
        if (assets.isInUse(projectId, fileId)) {
            throw error(HttpStatus.CONFLICT, "FILE_IN_USE", "文件已被运行快照引用");
        }
        if (revision == null || revision != current.revision()) {
            throw error(HttpStatus.CONFLICT, "REVISION_CONFLICT", "文件版本已变化");
        }
        if (assets.archive(projectId, fileId, revision) != 1) {
            throw error(HttpStatus.CONFLICT, "REVISION_CONFLICT", "文件版本已变化");
        }
        return FileAssetView.from(require(projectId, fileId));
    }

    public FileAssetRecord require(UUID projectId, UUID fileId) {
        FileAssetRecord asset = assets.find(projectId, fileId);
        if (asset == null) {
            throw error(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "文件不存在");
        }
        return asset;
    }

    public InputStream openRunFile(UUID runId, UUID fileId, RunRepository runs) throws IOException {
        RunRecord run = runs.findById(runId);
        if (run == null) throw error(HttpStatus.NOT_FOUND, "FILE_SNAPSHOT_NOT_FOUND", "运行文件快照不存在");
        JsonNode snapshot = findSnapshot(run.executionPlan(), fileId);
        if (snapshot == null) throw error(HttpStatus.NOT_FOUND, "FILE_SNAPSHOT_NOT_FOUND", "运行文件快照不存在");
        UUID projectId = projectId(run.executionPlan());
        if (projectId == null) throw error(HttpStatus.CONFLICT, "SNAPSHOT_METADATA_MISMATCH", "运行项目快照无效");
        FileAssetRecord asset = require(projectId, fileId);
        if (snapshot.path("size").asLong(-1) != asset.size()
                || !snapshot.path("sha256").asText("").equalsIgnoreCase(asset.sha256())
                || !snapshot.path("mimeType").asText("").equalsIgnoreCase(asset.mimeType())) {
            throw error(HttpStatus.CONFLICT, "SNAPSHOT_METADATA_MISMATCH", "运行文件快照元数据不匹配");
        }
        return storage.get(asset.objectKey());
    }

    private static JsonNode findSnapshot(JsonNode node, UUID fileId) {
        if (node == null) return null;
        if (node.isObject()) {
            // A certificate reference keeps the original fileId and nests the
            // immutable metadata under fileSnapshot. Prefer that nested
            // snapshot; treating the reference itself as the snapshot would
            // make size/sha256/mimeType look absent and reject a valid run.
            JsonNode nested = node.get("fileSnapshot");
            if (nested != null && nested.isObject()
                    && fileId.toString().equals(nested.path("fileId").asText())) return nested;
            if (fileId.toString().equals(node.path("fileId").asText())
                    && node.has("size") && node.has("sha256") && node.has("mimeType")) return node;
            var fields = node.fields();
            while (fields.hasNext()) {
                JsonNode found = findSnapshot(fields.next().getValue(), fileId);
                if (found != null) return found;
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                JsonNode found = findSnapshot(item, fileId);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static UUID projectId(JsonNode plan) {
        try {
            return UUID.fromString(plan.path("projectId").asText());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void requireProject(UUID projectId) {
        if (projects.findById(projectId) == null) {
            throw error(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ApiDomainException error(HttpStatus status, String code, String message) {
        return new ApiDomainException(status.value(), code, message);
    }
}

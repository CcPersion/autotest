package com.autotest.platform.file;

import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.run.RunRecord;
import com.autotest.platform.run.RunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileAssetServiceTest {
    @Test
    void opensNestedCertificateSnapshotWhenReferenceAlsoCarriesFileId() throws Exception {
        ObjectMapper json = new ObjectMapper();
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        byte[] bytes = "PKCS12".getBytes(StandardCharsets.UTF_8);
        FileAssetRecord asset = new FileAssetRecord(fileId, projectId, "PKCS12", "client.p12",
                "application/x-pkcs12", bytes.length,
                "a1b2c3", "ACTIVE", "private/client-p12", 0, Instant.now(), Instant.now());
        var plan = json.readTree("""
                {
                  "projectId":"%s",
                  "options":{"clientCertificate":{
                    "type":"PKCS12", "fileId":"%s",
                    "fileSnapshot":{"fileId":"%s","size":6,"sha256":"a1b2c3","mimeType":"application/x-pkcs12"}
                  }}
                }
                """.formatted(projectId, fileId, fileId));
        RunRecord run = new RunRecord(runId, projectId, UUID.randomUUID(), "API_DEFINITION", UUID.randomUUID(),
                UUID.randomUUID(), "PENDING", plan, "key", "5.6.3", null, null, null,
                null, null, null, false, Instant.now());
        FileAssetRepository assets = mock(FileAssetRepository.class);
        ProjectRepository projects = mock(ProjectRepository.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        RunRepository runs = mock(RunRepository.class);
        when(runs.findById(runId)).thenReturn(run);
        when(assets.find(projectId, fileId)).thenReturn(asset);
        when(storage.get("private/client-p12")).thenReturn(InputStream.nullInputStream());

        InputStream opened = new FileAssetService(assets, projects, storage).openRunFile(runId, fileId, runs);

        assertEquals(-1, opened.read());
    }
}

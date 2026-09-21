package com.autotest.platform.file;

import com.autotest.platform.run.RunRepository;
import com.autotest.platform.security.ApiDomainException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

@RestController
public class RunnerFileController {
    private final FileAssetService assets;
    private final RunRepository runs;
    private final String callbackToken;

    public RunnerFileController(FileAssetService assets, RunRepository runs,
                                @Value("${autotest.runner.callback-token:}") String callbackToken) {
        this.assets = assets;
        this.runs = runs;
        this.callbackToken = callbackToken == null ? "" : callbackToken;
    }

    @GetMapping("/api/v1/internal/runs/{runId}/files/{fileId}")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID runId, @PathVariable UUID fileId,
                                                        @RequestHeader(value = "X-Runner-Token", required = false)
                                                        String suppliedToken) throws Exception {
        verify(suppliedToken);
        InputStream stream = assets.openRunFile(runId, fileId, runs);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new InputStreamResource(stream));
    }

    private void verify(String supplied) {
        if (callbackToken.isBlank() || supplied == null
                || !MessageDigest.isEqual(callbackToken.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiDomainException(HttpStatus.UNAUTHORIZED.value(), "RUNNER_AUTHENTICATION_REQUIRED", "Runner 认证失败");
        }
    }
}

package com.autotest.platform.ai;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiModelConfigServiceTest {
    @Test
    void infersFakeProviderAndStoresOnlyReference() {
        AiModelConfigRepository repository = mock(AiModelConfigRepository.class);
        UUID id = UUID.randomUUID();
        AiModelConfigRecord saved = new AiModelConfigRecord(id, "本地替身", "fake://model", "fixture",
                "model-key", true, 0, Instant.now(), Instant.now(), "FAKE");
        when(repository.existsName("本地替身")).thenReturn(false);
        when(repository.insert("本地替身", "FAKE", "fake://model", "fixture", "model-key", true, id))
                .thenReturn(saved);
        AiModelConfigRecord result = new AiModelConfigService(repository).create(
                new AiModelConfigWrite("本地替身", "fake://model", "fixture", "model-key", true, null), id);
        assertEquals("FAKE", result.providerType());
        assertEquals("model-key", result.apiKeySecretRef());
    }

    @Test
    void rejectsNonHttpAndNonFakeModelEndpoint() {
        AiModelConfigRepository repository = mock(AiModelConfigRepository.class);
        AiModelConfigService service = new AiModelConfigService(repository);
        assertThrows(RuntimeException.class, () -> service.create(new AiModelConfigWrite(
                "模型", "file:///tmp/model", "fixture", null, true, null), UUID.randomUUID()));
    }
}

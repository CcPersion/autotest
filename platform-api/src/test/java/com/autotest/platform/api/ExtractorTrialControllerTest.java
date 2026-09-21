package com.autotest.platform.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ExtractorTrialController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ExtractorTrialService.class)
class ExtractorTrialControllerTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void exposesVersionedProjectScopedTrialEndpoint() throws Exception {
        mvc.perform(post("/api/v1/projects/00000000-0000-0000-0000-000000000001/extractor-trials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"response":{"statusCode":200,"durationMs":12,"body":"{\\"token\\":\\"abc\\"}",
                                 "headers":{},"cookies":{}},"extractors":[
                                   {"type":"JSON_PATH","expression":"$.token","variable":"token","failIfMissing":true}
                                 ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].ruleIndex").value(0))
                .andExpect(jsonPath("$.results[0].matched").value(true))
                .andExpect(jsonPath("$.results[0].value").value("abc"));
    }

    @Test
    void rejectsInvalidRuleShapeWithBadRequest() throws Exception {
        mvc.perform(post("/api/v1/projects/00000000-0000-0000-0000-000000000001/extractor-trials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"response":{"statusCode":200,"durationMs":0,"body":"{}","headers":{},"cookies":{}},
                                 "extractors":[{"type":"SCRIPT","expression":"$.x","variable":"x"}]}
                                """))
                .andExpect(status().isBadRequest());
    }
}

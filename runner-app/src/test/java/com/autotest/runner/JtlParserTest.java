package com.autotest.runner;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JtlParserTest {

    @Test
    void parsesSuccessAssertionFailureAndConnectionFailureSamples() throws Exception {
        Path jtl = Files.createTempFile("f1-09-", ".jtl");
        Files.writeString(jtl, """
                timeStamp,elapsed,label,responseCode,responseMessage,success,failureMessage,URL
                1,32,"HTTP Request [step-ok]",200,OK,true,,"http://test.local/health"
                2,40,"HTTP Request [step-assert]",200,OK,false,"JSONPath $.ok expected true","http://test.local/health"
                3,15,"HTTP Request [step-connect]",0,"Non HTTP response code: java.net.ConnectException",false,"Connection refused","http://test.local/health"
                """, StandardCharsets.UTF_8);

        List<JtlSample> samples = new JtlParser().parse(jtl);

        assertEquals(3, samples.size());
        assertEquals("step-ok", samples.get(0).stepId());
        assertEquals(32, samples.get(0).elapsedMs());
        assertTrue(samples.get(0).success());
        assertFalse(samples.get(1).success());
        assertEquals("Connection refused", samples.get(2).failureMessage());
    }

    @Test
    void masksSensitiveQueryValueBeforeItBecomesReportData() throws Exception {
        Path jtl = Files.createTempFile("f1-09-secret-", ".jtl");
        Files.writeString(jtl, """
                timeStamp,elapsed,label,responseCode,responseMessage,success,failureMessage,URL
                1,5,"HTTP Request [step-secret]",200,OK,true,,"http://test.local/p?token=top-secret&tenant=demo"
                """, StandardCharsets.UTF_8);

        JtlSample sample = new JtlParser().parse(jtl).get(0);

        assertFalse(sample.url().contains("top-secret"));
        assertTrue(sample.url().contains("token=***"));
        assertTrue(sample.url().contains("tenant=demo"));
    }

    @Test
    void normalizesEncodedAndCaseVariantSensitiveQueryKeys() throws Exception {
        Path jtl = Files.createTempFile("f1-09-query-variants-", ".jtl");
        Files.writeString(jtl, """
                timeStamp,elapsed,label,responseCode,responseMessage,success,failureMessage,URL
                1,5,"HTTP Request [step-secret]",200,OK,true,,"http://test.local/p?ACCESS%5FTOKEN=top-secret&Refresh-Token=refresh-secret&X%2DAPI%2DKEY=api-secret&tenant=a%20b"
                """, StandardCharsets.UTF_8);

        JtlSample sample = new JtlParser().parse(jtl).get(0);

        assertFalse(sample.url().contains("top-secret"));
        assertFalse(sample.url().contains("refresh-secret"));
        assertFalse(sample.url().contains("api-secret"));
        assertTrue(sample.url().contains("ACCESS%5FTOKEN=***"));
        assertTrue(sample.url().contains("Refresh-Token=***"));
        assertTrue(sample.url().contains("X%2DAPI%2DKEY=***"));
        assertTrue(sample.url().contains("tenant=a%20b"));
    }

    @Test
    void capturesControlledResponseBodyFromXmlJtl() throws Exception {
        Path jtl = Files.createTempFile("f1-09-response-body-", ".jtl");
        Files.writeString(jtl, """
                <?xml version="1.0" encoding="UTF-8"?>
                <testResults version="1.2">
                  <httpSample t="14" s="true" lb="HTTP Request [step-body]" rc="200" rm="OK">
                    <java.net.URL>http://test.local/health</java.net.URL>
                    <responseData class="java.lang.String">{"status":"ok"}</responseData>
                  </httpSample>
                </testResults>
                """, StandardCharsets.UTF_8);

        JtlSample sample = new JtlParser().parse(jtl).get(0);

        assertEquals("{\"status\":\"ok\"}", sample.responseBody());
    }

    @Test
    void decodesRunnerExtractionMetadataFromResponseHeaders() throws Exception {
        String encoded = java.util.Base64.getEncoder().encodeToString(
                "[{\"variable\":\"profile\",\"matched\":true,\"value\":{\"roles\":[\"qa\"]}}]"
                        .getBytes(StandardCharsets.UTF_8));
        Path jtl = Files.createTempFile("f2-03-extractions-", ".jtl");
        Files.writeString(jtl, "timeStamp,elapsed,label,responseCode,responseMessage,success,failureMessage,URL,responseHeaders\n"
                + "1,5,\"HTTP Request [step-extract]\",200,OK,true,,\"http://test.local\",\"X-Autotest-Extractions: " + encoded + "\"\n",
                StandardCharsets.UTF_8);

        JtlSample sample = new JtlParser().parse(jtl).get(0);

        assertTrue(sample.extractionsJson().contains("\"roles\":[\"qa\"]"));
    }

    @Test
    void parsesXmlJtlAndKeepsOnlyControlledExtractionHeader() throws Exception {
        String encoded = java.util.Base64.getEncoder().encodeToString(
                "[{\"variable\":\"authToken\",\"matched\":true,\"value\":\"token\"}]"
                        .getBytes(StandardCharsets.UTF_8));
        Path jtl = Files.createTempFile("f2-10-xml-", ".jtl");
        Files.writeString(jtl, """
                <?xml version="1.0" encoding="UTF-8"?>
                <testResults version="1.2">
                  <httpSample t="14" s="true" lb="HTTP Request [step-xml]" rc="200" rm="OK">
                    <responseHeader>HTTP/1.0 200 OK
                X-Autotest-Extractions: %s
                X-Internal-Sentinel: should-not-become-report-data
                    </responseHeader>
                    <java.net.URL>http://test.local/login?token=top-secret</java.net.URL>
                  </httpSample>
                </testResults>
                """.formatted(encoded), StandardCharsets.UTF_8);

        JtlSample sample = new JtlParser().parse(jtl).get(0);

        assertEquals("step-xml", sample.stepId());
        assertTrue(sample.success());
        assertTrue(sample.extractionsJson().contains("authToken"));
        assertFalse(sample.url().contains("top-secret"));
    }

    @Test
    void parsesTypedAssertionResultsFromXmlJtlInStableOrder() throws Exception {
        Path jtl = Files.createTempFile("f1-09-assertions-", ".jtl");
        Files.writeString(jtl, """
                <?xml version="1.0" encoding="UTF-8"?>
                <testResults version="1.2">
                  <httpSample t="14" s="false" lb="HTTP Request [step-assertions]" rc="200" rm="OK">
                    <assertionResult>
                      <name>Status Assertion [step-assertions]</name>
                      <failure>false</failure>
                      <error>false</error>
                      <failureMessage></failureMessage>
                    </assertionResult>
                    <assertionResult>
                      <name>JSONPath Assertion [step-assertions]</name>
                      <failure>true</failure>
                      <error>false</error>
                      <failureMessage>$.ok expected true</failureMessage>
                    </assertionResult>
                  </httpSample>
                </testResults>
                """, StandardCharsets.UTF_8);

        JtlSample sample = new JtlParser().parse(jtl).get(0);

        assertEquals(2, sample.assertionResults().size());
        assertTrue(sample.assertionResults().get(0).passed());
        assertFalse(sample.assertionResults().get(1).passed());
        assertEquals("$.ok expected true", sample.assertionResults().get(1).message());
    }
}

package com.autotest.platform.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SuiteMemberReportResolverTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void returnsSuiteMembersInPositionOrderWithNamesAndIds() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        List<SuiteMemberReportResolver.Member> members = SuiteMemberReportResolver.resolve(json.readTree("""
                {"targetType":"TEST_SUITE","suiteSteps":[
                  {"memberId":"%s","position":2,"targetType":"SCENARIO","targetId":"scenario-2","targetName":"订单场景","enabled":true},
                  {"memberId":"%s","position":1,"targetType":"API_CASE","targetId":"case-1","targetName":"登录用例","enabled":false}
                ]}
                """.formatted(first, second)));
        assertEquals(List.of("case-1", "scenario-2"), members.stream().map(SuiteMemberReportResolver.Member::targetId).toList());
        assertEquals("登录用例", members.get(0).targetName());
        assertEquals(false, members.get(0).enabled());
    }

    @Test
    void resolvesMemberIdFromScopedAndDirectResultKeys() throws Exception {
        UUID apiMember = UUID.randomUUID();
        UUID scenarioMember = UUID.randomUUID();
        List<SuiteMemberReportResolver.Member> members = SuiteMemberReportResolver.resolve(json.readTree("""
                {"targetType":"TEST_SUITE","suiteSteps":[
                  {"memberId":"%s","position":0,"targetType":"API_CASE","targetId":"case-1","targetName":"登录用例","enabled":true},
                  {"memberId":"%s","position":1,"targetType":"SCENARIO","targetId":"scenario-1","targetName":"业务场景","enabled":true}
                ]}
                """.formatted(apiMember, scenarioMember)));

        assertEquals(apiMember.toString(), SuiteMemberReportResolver.memberIdForResultKey(
                apiMember + "#0", members));
        assertEquals(scenarioMember.toString(), SuiteMemberReportResolver.memberIdForResultKey(
                scenarioMember + "/step-1#attempt-2", members));
        assertEquals(null, SuiteMemberReportResolver.memberIdForResultKey("unscoped-step#0", members));
    }
}

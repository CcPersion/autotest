# F2-02 Runtime Variable Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task.

**Goal:** 统一 Runner 内 HTTP、JDBC、Redis、ControlFlow 与断言期望值的变量解析，提供五层优先级、内置函数、严格预检和按运行/数据行隔离的可变上下文。

**Architecture:** 在 `runner-app` 引入一个不可泄漏 secret 的 `RunVariableContext`，以不可变 environment/scenario/case/data-row 基础层和可变 extracted/loop overlay 组成解析视图；`VariableResolver` 作为兼容门面委托它。ExecutionPlanAdapter、JDBC、Redis、ControlFlow 和场景/数据行执行统一从该上下文解析，并在外部触达前完成脱敏路径的变量预检。

**Tech Stack:** Java 17, Jackson `JsonNode`, JUnit 5, Maven, Apache JMeter 5.6.3.

**Spec:** `docs/requirements/01-产品需求规格说明书.md` §§6.4-6.6；F2-02 冻结要求（父代理任务交接）。不修改 `deployment/.env`，不扩展 F2-03/F2-04。

## Global Constraints

- Secret reference `${secret:name}` 只交给现有受限 materializer，不进入普通 scope、变量 JSON 或 JMX 明文。
- 优先级固定为 extracted > dataRow > caseVariables（兼容旧 `variables`）> scenario > environment。
- 完整 JSON 变量保持 JsonNode 类型；混合文本字符串化；缺失变量在外部触达前返回 `VARIABLE_UNDEFINED` 与脱敏字段路径。
- 每个直接 API_CASE 数据行建立独立 `(runId,rowId)` 上下文，行结束销毁 extracted；场景一个 run 共用 extracted，loop overlay 用 try/finally 恢复。

## Tasks

1. **Add red tests for the context contract.**
   - Files: `runner-app/src/test/java/com/autotest/runner/RunVariableContextTest.java`, `runner-app/src/test/java/com/autotest/runner/VariableResolverTest.java`.
   - Cover five-layer typed lookup, old `variables` alias, complete/mixed JSON interpolation, built-in functions and validation, secret-name rejection, missing-variable error/path, overlay restoration, three row isolation, parallel run isolation, and scope serialization round-trip.
   - Verify command: `mvn -pl runner-app -am -Dtest=RunVariableContextTest,VariableResolverTest test` must fail before implementation.

2. **Implement the context and resolver semantics.**
   - Files: `runner-app/src/main/java/com/autotest/runner/RunVariableContext.java`, `runner-app/src/main/java/com/autotest/runner/VariableResolver.java`.
   - Add immutable base scopes, isolated mutable extracted values, loop overlay stack, typed lookup, preflight result/error with redacted paths, strict variable-name/secret rules, UTC functions and overflow-safe random integer generation.
   - Keep the legacy resolver API as a thin compatibility facade and prevent secret values from ordinary maps.

3. **Route all step resolvers through the context.**
   - Files: `ExecutionPlanAdapter.java`, `ControlFlowEvaluator.java`, `JdbcSqlExecutor.java`, `RedisCommandExecutor.java`, `JmeterPlanCompiler.java` as required by current call paths; related tests.
   - Accept `RunVariableContext` at boundaries, preserve old map overloads only as adapters, and preflight URL/header/query/body/cookie/SQL/Redis/control/assertion fields before external work.
   - Ensure undefined values fail with `VARIABLE_UNDEFINED` and no HTTP/JDBC/Redis touch.

4. **Isolate direct rows and share scenario run scope.**
   - Files: `RunnerWorker.java`, `ExecutionPlanAdapter.java`, `ScenarioExecutionStep.java`, relevant Runner tests.
   - Build one context per direct `(runId,rowId)`, clear row extracted state after each row, keep one context for a scenario run, apply and restore loop overlays, and fail explicit multi-row API_CASE scenario references with `DATA_ROWS_IN_SCENARIO_UNSUPPORTED`.
   - Add tests for three rows, parallel contexts, scenario cross-step/cross-run sharing, recursive preflight, and loop restoration.

5. **Add platform plan validation/preflight contract tests.**
   - Files: `platform-api/src/main/java/com/autotest/platform/run/PlanBuilder.java` or current validator entry point, corresponding tests.
   - Validate dynamic fields and scenario references before saving/running while preserving secret references and the existing public API contract.

6. **Run fresh verification.**
   - Targeted Runner/Platform Maven tests, then `mvn -pl shared-contracts,runner-app,platform-api -am test` and the required WSL gate. Record exact commands, exits, counts, failures and unverified real-network boundaries for handoff; Sol final code review remains pending.

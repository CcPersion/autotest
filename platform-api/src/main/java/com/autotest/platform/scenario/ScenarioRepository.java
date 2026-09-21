package com.autotest.platform.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class ScenarioRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ScenarioRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<ScenarioRecord> findAll(UUID projectId, boolean includeArchived) {
        String archivedClause = includeArchived ? "" : " AND archived_at IS NULL";
        return jdbc.query(select() + " WHERE project_id = ?" + archivedClause + " ORDER BY updated_at DESC, id",
                (rs, rowNum) -> map(rs), projectId);
    }

    public ScenarioRecord findById(UUID projectId, UUID scenarioId) {
        List<ScenarioRecord> rows = jdbc.query(select() + " WHERE project_id = ? AND id = ?",
                (rs, rowNum) -> map(rs), projectId, scenarioId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ScenarioRecord findActiveByIdForUpdate(UUID projectId, UUID scenarioId) {
        List<ScenarioRecord> rows = jdbc.query(select()
                        + " WHERE project_id = ? AND id = ? AND archived_at IS NULL FOR UPDATE",
                (rs, rowNum) -> map(rs), projectId, scenarioId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean existsActiveName(UUID projectId, String name) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM scenarios "
                + "WHERE project_id = ? AND archived_at IS NULL AND lower(name) = lower(?))",
                Boolean.class, projectId, name);
        return Boolean.TRUE.equals(exists);
    }

    public boolean existsActiveNameExcluding(UUID projectId, String name, UUID excludedId) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM scenarios "
                + "WHERE project_id = ? AND archived_at IS NULL AND lower(name) = lower(?) AND id <> ?)",
                Boolean.class, projectId, name, excludedId);
        return Boolean.TRUE.equals(exists);
    }

    public ScenarioRecord insert(UUID projectId, String name, String description, JsonNode variables,
                                 JsonNode settings, UUID actorId) throws DuplicateKeyException {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO scenarios (id, project_id, name, description, variables_json, settings_json, "
                        + "revision, archived_at, created_by, updated_by, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, 0, NULL, ?, ?, ?, ?)",
                id, projectId, name, description, variables.toString(), settings.toString(), actorId, actorId,
                Timestamp.from(now), Timestamp.from(now));
        return findById(projectId, id);
    }

    public int updateDetails(UUID projectId, UUID scenarioId, String name, String description, JsonNode variables,
                             JsonNode settings, int expectedRevision, UUID actorId) {
        return jdbc.update("UPDATE scenarios SET name = ?, description = ?, variables_json = ?::jsonb, "
                        + "settings_json = ?::jsonb, revision = revision + 1, updated_by = ?, updated_at = ? "
                        + "WHERE project_id = ? AND id = ? AND archived_at IS NULL AND revision = ?",
                name, description, variables.toString(), settings.toString(), actorId, Timestamp.from(Instant.now()),
                projectId, scenarioId, expectedRevision);
    }

    public int archive(UUID projectId, UUID scenarioId, int expectedRevision, UUID actorId) {
        return jdbc.update("UPDATE scenarios SET archived_at = ?, revision = revision + 1, updated_by = ?, "
                        + "updated_at = ? WHERE project_id = ? AND id = ? AND archived_at IS NULL AND revision = ?",
                Timestamp.from(Instant.now()), actorId, Timestamp.from(Instant.now()), projectId, scenarioId, expectedRevision);
    }

    public void deleteSteps(UUID projectId, UUID scenarioId) {
        jdbc.update("DELETE FROM scenario_steps WHERE project_id = ? AND scenario_id = ?", projectId, scenarioId);
    }

    public void insertStep(UUID projectId, UUID scenarioId, ScenarioStepWrite step) {
        jdbc.update("INSERT INTO scenario_steps (id, scenario_id, project_id, parent_id, position, kind, title, "
                        + "enabled, section, reference_mode, api_case_id, failure_strategy, step_config) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                step.id(), scenarioId, projectId, step.parentId(), step.position(), step.kind(), step.title(),
                !Boolean.FALSE.equals(step.enabled()), step.section(), step.referenceMode(), step.apiCaseId(),
                step.failureStrategy(), step.stepConfig().toString());
    }

    public List<ScenarioStepRecord> findSteps(UUID scenarioId) {
        return jdbc.query("SELECT id, scenario_id, parent_id, position, kind, title, enabled, section, "
                        + "reference_mode, api_case_id, failure_strategy, step_config FROM scenario_steps "
                        + "WHERE scenario_id = ? ORDER BY position, id",
                (rs, rowNum) -> mapStep(rs), scenarioId);
    }

    public ScenarioRecord withSteps(ScenarioRecord scenario) {
        return new ScenarioRecord(scenario.id(), scenario.projectId(), scenario.name(), scenario.description(),
                scenario.variables(), scenario.settings(), scenario.revision(), scenario.archived(),
                scenario.createdAt(), scenario.updatedAt(), findSteps(scenario.id()));
    }

    private String select() {
        return "SELECT id, project_id, name, description, variables_json, settings_json, revision, archived_at, "
                + "created_at, updated_at FROM scenarios";
    }

    private ScenarioRecord map(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            return new ScenarioRecord(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                    rs.getString("name"), rs.getString("description"), objectMapper.readTree(rs.getString("variables_json")),
                    objectMapper.readTree(rs.getString("settings_json")), rs.getInt("revision"),
                    rs.getTimestamp("archived_at") != null, rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant(), List.of());
        } catch (IOException exception) {
            throw new IllegalStateException("stored scenario JSON is invalid", exception);
        }
    }

    private ScenarioStepRecord mapStep(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            return new ScenarioStepRecord(rs.getObject("id", UUID.class), rs.getObject("scenario_id", UUID.class),
                    rs.getObject("parent_id", UUID.class), rs.getInt("position"), rs.getString("kind"),
                    rs.getString("title"), rs.getBoolean("enabled"), rs.getString("section"),
                    rs.getString("reference_mode"), rs.getObject("api_case_id", UUID.class),
                    rs.getString("failure_strategy"), objectMapper.readTree(rs.getString("step_config")));
        } catch (IOException exception) {
            throw new IllegalStateException("stored scenario step JSON is invalid", exception);
        }
    }
}

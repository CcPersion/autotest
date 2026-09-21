package com.autotest.platform.schedule;

import com.autotest.platform.environment.EnvironmentRecord;
import com.autotest.platform.environment.EnvironmentRepository;
import com.autotest.platform.project.ProjectRecord;
import com.autotest.platform.project.ProjectRepository;
import com.autotest.platform.run.RunService;
import com.autotest.platform.security.ApiDomainException;
import com.autotest.platform.suite.TestSuiteRecord;
import com.autotest.platform.suite.TestSuiteRunService;
import com.autotest.platform.suite.TestSuiteService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
public class ScheduleService {
    private static final String DEFAULT_ZONE = "Asia/Shanghai";
    private final ScheduleRepository schedules;
    private final ProjectRepository projects;
    private final TestSuiteService suites;
    private final EnvironmentRepository environments;
    private final TestSuiteRunService suiteRuns;

    public ScheduleService(ScheduleRepository schedules, ProjectRepository projects, TestSuiteService suites,
                           EnvironmentRepository environments, TestSuiteRunService suiteRuns) {
        this.schedules = schedules;
        this.projects = projects;
        this.suites = suites;
        this.environments = environments;
        this.suiteRuns = suiteRuns;
    }

    public List<ScheduleRecord> list(UUID projectId) {
        requireProject(projectId);
        return schedules.findAll(projectId);
    }

    public ScheduleRecord get(UUID projectId, UUID scheduleId) {
        requireProject(projectId);
        ScheduleRecord schedule = schedules.findById(projectId, scheduleId);
        if (schedule == null) throw notFound();
        return schedule;
    }

    @Transactional
    public ScheduleRecord create(UUID projectId, ScheduleWrite request, UUID actorId) {
        ProjectRecord project = requireWritableProject(projectId);
        Normalized normalized = normalize(projectId, request, null);
        if (schedules.existsName(projectId, normalized.name(), null)) throw conflict("NAME_CONFLICT", "调度名称已存在");
        try {
            return schedules.insert(projectId, normalized.name(), normalized.suiteId(), normalized.environmentId(),
                    normalized.cron(), normalized.zoneId(), normalized.enabled(), normalized.nextRunAt(), actorId);
        } catch (DuplicateKeyException exception) {
            throw conflict("NAME_CONFLICT", "调度名称已存在");
        }
    }

    @Transactional
    public ScheduleRecord update(UUID projectId, UUID scheduleId, ScheduleWrite request, UUID actorId) {
        requireWritableProject(projectId);
        ScheduleRecord current = schedules.findByIdForUpdate(projectId, scheduleId);
        if (current == null) throw notFound();
        if (request == null || request.revision() == null || request.revision() != current.revision()) {
            throw revisionConflict(current.revision(), request == null ? null : request.revision());
        }
        Normalized normalized = normalize(projectId, request, current);
        if (schedules.existsName(projectId, normalized.name(), scheduleId)) throw conflict("NAME_CONFLICT", "调度名称已存在");
        if (schedules.update(projectId, scheduleId, normalized.name(), normalized.suiteId(), normalized.environmentId(),
                normalized.cron(), normalized.zoneId(), normalized.enabled(), normalized.nextRunAt(), current.revision(), actorId) != 1) {
            throw revisionConflict(current.revision(), request.revision());
        }
        return schedules.findById(projectId, scheduleId);
    }

    /** 单实例轮询入口；通过 next_run_at 条件更新避免同一次到期重复创建运行。 */
    @Transactional
    public int runDue(Instant now) {
        int created = 0;
        for (ScheduleRecord schedule : schedules.findDue(now)) {
            Instant due = schedule.nextRunAt();
            Instant next = CronSchedule.parse(schedule.cronExpression()).next(due, ZoneId.of(schedule.zoneId()));
            RunService.CreateResult result = suiteRuns.createResult(schedule.projectId(), schedule.suiteId(),
                    schedule.environmentId(), "schedule:" + schedule.id() + ":" + due.toEpochMilli(), schedule.createdBy());
            if (schedules.advance(schedule.projectId(), schedule.id(), due, next, due) == 1 && result.created()) created++;
        }
        return created;
    }

    private Normalized normalize(UUID projectId, ScheduleWrite request, ScheduleRecord current) {
        if (request == null) throw validation("调度请求不能为空");
        String name = required(request.name(), "调度名称不能为空", 256);
        UUID suiteId = request.suiteId() == null && current != null ? current.suiteId() : request.suiteId();
        UUID environmentId = request.environmentId() == null && current != null ? current.environmentId() : request.environmentId();
        if (suiteId == null || environmentId == null) throw validation("调度必须选择测试集合和环境");
        TestSuiteRecord suite = suites.get(projectId, suiteId);
        if (suite.archived()) throw conflict("TEST_SUITE_ARCHIVED", "测试集合已归档");
        EnvironmentRecord environment = environments.findById(projectId, environmentId);
        if (environment == null) throw notFound();
        if (environment.archived()) throw conflict("ENVIRONMENT_ARCHIVED", "环境已归档");
        String cronText = request.cronExpression() == null && current != null ? current.cronExpression() : request.cronExpression();
        CronSchedule cron = CronSchedule.parse(cronText);
        String zone = request.zoneId() == null && current != null ? current.zoneId()
                : (request.zoneId() == null || request.zoneId().isBlank() ? DEFAULT_ZONE : request.zoneId().strip());
        try {
            ZoneId.of(zone);
        } catch (RuntimeException exception) {
            throw validation("时区不合法");
        }
        boolean enabled = request.enabled() == null ? current == null || current.enabled() : request.enabled();
        Instant next = enabled ? cron.next(Instant.now(), ZoneId.of(zone)) : null;
        return new Normalized(name, suiteId, environmentId, cron.expression(), zone, enabled, next);
    }

    private ProjectRecord requireWritableProject(UUID projectId) {
        ProjectRecord project = projects.findByIdForUpdate(projectId);
        if (project == null) throw notFound();
        if (project.archived()) throw conflict("PROJECT_ARCHIVED", "项目已归档");
        return project;
    }

    private void requireProject(UUID projectId) {
        if (projects.findById(projectId) == null) throw notFound();
    }

    private static String required(String value, String message, int max) {
        String result = value == null ? "" : value.strip();
        if (result.isEmpty() || result.length() > max) throw validation(message);
        return result;
    }

    private static ApiDomainException validation(String message) {
        return new ApiDomainException(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", message);
    }

    private static ApiDomainException notFound() {
        return new ApiDomainException(HttpStatus.NOT_FOUND.value(), "RESOURCE_NOT_FOUND", "资源不存在");
    }

    private static ApiDomainException conflict(String code, String message) {
        return new ApiDomainException(HttpStatus.CONFLICT.value(), code, message);
    }

    private static ApiDomainException revisionConflict(int current, Integer requested) {
        return new ApiDomainException(HttpStatus.CONFLICT.value(), "REVISION_CONFLICT", "调度版本已变化",
                java.util.Map.of("currentRevision", current, "requestedRevision", requested == null ? -1 : requested));
    }

    private record Normalized(String name, UUID suiteId, UUID environmentId, String cron, String zoneId,
                              boolean enabled, Instant nextRunAt) {
    }
}

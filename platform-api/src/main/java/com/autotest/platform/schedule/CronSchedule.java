package com.autotest.platform.schedule;

import org.springframework.scheduling.support.CronExpression;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;

/** 只接受标准 5/6 段 Cron，不接受脚本、宏或额外参数。 */
public final class CronSchedule {
    private final String expression;
    private final CronExpression parsed;

    private CronSchedule(String expression, CronExpression parsed) {
        this.expression = expression;
        this.parsed = parsed;
    }

    public static CronSchedule parse(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > 128
                || normalized.contains(";") || normalized.contains("|")
                || normalized.contains("&&") || normalized.contains("$()")
                || normalized.contains("`")) {
            throw new IllegalArgumentException("Cron 表达式不合法");
        }
        long fields = Arrays.stream(normalized.split("\\s+")).count();
        if (fields == 5) normalized = "0 " + normalized;
        if (fields != 5 && fields != 6) {
            throw new IllegalArgumentException("Cron 必须为 5 段或 6 段");
        }
        try {
            return new CronSchedule(value.strip(), CronExpression.parse(normalized));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Cron 表达式不合法", exception);
        }
    }

    public Instant next(Instant from, ZoneId zone) {
        if (from == null || zone == null) throw new IllegalArgumentException("计算下次时间需要起点和时区");
        ZonedDateTime next = parsed.next(from.atZone(zone));
        if (next == null) throw new IllegalArgumentException("Cron 没有可计算的下次时间");
        return next.toInstant();
    }

    public String expression() {
        return expression;
    }
}

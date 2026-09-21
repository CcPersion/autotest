package com.autotest.platform.schedule;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** 单实例调度轮询；不承担分布式抢占或 Runner 租约。 */
@Component
public class SchedulePoller {
    private final ScheduleService schedules;

    public SchedulePoller(ScheduleService schedules) {
        this.schedules = schedules;
    }

    @Scheduled(fixedDelayString = "${autotest.schedule.poll-ms:10000}")
    public void poll() {
        schedules.runDue(Instant.now());
    }
}

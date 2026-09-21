package com.autotest.runner;

/** JMeter 子进程的最小结果；非零退出是运行失败，不应抛出到 Runner 主进程。 */
public record ProcessResult(int exitCode, boolean canceled) {

    public ProcessResult(int exitCode) {
        this(exitCode, false);
    }
}

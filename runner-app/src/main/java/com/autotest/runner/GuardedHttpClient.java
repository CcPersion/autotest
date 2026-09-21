package com.autotest.runner;

import org.apache.jmeter.protocol.http.sampler.HTTPHC4Impl;
import org.apache.jmeter.protocol.http.sampler.HTTPSampleResult;
import org.apache.jmeter.protocol.http.sampler.HTTPSamplerBase;

import java.net.URL;

/** 仅在受控 Sampler 内暴露 JMeter HC4 单跳调用和生命周期。 */
final class GuardedHttpClient extends HTTPHC4Impl {

    GuardedHttpClient(HTTPSamplerBase sampler) {
        super(sampler);
    }

    HTTPSampleResult send(URL url, String method, boolean followingRedirect, int depth) {
        return super.sample(url, method, followingRedirect, depth);
    }

    void threadFinishedSafely() {
        super.threadFinished();
    }

    boolean interruptSafely() {
        return super.interrupt();
    }

    void notifyFirstSampleAfterLoopRestartSafely() {
        super.notifyFirstSampleAfterLoopRestart();
    }
}

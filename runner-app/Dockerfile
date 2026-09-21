FROM eclipse-temurin:17-jre-jammy

ARG JMETER_VERSION=5.6.3
ARG JMETER_SHA512=5978a1a35edb5a7d428e270564ff49d2b1b257a65e17a759d259a9283fc17093e522fe46f474a043864aea6910683486340706d745fcdf3db1505fd71e689083
ARG JMETER_URL=https://mirrors.cloud.tencent.com/apache/jmeter/binaries/apache-jmeter-5.6.3.tgz
ARG JMETER_SIZE=87414762

ENV JMETER_HOME=/opt/jmeter
ENV PATH=${JMETER_HOME}/bin:${PATH}
ENV JAVA_TOOL_OPTIONS=-Djava.awt.headless=true

RUN mkdir --parents /opt/jmeter /opt/runner /work/runs \
    && max_attempts=20 \
    && attempt=1 \
    && archive=/tmp/apache-jmeter-${JMETER_VERSION}.tgz \
    && while [ "${attempt}" -le "${max_attempts}" ] && [ ! -f "${archive}.complete" ]; do \
        echo "JMeter 下载尝试 ${attempt}/${max_attempts}"; \
        if curl --fail --show-error --location --connect-timeout 15 --max-time 300 -C - "${JMETER_URL}" --output "${archive}"; then \
            downloaded="$(wc -c < "${archive}")"; \
            echo "JMeter 当前下载字节数: ${downloaded}/${JMETER_SIZE}"; \
            if [ "${downloaded}" -eq "${JMETER_SIZE}" ]; then touch "${archive}.complete"; fi; \
        else \
            downloaded="$(wc -c < "${archive}" 2>/dev/null || echo 0)"; \
            echo "JMeter 下载中断，已保留字节数: ${downloaded}/${JMETER_SIZE}"; \
        fi; \
        attempt=$((attempt + 1)); \
    done \
    && test -f "${archive}.complete" \
    && test "$(wc -c < "${archive}")" -eq "${JMETER_SIZE}" \
    && echo "${JMETER_SHA512}  ${archive}" | sha512sum --check --status \
    && tar --extract --gzip --file "${archive}" --strip-components=1 --directory /opt/jmeter \
    && rm --force "${archive}" "${archive}.complete"

RUN useradd --create-home --uid 10001 --shell /usr/sbin/nologin runner \
    && chown --recursive runner:runner /opt/jmeter /opt/runner /work/runs

COPY src/test/resources/smoke.jmx /opt/runner/smoke.jmx
COPY target/runner-app-0.1.0-SNAPSHOT.jar /opt/runner/runner.jar
COPY target/runner-app-0.1.0-SNAPSHOT-components.jar /opt/jmeter/lib/ext/autotest-runner-components.jar
COPY src/main/resources/jmeter/saveservice.properties /opt/jmeter/bin/saveservice.properties

RUN chown runner:runner /opt/runner/smoke.jmx /opt/runner/runner.jar \
    /opt/jmeter/lib/ext/autotest-runner-components.jar /opt/jmeter/bin/saveservice.properties

WORKDIR /work/runs
USER runner
CMD ["jmeter", "--version"]

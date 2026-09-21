FROM eclipse-temurin:17-jre-jammy

RUN useradd --create-home --uid 10001 --shell /usr/sbin/nologin platform \
    && mkdir --parents /opt/platform \
    && chown --recursive platform:platform /opt/platform

COPY target/platform-api-0.1.0-SNAPSHOT.jar /opt/platform/platform-api.jar
RUN chown platform:platform /opt/platform/platform-api.jar

USER platform
WORKDIR /opt/platform
ENV JAVA_TOOL_OPTIONS=-Djava.awt.headless=true
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/platform/platform-api.jar"]

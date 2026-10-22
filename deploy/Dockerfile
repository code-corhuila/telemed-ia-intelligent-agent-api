FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY pom.xml .
COPY intelligent-agent-core ./intelligent-agent-core
COPY intelligent-agent-adapters ./intelligent-agent-adapters
COPY intelligent-agent-app ./intelligent-agent-app

# Tests run in CI (mvn -B verify); the image only packages the jar.
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre

WORKDIR /app

# Runs as an unprivileged user, never as root.
RUN groupadd --system telemed \
    && useradd --system \
        --gid telemed \
        --home-dir /app \
        --shell /usr/sbin/nologin \
        telemed

COPY --from=build \
    --chown=telemed:telemed \
    /app/intelligent-agent-app/target/intelligent-agent-app.jar \
    app.jar

USER telemed

EXPOSE 8086 9086

ENTRYPOINT ["java", "-jar", "app.jar"]

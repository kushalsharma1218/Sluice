# ---- build ----------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies first so a source-only change reuses the cached layer.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

# ---- run ------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN groupadd --system sluice && useradd --system --gid sluice --home /app sluice
COPY --from=build /build/target/sluice-*.jar /app/sluice.jar
USER sluice

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD ["sh", "-c", "curl -fsS http://localhost:8080/actuator/health || exit 1"]

# Container-aware heap sizing; the rest of the tuning belongs in the compose file.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+ZGenerational"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/sluice.jar"]

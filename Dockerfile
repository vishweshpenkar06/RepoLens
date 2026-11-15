# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:25-jdk-jammy AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -ntp -q -DskipTests package \
    && java -Djarmode=tools -jar target/repolens-0.1.0.jar extract --destination extracted

FROM eclipse-temurin:25-jre-jammy

RUN groupadd --system --gid 1001 app \
    && useradd --system --uid 1001 --gid app --home /app app \
    && mkdir -p /app/.cache \
    && chown -R app:app /app

WORKDIR /app
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    EMBEDDING_CACHE_DIR=/app/.cache/repolens-onnx

COPY --from=build --chown=app:app /workspace/extracted/lib/ ./lib/
COPY --from=build --chown=app:app /workspace/extracted/repolens-0.1.0.jar ./

USER app
EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=6 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8081 && printf "GET /actuator/health/readiness HTTP/1.0\r\n\r\n" >&3 && grep -q "200" <&3'

ENTRYPOINT ["java", "-jar", "repolens-0.1.0.jar"]

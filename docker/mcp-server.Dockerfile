FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY . .
RUN ./mvnw -B -ntp -pl roadmind-mcp-server -am -DskipTests package

FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /workspace/roadmind-mcp-server/target/roadmind-mcp-server-0.1.0-SNAPSHOT.jar /app/app.jar

EXPOSE 8090
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=12 \
    CMD curl --fail --silent http://127.0.0.1:8090/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# ── Stage 1: Build ──
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /build

# Copy Maven wrapper & pom first (layer-cached dependency download)
COPY mvnw mvnw
COPY .mvn .mvn
COPY pom.xml pom.xml
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copy source and build the fat JAR (skip tests – they need a running DB)
COPY src src
RUN ./mvnw package -DskipTests -B

# ── Stage 2: Runtime ──
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Create a non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Ensure file logging path exists and is writable by the runtime user
RUN mkdir -p /app/logs && chown -R appuser:appgroup /app

COPY --from=builder /build/target/backend-0.0.1-SNAPSHOT.jar app.jar

# Switch to non-root
USER appuser

EXPOSE 8080

ENV SERVER_PORT=8080 \
    JAVA_OPTS="-Xms128m -Xmx384m -XX:+UseG1GC -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
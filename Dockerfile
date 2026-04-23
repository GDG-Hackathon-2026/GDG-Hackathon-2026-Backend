# ---- Build stage ----
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --version --no-daemon

COPY src src
RUN ./gradlew bootJar --no-daemon -x test

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd -r -u 1001 spring
USER spring

COPY --from=builder /workspace/build/libs/*.jar /app/app.jar

EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC"
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]

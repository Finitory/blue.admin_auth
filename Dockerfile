# syntax=docker/dockerfile:1

# Build stage
FROM eclipse-temurin:25-jdk-noble AS build

WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle/ gradle/

RUN chmod +x ./gradlew \
    && ./gradlew dependencies --no-daemon

COPY src/ src/

RUN ./gradlew bootJar --no-daemon

# Runtime stage
FROM eclipse-temurin:25-jre-noble

WORKDIR /app

RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid 10001 app

COPY --from=build --chown=app:app \
    /workspace/build/libs/*.jar /app/app.jar

USER app

EXPOSE 12200

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

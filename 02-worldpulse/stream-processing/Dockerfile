# Multi-stage build: Maven build + lightweight JRE run stage
# Stage 1: Build
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apt-get update && apt-get install -y maven && \
    mvn package -DskipTests -q

# Stage 2: Run
FROM eclipse-temurin:21-jre
VOLUME /tmp
COPY --from=build /app/target/worldpulse-stream-processing-0.0.1-SNAPSHOT.jar app.jar
ENV JAVA_OPTS=""
ENTRYPOINT exec java $JAVA_OPTS -jar /app.jar

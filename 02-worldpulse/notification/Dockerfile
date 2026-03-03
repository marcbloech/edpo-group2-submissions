# Copied from Lab 4: kafka/java/checkout/Dockerfile
FROM eclipse-temurin:21-jdk
VOLUME /tmp
ADD target/*.jar app.jar
ENV JAVA_OPTS=""
ENTRYPOINT exec java $JAVA_OPTS -jar /app.jar

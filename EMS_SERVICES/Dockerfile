# syntax=docker/dockerfile:1.7

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY src ./src

RUN --mount=type=cache,target=/root/.m2 mvn -q -DskipTests clean package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Sized for a 0.5 CPU / 512 MB instance (~100 concurrent exam users).
# Any of these can be overridden by the host's environment variables.
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=60 -XX:InitialRAMPercentage=25 -XX:+UseSerialGC -Xss512k -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom" \
    SERVER_TOMCAT_THREADS_MAX=50 \
    SERVER_TOMCAT_THREADS_MIN_SPARE=5 \
    DB_POOL_MAX_SIZE=10 \
    DB_POOL_MIN_IDLE=2

RUN addgroup -S ems && adduser -S ems -G ems

COPY --from=build /workspace/target/*.jar /app/app.jar

RUN chown -R ems:ems /app

USER ems

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]

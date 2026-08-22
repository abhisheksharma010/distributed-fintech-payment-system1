FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

COPY gradlew .
COPY gradle ./gradle
COPY build.gradle .
COPY settings.gradle .

RUN chmod +x gradlew

COPY src ./src

ENV JAVA_OPTS="-Xmx384m -XX:MaxMetaspaceSize=256m"
ENV GRADLE_OPTS="-Xmx384m -XX:MaxMetaspaceSize=256m -Dorg.gradle.daemon=false -Dorg.gradle.workers.max=1"

RUN ./gradlew clean bootJar \
    --no-daemon \
    -Dorg.gradle.jvmargs="-Xmx384m -XX:MaxMetaspaceSize=256m"

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN useradd --system --create-home --shell /usr/sbin/nologin spring

COPY --from=builder /app/build/libs/*.jar /app/app.jar

RUN chown spring:spring /app/app.jar

USER spring

EXPOSE 8084

ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
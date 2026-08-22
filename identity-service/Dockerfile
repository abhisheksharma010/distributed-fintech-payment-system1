FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle ./

RUN chmod +x gradlew

COPY src src

RUN ./gradlew clean bootJar \
    --no-daemon \
    -Dorg.gradle.jvmargs="-Xmx256m -XX:MaxMetaspaceSize=256m" \
    -Dorg.gradle.workers.max=1


FROM eclipse-temurin:21-jre

WORKDIR /app

RUN useradd --system --create-home spring

COPY --from=builder /app/build/libs/*.jar app.jar

USER spring

EXPOSE 8082

ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

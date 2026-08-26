FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY gradle ./gradle
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties* ./
COPY gradle/libs.versions.toml ./gradle/libs.versions.toml
COPY java ./java
RUN ./gradlew :java:extraction-gateway:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache wget \
    && addgroup -S synanton && adduser -S -G synanton synanton
USER synanton
USER synanton
WORKDIR /app
COPY --from=build /workspace/java/extraction-gateway/build/libs/extraction-gateway*.jar app.jar
EXPOSE 8092 9091
ENTRYPOINT ["java", "-jar", "app.jar"]

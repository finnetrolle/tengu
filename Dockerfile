# multi-stage: сборка → минимальный JRE-рантайм
FROM eclipse-temurin:21-jdk AS build
WORKDIR /work
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY protocol ./protocol
COPY toon ./toon
COPY toolkit ./toolkit
COPY plugins ./plugins
COPY server ./server
RUN ./gradlew :server:installDist --no-daemon -q

FROM eclipse-temurin:21-jre
WORKDIR /opt/tengu
COPY --from=build /work/server/build/install/server ./
EXPOSE 8080
ENTRYPOINT ["bin/server"]

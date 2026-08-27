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
COPY cli ./cli
RUN ./gradlew :server:installDist --no-daemon -q

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 1001 --no-create-home tengu
WORKDIR /opt/tengu
COPY --from=build --chown=tengu:tengu /work/server/build/install/server ./
USER tengu
EXPOSE 8080
ENTRYPOINT ["bin/server"]

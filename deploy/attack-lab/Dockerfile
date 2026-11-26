FROM eclipse-temurin:21-jre-alpine@sha256:5d7689f47d670aca766b9503a70bccc2d73b4eb9221ae8f714ae958313c4ce70
ARG SOURCE_COMMIT
LABEL logssey.test-fixture="true" org.opencontainers.image.revision=$SOURCE_COMMIT
LABEL logssey.baseline-commit="ac58beab28905537bbe1ee946d8d158adfaf4b91"
RUN addgroup -S -g 1000 app && adduser -S -u 1000 -G app app
WORKDIR /app
COPY build/libs/*SNAPSHOT.jar app.jar
USER 1000
ENTRYPOINT ["java", "-Xmx384m", "-jar", "/app/app.jar"]

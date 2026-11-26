FROM eclipse-temurin:21-jre-alpine
ARG SOURCE_COMMIT
LABEL logssey.test-fixture="true" org.opencontainers.image.revision=$SOURCE_COMMIT
LABEL logssey.baseline-commit="ac58beab28905537bbe1ee946d8d158adfaf4b91"
RUN addgroup -S -g 1000 app && adduser -S -u 1000 -G app app
WORKDIR /app
COPY build/libs/*SNAPSHOT.jar app.jar
USER 1000
ENTRYPOINT ["java", "-Xmx384m", "-jar", "/app/app.jar"]

# CI 에서 ./gradlew build 로 만든 JAR 을 받는다.
#
# Dockerfile 안에서 Gradle 빌드를 하지 않는 이유는
# CI 가 Gradle 캐시를 쓰고 테스트도 같은 단계에서 돌리기 때문이다.
# 여기서 다시 빌드하면 의존성을 매번 새로 받게 된다.
#
# JAR_FILE 을 인자로 두어 멀티모듈로 전환해도 같은 파일을 쓸 수 있다.
#   docker build --build-arg JAR_FILE=api/build/libs/*.jar .
ARG JAR_FILE=build/libs/*.jar

FROM eclipse-temurin:21-jre-alpine

# 루트로 실행하지 않는다.
RUN addgroup -S app && adduser -S app -G app

WORKDIR /app

ARG JAR_FILE
COPY ${JAR_FILE} app.jar

USER app

EXPOSE 8080

# exec 형식을 쓴다.
# shell 형식이면 JVM 이 PID 1 이 아니라 SIGTERM 을 받지 못해
# graceful shutdown 이 동작하지 않는다.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
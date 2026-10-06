# syntax=docker/dockerfile:1
#
# Build context must be backend/ (this module depends on :core and
# :manager:infrastructure, and settings.gradle.kts includes every module,
# so the whole multi-project build needs to be present to configure).

FROM eclipse-temurin:24-jdk-alpine AS deps
WORKDIR /app

COPY gradlew settings.gradle.kts ./
COPY gradle gradle
COPY core/build.gradle.kts core/build.gradle.kts
COPY console-common/build.gradle.kts console-common/build.gradle.kts
COPY manager/infrastructure/build.gradle.kts manager/infrastructure/build.gradle.kts
COPY manager/console/build.gradle.kts manager/console/build.gradle.kts
COPY manager/api/build.gradle.kts manager/api/build.gradle.kts
COPY processor/infrastructure/build.gradle.kts processor/infrastructure/build.gradle.kts
COPY processor/console/build.gradle.kts processor/console/build.gradle.kts

RUN --mount=type=cache,id=gradle-home,target=/root/.gradle \
    ./gradlew :manager:api:dependencies --no-daemon > /dev/null

FROM deps AS builder
COPY . .

RUN --mount=type=cache,id=gradle-home,target=/root/.gradle \
    ./gradlew :manager:api:bootJar --no-daemon -x ktlintCheck \
    && cp manager/api/build/libs/$(ls manager/api/build/libs | grep -v -- -plain.jar) app.jar

FROM eclipse-temurin:24-jre-alpine AS runner
WORKDIR /app

RUN addgroup --system --gid 1001 spring \
    && adduser --system --uid 1001 spring

COPY --from=builder --chown=spring:spring /app/app.jar ./app.jar

USER spring
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

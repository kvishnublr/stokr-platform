FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace

# Cache Maven dependencies separately from source — invalidated only when pom.xml files change
COPY pom.xml .
COPY stokr-common/pom.xml stokr-common/
COPY stokr-auth/pom.xml stokr-auth/
COPY stokr-user/pom.xml stokr-user/
COPY stokr-marketdata/pom.xml stokr-marketdata/
COPY stokr-broker/pom.xml stokr-broker/
COPY stokr-v5/pom.xml stokr-v5/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -pl stokr-v5 -am dependency:go-offline -q

COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -pl stokr-v5 -am package -DskipTests -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN apk add --no-cache curl && \
    addgroup -S stokr && adduser -S stokr -G stokr
COPY --from=build /workspace/stokr-v5/target/stokr-v5-*.jar /app/stokr-v5.jar

USER stokr
EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=70.0", "-Xms256m", "-Duser.timezone=Asia/Kolkata", "-jar", "/app/stokr-v5.jar"]

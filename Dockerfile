FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace

# Cache Maven dependencies separately from source — invalidated only when pom.xml files change
COPY pom.xml .
COPY stokr-common/pom.xml stokr-common/
COPY stokr-auth/pom.xml stokr-auth/
COPY stokr-user/pom.xml stokr-user/
COPY stokr-risk/pom.xml stokr-risk/
COPY stokr-oms/pom.xml stokr-oms/
COPY stokr-marketdata/pom.xml stokr-marketdata/
COPY stokr-strategy/pom.xml stokr-strategy/
COPY stokr-backtest/pom.xml stokr-backtest/
COPY stokr-execution/pom.xml stokr-execution/
COPY stokr-admin/pom.xml stokr-admin/
COPY stokr-broker/pom.xml stokr-broker/
COPY stokr-websocket/pom.xml stokr-websocket/
COPY stokr-bootstrap/pom.xml stokr-bootstrap/
RUN --mount=type=cache,target=/root/.m2 \
    mvn -pl stokr-bootstrap -am dependency:go-offline -q

COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -pl stokr-bootstrap -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S stokr && adduser -S stokr -G stokr
COPY --from=build /workspace/stokr-bootstrap/target/stokr-bootstrap-*.jar /app/stokr-bootstrap.jar

USER stokr
EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=70.0", "-Xms256m", "-Duser.timezone=Asia/Kolkata", "-jar", "/app/stokr-bootstrap.jar"]

FROM maven:3.9.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace

COPY . .
RUN mvn -pl stokr-bootstrap -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S stokr && adduser -S stokr -G stokr
COPY --from=build /workspace/stokr-bootstrap/target/stokr-bootstrap-*.jar /app/stokr-bootstrap.jar

USER stokr
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/stokr-bootstrap.jar"]

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/stokr-lite-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Dspring.flyway.enabled=false", "-Dspring.jpa.hibernate.ddl-auto=update", "-jar", "app.jar"]

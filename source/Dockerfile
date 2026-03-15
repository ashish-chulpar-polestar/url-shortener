# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml .
COPY src/ src/
RUN mvn -B package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-jammy
COPY --from=builder /build/target/mti-scores-api-1.0.0-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

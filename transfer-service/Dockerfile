# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build

WORKDIR /app

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

COPY legacy-core-sdk legacy-core-sdk
COPY mock-bank mock-bank
COPY transfer-service transfer-service

RUN chmod +x mvnw

RUN ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/transfer-service/target/transfer-service-1.0.0-SNAPSHOT.jar /app/transfer-service.jar

EXPOSE 8080

ENTRYPOINT ["java", "-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch", "-jar", "/app/transfer-service.jar"]
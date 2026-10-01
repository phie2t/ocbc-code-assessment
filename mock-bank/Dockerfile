
# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build

WORKDIR /build

COPY mvnw .
COPY .mvn .mvn
COPY mock-bank/pom.xml mock-bank/pom.xml

RUN chmod +x mvnw
RUN ./mvnw -B -q dependency:go-offline -f mock-bank/pom.xml

COPY mock-bank/src mock-bank/src

RUN ./mvnw -B -q -DskipTests package -f mock-bank/pom.xml

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /build/mock-bank/target/mock-bank.jar /app/mock-bank.jar

USER 10001:10001

EXPOSE 9090

HEALTHCHECK --interval=5s --timeout=3s --start-period=30s --retries=12 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/9090 && printf "GET /health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && grep -q UP <&3'

ENTRYPOINT ["java", "-jar", "/app/mock-bank.jar"]
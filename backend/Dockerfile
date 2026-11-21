# Build from the repo root: docker build -f backend/Dockerfile .
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY collector collector
COPY engine engine
COPY backend backend
COPY automation-tests/pom.xml automation-tests/pom.xml
RUN mvn -q -pl backend -am -DskipTests package

FROM eclipse-temurin:17-jre
RUN useradd -r -u 10001 monitor
WORKDIR /app
COPY --from=build /src/backend/target/backend-*.jar app.jar
USER monitor
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

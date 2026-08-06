# HCX-6: Build the Spring Boot jar in an isolated Maven stage.
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY .mvn/ .mvn/
COPY mvnw ./
RUN chmod +x mvnw
RUN ./mvnw -q -DskipTests dependency:go-offline

COPY src/ src/
RUN ./mvnw -q -DskipTests package \
    && JAR_PATH="$(ls -1 target/*.jar | grep -v '\\.original$' | head -n 1)" \
    && test -n "$JAR_PATH" \
    && cp "$JAR_PATH" /workspace/app.jar

# HCX-6: Use a lightweight Java runtime image and non-root user.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN groupadd --system spring && useradd --system --gid spring spring
COPY --from=build /workspace/app.jar /app/app.jar

EXPOSE 8080
USER spring

ENTRYPOINT ["java", "-jar", "/app/app.jar"]


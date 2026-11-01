# ---------- Stage 1: build the jar ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Download dependencies first (cached until pom.xml changes)
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# Fix Windows line endings and make the Maven wrapper executable
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -q dependency:go-offline

# Build the application (tests run separately with ./mvnw test)
COPY src ./src
RUN ./mvnw -B -q package -DskipTests

# ---------- Stage 2: small runtime image ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# Run as a normal user, not root
RUN useradd --system --create-home spring
USER spring

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

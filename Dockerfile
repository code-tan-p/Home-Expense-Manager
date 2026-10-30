# Build stage: compile and package the program.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN sed -i "s/$//" mvnw && chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package

# Run stage: a small Java 21 runtime, running as a normal (non-root) user.
FROM eclipse-temurin:21-jre
RUN useradd --system --create-home --uid 10001 app && mkdir /data && chown app /data
USER app
WORKDIR /app
COPY --from=build /src/target/home-expense-manager.jar app.jar

# All records live in /data. Mount a local volume there (not a network drive).
ENV HOME_EXPENSE_DATA_DIR=/data \
    SERVER_ADDRESS=0.0.0.0 \
    HOME_EXPENSE_TIMEZONE=Asia/Kolkata
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD ["sh", "-c", "wget -qO- http://127.0.0.1:8080/actuator/health | grep -q UP || exit 1"]
ENTRYPOINT ["java", "-jar", "app.jar"]

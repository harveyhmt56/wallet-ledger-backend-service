FROM eclipse-temurin:21.0.8_9-jdk-jammy AS build
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:21.0.8_9-jre-jammy
RUN groupadd --system wallet && useradd --system --gid wallet wallet
WORKDIR /app
COPY --from=build /workspace/target/wallet-ledger-service-0.0.1-SNAPSHOT.jar app.jar
USER wallet:wallet
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

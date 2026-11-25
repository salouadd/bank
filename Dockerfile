FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S -G app app \
    && mkdir -p /app/uploads/kyc /tmp/app \
    && chown -R app:app /app/uploads /tmp/app
COPY --from=builder /build/target/*.jar app.jar

EXPOSE 8081
USER app:app

ENTRYPOINT ["java", "-Djava.io.tmpdir=/tmp/app", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
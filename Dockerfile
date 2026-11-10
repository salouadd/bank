# -------------------------------------------------------------
# Étape 1 : Phase de compilation (Build)
# -------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

# Mise en cache des dépendances Maven
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copie des sources et compilation sans tests unitaires
COPY src ./src
RUN mvn clean package -DskipTests

# -------------------------------------------------------------
# Étape 2 : Phase d'exécution (Runtime)
# -------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Création du dossier de stockage pour les pièces KYC
RUN mkdir -p uploads/kyc

# Récupération du livrable JAR compilé
COPY --from=builder /build/target/*.jar app.jar

# Exposition du port applicatif
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
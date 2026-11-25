# Identity Service - README20

Microservice Spring Boot de gestion de l'identité et du dossier KYC. Il fournit l'inscription, la connexion, le profil utilisateur, les JWT RSA, le JWKS, le dépôt de documents KYC et la validation administrative.

## Sommaire

- [Fonctionnalités](#fonctionnalités)
- [Sécurité](#sécurité)
- [API](#api)
- [Architecture technique](#architecture-technique)
- [Configuration locale](#configuration-locale)
- [Déploiement Docker Compose](#déploiement-docker-compose)
- [Déploiement Docker Swarm](#déploiement-docker-swarm)
- [Tests](#tests)
- [CI et analyse de sécurité](#ci-et-analyse-de-sécurité)
- [Limites actuelles](#limites-actuelles)

## Fonctionnalités

### Identité

- Inscription publique avec validation des données.
- Normalisation de l'adresse électronique.
- Mots de passe stockés avec BCrypt.
- Rôle serveur `ROLE_USER` à l'inscription; choix de rôle impossible côté client.
- Statuts `PENDING_KYC`, `ACTIVE` et `SUSPENDED`.
- Connexion publique avec JWT Bearer de courte durée.
- Consultation du profil courant via `/users/me`.
- Refus d'authentification des comptes suspendus.
- Bootstrap administrateur ponctuel réservé au profil local.

### KYC

- Dépôt `multipart/form-data` par utilisateur authentifié.
- Types de document PDF, JPEG et PNG.
- Taille maximale par défaut: 10 MiB.
- Vérification de l'extension, du contenu réel et du type MIME.
- Masquage du numéro de document dans les réponses.
- Liste des documents de l'utilisateur courant.
- Liste des dossiers en attente pour les administrateurs.
- Vérification ou rejet d'un dossier; une décision ne peut être appliquée qu'à un dossier `PENDING`.
- Téléchargement administratif en pièce jointe avec `X-Content-Type-Options: nosniff`.
- Clé de stockage opaque et UUID aléatoire; protection contre les chemins KYC arbitraires.
- Nettoyage du fichier stocké en cas d'échec de transaction ou d'échec de base.

### Exploitation

- Flyway pour les migrations PostgreSQL.
- Actuator liveness/readiness et vérification de la base en readiness.
- Arrêt gracieux configuré.
- Pool Hikari configurable.
- OpenAPI JSON et Swagger UI.
- Numéro de corrélation `X-Correlation-ID` dans les réponses.

## Sécurité

### Authentification JWT

- Signature RSA avec `RS256`.
- RSA de 2048 bits minimum.
- Validation du `kid`, de l'algorithme, de la clé publique, de l'issuer, de l'audience, de la signature et de l'expiration.
- Claims: `sub` (adresse électronique, conservée pour la compatibilité interne), `userId` (UUID), `role`, `status`, `iss`, `aud`, `iat` et `exp`.
- Session applicative entièrement stateless.
- Caches de JWKS publics avec `Cache-Control: public, max-age=300`.
- Route publique `/.well-known/jwks.json` sans exposer la clé privée.
- Les jetons ne doivent pas être conservés dans les journaux ou les tickets.

> Les valeurs par défaut de l'issuer et de l'audience sont `identity-service` et `financial-coach-service`, respectivement. Elles peuvent être configurées avec `IDENTITY_JWT_ISSUER` et `IDENTITY_JWT_AUDIENCE`. L'expiration par défaut est 900000 ms (15 minutes).

### Autorisations

- `/users/register` et `/users/login`: publics.
- `/.well-known/jwks.json`: public.
- `/actuator/health/**`: public.
- `/v3/api-docs/**` et Swagger: publics.
- `/users/me` et `/kyc/**`: authentification JWT requise.
- `/kyc/admin/**`: rôle `ROLE_ADMIN` requis.
- Comptes `SUSPENDED`: rejetés même si un JWT valide existe.
- HTTP Basic et formulaires désactivés.

### Durcissement des données et des fichiers

- Limite JSON et limites de multipart configurées.
- Emails normalisés et contraintes de taille/format.
- Mot de passe limité à 72 octets pour BCrypt.
- Erreurs internes génériques: aucun détail technique retourné au client.
- Forme d'erreur centralisée avec statut, message, chemin, timestamp et erreurs de validation.
- Numéro de document masqué dans les réponses utilisateur et administrateur.
- Téléchargement en `attachment`, jamais en affichage inline.
- Clé de stockage validée comme UUID/nom attendu avant résolution sur disque.
- Envoie de fichiers en pièce jointe KYC sans chemin physique exposé.

### Conteneur

L'image Docker exécute l'application comme utilisateur non-root. Le Compose local et le stack Swarm utilisent:

- système de fichiers racine en lecture seule;
- `no-new-privileges`;
- suppression de toutes les capacités Linux;
- `tmpfs` pour les fichiers temporaires;
- arrêt gracieux;
- secret PostgreSQL et clés JWT via Docker Swarm Secrets en production.

## API

Base locale: `http://127.0.0.1:8081`

| Méthode | Route | Accès | Description |
|---|---|---|---|
| `POST` | `/users/register` | Public | Inscription |
| `POST` | `/users/login` | Public | Connexion et JWT |
| `GET` | `/users/me` | JWT | Profil courant |
| `POST` | `/kyc/upload` | JWT | Dépôt documentaire |
| `GET` | `/kyc/documents` | JWT | Documents de l'utilisateur |
| `GET` | `/kyc/admin/pending` | `ROLE_ADMIN` | Dossiers en attente |
| `PUT` | `/kyc/admin/documents/{id}/verify` | `ROLE_ADMIN` | Vérifier un dossier |
| `PUT` | `/kyc/admin/documents/{id}/reject` | `ROLE_ADMIN` | Rejeter avec motif |
| `GET` | `/kyc/admin/documents/{id}/file` | `ROLE_ADMIN` | Télécharger un document |
| `GET` | `/.well-known/jwks.json` | Public | Clés publiques RSA |
| `GET` | `/actuator/health/readiness` | Public | Readiness |
| `GET` | `/actuator/health/liveness` | Public | Liveness |
| `GET` | `/v3/api-docs` | Public | Contrat OpenAPI |
| `GET` | `/swagger-ui/index.html` | Public | Swagger UI |

### Inscription

```powershell
$body = @{
    email = "client@example.com"
    password = "MotDePasse-Test-123!"
    firstName = "Alice"
    lastName = "Dupont"
    dateOfBirth = "1995-04-12"
    address = "1 rue Exemple"
    phoneNumber = "+33123456789"
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri "http://127.0.0.1:8081/users/register" `
  -ContentType "application/json" `
  -Body $body
```

### Connexion

```powershell
$body = @{
    email = "client@example.com"
    password = "MotDePasse-Test-123!"
} | ConvertTo-Json

$auth = Invoke-RestMethod -Method Post `
  -Uri "http://127.0.0.1:8081/users/login" `
  -ContentType "application/json" `
  -Body $body

$token = $auth.accessToken
$auth
```

### Profil

```powershell
Invoke-RestMethod -Method Get `
  -Uri "http://127.0.0.1:8081/users/me" `
  -Headers @{ Authorization = "Bearer $token" }
```

### Dépôt KYC

```powershell
curl.exe -X POST "http://127.0.0.1:8081/kyc/upload" `
  -H "Authorization: Bearer $token" `
  -F "documentType=PASSPORT" `
  -F "documentNumber=AB123456" `
  -F "file=@C:\chemin\vers\document.pdf;type=application/pdf"
```

`DocumentType` doit être une valeur connue du modèle: `PASSPORT`, `NATIONAL_ID` ou `DRIVING_LICENSE`.

## Architecture technique

- Java 21
- Spring Boot 3.4.5
- Spring Security stateless
- JJWT 0.12.6
- Spring Data JPA
- PostgreSQL
- Flyway
- Springdoc/OpenAPI
- Maven
- Docker/Docker Compose/Docker Swarm

Les erreurs principales sont:

| HTTP | Sens |
|---|---|
| `400` | Corps, paramètres ou fichier invalide |
| `401` | Identifiants ou JWT absent/invalide |
| `403` | Rôle insuffisant |
| `404` | Ressource absente |
| `409` | Email déjà enregistré ou transition KYC interdite |
| `413` | Taille maximale dépassée |
| `500` | Erreur interne générique |

## Configuration locale

Prérequis:

- Java 21
- Maven Wrapper inclus
- Docker Desktop recommandé
- PostgreSQL via Docker Compose
- Ports disponibles `5432` et `8081`

Lancer avec Docker Compose:

```powershell
$env:LOCAL_POSTGRES_PASSWORD = "change-this-local-password"
docker compose up --build
```

Arrêter:

```powershell
docker compose down
```

Supprimer aussi les données locales:

```powershell
docker compose down --volumes
```

Lancer avec Maven en local:

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:5432/identity_db"
$env:SPRING_DATASOURCE_USERNAME = "postgres"
$env:SPRING_DATASOURCE_PASSWORD = "change-this-local-password"
.\mvnw.cmd spring-boot:run
```

Important: le profil local autorise les clés JWT éphémères et les régénère au redémarrage. Il ne convient pas à une intégration persistante.

## Déploiement Docker Compose

Depuis PowerShell:

```powershell
$env:LOCAL_POSTGRES_PASSWORD = "change-this-local-password"
docker compose up --build --detach

curl.exe --fail http://127.0.0.1:8081/actuator/health/readiness
curl.exe --fail http://127.0.0.1:8081/.well-known/jwks.json

docker compose ps
docker compose logs --tail=100 identity-service
```

Arrêt propre:

```powershell
docker compose down
docker compose down --volumes
```

## Déploiement Docker Swarm

Le fichier `docker-stack.yml` attend:

- une base PostgreSQL persistante sur un nœud portant le label `identity-data=true`;
- les volumes nommés `postgres_data` et `kyc_data`, locaux au nœud et persistants entre redémarrages;
- une image publishée avec un tag immuable ou digest;
- les secrets Swarm `identity_db_password`, `identity_jwt_private_key` et `identity_jwt_public_key`;
- un réseau overlay externe partagé avec le Gateway;
- des clés RSA PEM persistantes.

Le conteneur expose le port interne `8081`. Il rejoint le réseau overlay externe sous l'alias DNS `identity-service`; il ne publie pas directement ce port sur les hôtes Swarm. Le Gateway et le Financial Coach ne reçoivent que l'URL JWKS publique, jamais les secrets de clé privée.

Créer les clés avec OpenSSL dans un emplacement sécurisé hors du dépôt (et le fichier de mot de passe PostgreSQL dans le même type d'emplacement sécurisé):

```powershell
$keyDirectory = "<chemin-securise-hors-du-depot>"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$keyDirectory\identity-private-key.pem"
openssl pkey -in "$keyDirectory\identity-private-key.pem" -pubout -outform PEM -out "$keyDirectory\identity-public-key.pem"
openssl pkcs8 -topk8 -nocrypt -in "$keyDirectory\identity-private-key.pem" -out "$keyDirectory\identity-private-key-pkcs8.pem"
Move-Item "$keyDirectory\identity-private-key-pkcs8.pem" "$keyDirectory\identity-private-key.pem" -Force
```

Conserver la clé privée hors du dépôt et hors de l'image.
Le profil `prod` refuse les clés éphémères et lit les fichiers montés par les secrets Swarm. Les variables de configuration sont `IDENTITY_JWT_ACTIVE_KEY_ID`, `IDENTITY_JWT_PRIVATE_KEY_LOCATION`, `IDENTITY_JWT_PUBLIC_KEY_LOCATION`, `IDENTITY_JWT_ISSUER` et `IDENTITY_JWT_AUDIENCE`; les deux emplacements de clé pointent vers `/run/secrets/identity_jwt_private_key` et `/run/secrets/identity_jwt_public_key` dans le stack.

Créer le réseau, les secrets et les labels:

```powershell
docker swarm init
docker network create --driver overlay --attachable gateway
docker node update --label-add identity-data=true "<NODE-ID>"

docker secret create identity_db_password "<chemin-securise-hors-du-depot>\db-password.txt"
docker secret create identity_jwt_private_key "$keyDirectory\identity-private-key.pem"
docker secret create identity_jwt_public_key "$keyDirectory\identity-public-key.pem"
```

N'étiquetez qu'un seul nœud `identity-data=true`: les volumes Docker locaux ne sont ni partagés entre les nœuds ni répliqués. Prévoir sauvegarde et restauration séparées pour PostgreSQL et les documents KYC.

Depuis la racine du dépôt, construire l'image avec le `Dockerfile` fourni puis la publier dans le registre accessible aux nœuds Swarm. Remplacer les deux valeurs entre chevrons par le registre et un tag immuable de votre organisation:

```powershell
$env:IDENTITY_IMAGE = "<registre>/<espace>/identity-service:<tag-immuable>"
docker build --pull --file Dockerfile --tag $env:IDENTITY_IMAGE .
docker push $env:IDENTITY_IMAGE
```

`POSTGRES_IMAGE` doit également désigner une image PostgreSQL validée et épinglée. Les valeurs d'issuer et d'audience ci-dessus correspondent aux valeurs attendues par défaut par le Financial Coach; définissez-les selon son contrat si elles sont modifiées.

Déployer l'image publiée:

```powershell
$env:POSTGRES_IMAGE = "<image-postgresql-approuvee-et-immuable>"
$env:IDENTITY_IMAGE = "<registre>/<espace>/identity-service:<tag-immuable>"
$env:IDENTITY_JWT_ACTIVE_KEY_ID = "<identifiant-de-cle>"
$env:IDENTITY_JWT_ISSUER = "identity-service"
$env:IDENTITY_JWT_AUDIENCE = "financial-coach-service"
$env:GATEWAY_OVERLAY_NETWORK = "gateway"

docker stack deploy --with-registry-auth -c docker-stack.yml identity
docker stack services identity
docker stack ps identity
```

Pour passer une clé publique de vérification supplémentaire lors d'une rotation, configurer `identity.jwt.verification-keys` avec un `kid` et un emplacement de clé publique distinct. Conserver la nouvelle clé publique avant d'émettre des jetons avec le nouveau `kid`, puis retirer l'ancienne après expiration des jetons et du cache JWKS.

Vérifier depuis le réseau du Gateway:

```powershell
docker exec "<gateway-container>" curl --fail http://identity-service:8081/actuator/health/readiness
docker exec "<gateway-container>" curl --fail http://identity-service:8081/.well-known/jwks.json
```

## Tests

Lancer toute la suite Maven:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

Lancer un test ciblé:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress -Dtest=SecurityAuthorizationTest test
```

Profil test avec PostgreSQL:

```powershell
$env:SPRING_PROFILES_ACTIVE = "test"
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://127.0.0.1:5432/identity_db"
$env:SPRING_DATASOURCE_USERNAME = "postgres"
$env:SPRING_DATASOURCE_PASSWORD = "ci-only-password"
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

La suite couvre notamment:

- chargement du contexte Spring;
- inscription, normalisation, duplication et contraintes email;
- expiration de BCrypt;
- connexion valide/invalide et compte suspendu;
- stockage, lecture et suppression des documents;
- refus des extensions, signatures ou tailles invalides;
- nettoyage après rollback base;
- masquage du numéro de document;
- transitions KYC vérifié/rejeté/interdit;
- JWT signé, expiration, `kid`, clé inconnue et falsification;
- rotation des clés;
- autorisation utilisateur/admin;
- routes publiques et protégées;
- `X-Correlation-ID`;
- JWKS et contrat OpenAPI.

Vérifications HTTP rapides:

```powershell
curl.exe --fail http://127.0.0.1:8081/actuator/health/liveness
curl.exe --fail http://127.0.0.1:8081/actuator/health/readiness
curl.exe --fail http://127.0.0.1:8081/.well-known/jwks.json
curl.exe --fail http://127.0.0.1:8081/v3/api-docs
curl.exe --fail http://127.0.0.1:8081/swagger-ui/index.html
```

Test négatif sans JWT:

```powershell
curl.exe -i http://127.0.0.1:8081/kyc/documents
```

Résultat attendu: `401 Unauthorized`.

## CI et analyse de sécurité

Le pipeline GitHub Actions exécute:

1. compilation Java 21 et tests Maven avec PostgreSQL;
2. Gitleaks sur l'historique Git;
3. Semgrep Java/OWASP Top Ten;
4. Trivy des dépendances Maven;
5. build et scan Trivy de l'image Docker;
6. démarrage Compose puis OWASP ZAP baseline.

Commandes locales équivalentes:

```powershell
docker build --tag identity-service:test .

docker run --rm `
  -v "${PWD}:/src" `
  aquasec/trivy:0.58.1 fs `
  --scanners vuln --severity HIGH,CRITICAL --exit-code 1 `
  --format json --output /src/trivy-dependencies-report.json /src

docker run --rm `
  -v "${PWD}:/src" `
  semgrep/semgrep:1.100.0 semgrep scan `
  --config=p/java --config=p/owasp-top-ten --error `
  --json --output=/src/semgrep-report.json /src

docker run --rm `
  -v "${PWD}:/path" `
  zricethezav/gitleaks:v8.24.2 detect --redact `
  --source=/path --report-format=json --report-path=/path/gitleaks-report.json
```

Gitleaks bloque toute détection, Semgrep bloque les erreurs configurées et Trivy bloque les vulnérabilités `HIGH`/`CRITICAL`. Toute exception temporaire doit être approuvée, datée et suivie par une issue.

## Limites actuelles

Avant une mise en production réelle, les points suivants doivent être décidés ou implémentés:

- le stockage KYC utilise un volume Swarm local persistant au nœud, sans réplication ni mécanisme de sauvegarde/restauration fourni;
- la stratégie de sauvegarde/restauration PostgreSQL n'est pas fournie;
- aucune révocation immédiate, fermeture de compte libre-service ou mécanisme de rotation des tokens n'existe;
- le provisioning de production des comptes administrateurs n'est pas implémenté;
- les clés JWT doivent être persistées et gérées via un coffre-fort approuvé;
- l'antivirus, le malware scanning ou CDR ne remplace pas les contrôles de fichier actuels;
- CORS est désactivé côté microservice et doit être géré par l'architecture aval;
- Swagger/OpenAPI peut être désactivé ou masqué selon l'exposition voulue;
- les journaux ne doivent jamais contenir JWT, mot de passe, contenu KYC ou numéro de document.

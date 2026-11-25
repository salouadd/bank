# Identity Service — commandes et requêtes pour utiliser le microservice

Ce guide montre comment démarrer le service en local puis appeler ses routes dans l'ordre. Il décrit le comportement actuellement implémenté; ce n'est pas une procédure de production. Le stockage KYC est local. Un compte admin peut être provisionné ponctuellement avec la commande locale décrite ci-dessous; aucun mécanisme de provisionnement de production n'est fourni.

## 1. Prérequis

- Java 21 si l'application est démarrée avec Maven.
- PostgreSQL local accessible avec la configuration définie dans `src/main/resources/application-local.yaml`, ou Docker Desktop pour Compose.
- PowerShell à la racine du dépôt.
- Pour déposer un document KYC, un fichier réellement conforme en PDF, JPEG ou PNG (pas un fichier texte simplement renommé).

URL de base par défaut : `http://localhost:8081`.

> **Important :** les commandes qui utilisent `$token`, `$headers` ou `$adminHeaders` doivent être exécutées dans la même fenêtre PowerShell que la commande de connexion correspondante. Un JWT est temporaire et ne doit pas être partagé.

### Parcours rapide pour le compte admin local déjà configuré

Le compte `client@example.com` a été promu `ROLE_ADMIN` dans votre base locale. Le document `4efc113c-d8d2-469a-aa48-d29d694f55f8` a déjà été vérifié (`VERIFIED`); ne relancez pas la décision sur ce document. Pour vérifier le compte et lister les prochains documents en attente :

```powershell
$loginBody = @{
    email = "client@example.com"
    password = "Local-Password-123!"
} | ConvertTo-Json

$login = Invoke-RestMethod `
    -Method Post `
    -Uri "http://localhost:8081/users/login" `
    -ContentType "application/json" `
    -Body $loginBody

$adminToken = $login.accessToken
$adminHeaders = @{ Authorization = "Bearer $adminToken" }

Invoke-RestMethod `
    -Method Get `
    -Uri "http://localhost:8081/users/me" `
    -Headers $adminHeaders

Invoke-RestMethod `
    -Method Get `
    -Uri "http://localhost:8081/kyc/admin/pending" `
    -Headers $adminHeaders
```

Le profil doit afficher `ROLE_ADMIN`. La liste `/kyc/admin/pending` donne les UUID des documents qui peuvent encore être vérifiés ou rejetés.

## 2. Démarrer l'application

### Option A — PostgreSQL et service avec Docker Compose

Dans PowerShell, depuis le répertoire du projet :

```powershell
$env:LOCAL_POSTGRES_PASSWORD = "LocalOnly-ChangeThis-123!"
docker compose up --build -d
docker compose ps
docker compose logs -f identity-service
```

Le mot de passe ci-dessus est un exemple local uniquement. Pour un vrai environnement partagé, choisissez un mot de passe local sûr. Compose ne démarre pas si `LOCAL_POSTGRES_PASSWORD` n'est pas défini. L'API et PostgreSQL sont liés à `127.0.0.1`, donc les ports ne sont pas publiés sur toutes les interfaces réseau.

Vérifier que le service est prêt :

```powershell
Invoke-RestMethod http://localhost:8081/actuator/health/readiness
```

La réponse attendue a un statut `UP`. La route `/actuator/health` est également disponible; aucun détail interne n'est exposé.

Pour arrêter les conteneurs en conservant les données locales :

```powershell
docker compose down
```

Ne pas ajouter `-v` sauf si vous souhaitez supprimer les volumes PostgreSQL et KYC de développement.

### Option B — PostgreSQL déjà installé localement

1. Vérifier que la base et l'utilisateur attendus existent, ou remplacer les variables par vos paramètres PostgreSQL :

```powershell
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/identity_db"
$env:SPRING_DATASOURCE_USERNAME = "postgres"
$env:SPRING_DATASOURCE_PASSWORD = "votre-mot-de-passe-local"
$env:SPRING_PROFILES_ACTIVE = "local"
```

2. Démarrer l'application :

```powershell
.\mvnw.cmd spring-boot:run
```

Flyway applique les migrations disponibles au démarrage. Garder cette fenêtre PowerShell ouverte pendant les requêtes.

## 3. Faire les requêtes dans l'ordre

### 3.1 Vérifier l'état du service

```powershell
Invoke-RestMethod -Method Get -Uri "http://localhost:8081/actuator/health"
Invoke-RestMethod -Method Get -Uri "http://localhost:8081/actuator/health/readiness"
```

### 3.2 Créer un compte utilisateur

L'inscription est publique. Le serveur fixe le rôle `ROLE_USER` et le statut `PENDING_KYC`; le client ne peut pas choisir ces valeurs.

```powershell
$registerBody = @{
    email = "client@example.com"
    password = "Local-Password-123!"
    firstName = "Ada"
    lastName = "Lovelace"
    dateOfBirth = "1990-12-10"
    address = "10 rue Exemple"
    phoneNumber = "+33123456789"
} | ConvertTo-Json

$newUser = Invoke-RestMethod `
    -Method Post `
    -Uri "http://localhost:8081/users/register" `
    -ContentType "application/json" `
    -Body $registerBody

$newUser | ConvertTo-Json -Depth 5
```

Résultat attendu : HTTP `201 Created`, avec notamment `id`, `email`, `role`, `status`, `firstName` et `lastName`. Le mot de passe ne doit jamais être inclus dans une requête de ticket ou un journal.

Règles actuelles : mot de passe de 8 à 72 caractères et au plus 72 octets UTF-8; email valide; prénom/nom requis. Les emails sont normalisés. Un email déjà utilisé renvoie `409 Conflict`; les entrées invalides renvoient `400 Bad Request`.

### 3.3 Se connecter et conserver le JWT

```powershell
$loginBody = @{
    email = "client@example.com"
    password = "Local-Password-123!"
} | ConvertTo-Json

$login = Invoke-RestMethod `
    -Method Post `
    -Uri "http://localhost:8081/users/login" `
    -ContentType "application/json" `
    -Body $loginBody

$token = $login.accessToken
$login | Select-Object tokenType, expiresIn
```

La réponse contient `accessToken`, `tokenType` (`Bearer`) et `expiresIn` en secondes. Garder `$token` uniquement pour cette session de test; ne pas le publier ni le journaliser. Un email/mot de passe incorrect renvoie `401 Unauthorized`.

### 3.4 Lire le profil authentifié

```powershell
$headers = @{
    Authorization = "Bearer $token"
}

Invoke-RestMethod `
    -Method Get `
    -Uri "http://localhost:8081/users/me" `
    -Headers $headers |
    ConvertTo-Json -Depth 5
```

Sans jeton valide, les routes protégées répondent `401 Unauthorized`.

### 3.5 Déposer un document KYC

Le document doit être présent sur le disque. Les signatures de contenu prises en charge sont PDF, JPEG et PNG; l'extension doit correspondre. La limite par défaut est 10 MiB.

Avec `curl.exe` depuis PowerShell (remplacer le chemin par celui d'un vrai fichier) :

```powershell
curl.exe -i `
  -X POST "http://localhost:8081/kyc/upload" `
  -H "Authorization: Bearer $token" `
  -F "documentType=PASSPORT" `
  -F "documentNumber=AB1234567" `
  -F "file=@C:\Users\Public\Documents\passport.pdf;type=application/pdf"
```

Valeurs possibles pour `documentType` : `PASSPORT`, `NATIONAL_ID`, `DRIVING_LICENSE`. Résultat attendu : HTTP `201 Created`, avec l'identifiant du document et `documentNumberMasked`; le numéro complet n'est pas renvoyé. Un fichier vide, trop grand, à extension interdite ou dont le contenu ne correspond pas à l'extension est rejeté.

### 3.6 Lister ses documents KYC

```powershell
Invoke-RestMethod `
    -Method Get `
    -Uri "http://localhost:8081/kyc/documents" `
    -Headers $headers |
    ConvertTo-Json -Depth 5
```

Cette route ne liste que les documents de l'utilisateur associé au jeton.

### 3.7 Récupérer la JWKS publique

La JWKS ne nécessite pas de jeton et contient les clés publiques permettant aux consommateurs de vérifier la signature RS256 :

```powershell
Invoke-RestMethod `
    -Method Get `
    -Uri "http://localhost:8081/.well-known/jwks.json" |
    ConvertTo-Json -Depth 8
```

La réponse contient `keys`, avec les champs JWK tels que `kty`, `alg`, `kid`, `n` et `e`. La clé privée n'est jamais publiée. Les consommateurs doivent choisir la clé dont le `kid` correspond à l'en-tête du JWT et vérifier aussi l'expiration et l'émetteur.

### 3.8 Consulter le contrat OpenAPI

Ouvrir l'une de ces adresses dans un navigateur :

- Swagger UI : `http://localhost:8081/swagger-ui/index.html`
- OpenAPI JSON : `http://localhost:8081/v3/api-docs`

Les routes documentent les payloads et le schéma `Bearer JWT`. Seules les opérations `/kyc/admin/**` nécessitent en plus le rôle `ROLE_ADMIN`.

## 4. Routes administrateur KYC

Ces routes ne fonctionnent qu'avec un JWT rattaché à un compte possédant réellement `ROLE_ADMIN` :

```text
GET  /kyc/admin/pending
PUT  /kyc/admin/documents/{id}/verify
PUT  /kyc/admin/documents/{id}/reject
GET  /kyc/admin/documents/{id}/file
```

Exemple de lecture des dossiers en attente :

```powershell
Invoke-RestMethod `
    -Method Get `
    -Uri "http://localhost:8081/kyc/admin/pending" `
    -Headers $headers
```

Exemple de rejet (le compte doit être administrateur et l'UUID doit être celui d'un dossier `PENDING`) :

```powershell
$documentId = "00000000-0000-0000-0000-000000000000"
$rejectBody = @{
    rejectionReason = "Le document est illisible; merci de déposer une nouvelle image."
} | ConvertTo-Json

Invoke-RestMethod `
    -Method Put `
    -Uri "http://localhost:8081/kyc/admin/documents/$documentId/reject" `
    -Headers $headers `
    -ContentType "application/json" `
    -Body $rejectBody
```

L'UUID ci-dessus est un exemple à remplacer par l'identifiant retourné après un dépôt. Un compte utilisateur normal reçoit `403 Forbidden`.

### Provisionner un administrateur pour les essais locaux

Cette commande est un outil ponctuel **uniquement pour le développement local**. Elle ne crée pas de compte : elle promeut un compte existant après vérification de son adresse. Elle ne crée aucune route HTTP. Elle exige le profil `local`, une base dont l'hôte est `localhost`, `127.0.0.1` ou `::1`, et s'exécute sans serveur HTTP. Le profil `prod` et toute base distante sont refusés.

1. Vérifier que le compte a déjà été inscrit et que PostgreSQL locale est démarrée. La base configurée doit être celle utilisée par le service.
2. Dans une nouvelle fenêtre PowerShell ouverte à la racine du dépôt, réutiliser les mêmes paramètres de connexion que pour le service. Avec Compose, si `LOCAL_POSTGRES_PASSWORD` est déjà défini :

```powershell
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/identity_db"
$env:SPRING_DATASOURCE_USERNAME = "postgres"
$env:SPRING_DATASOURCE_PASSWORD = $env:LOCAL_POSTGRES_PASSWORD
```

Si l'application utilise une autre base locale, indiquez ici exactement la même URL, le même utilisateur et le même mot de passe que ceux du service.

3. Exécuter la commande ponctuelle en remplaçant l'adresse par celle du compte existant :

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.jvmArguments=-Dspring.profiles.active=local -Didentity.admin.bootstrap-only=true -Didentity.admin.bootstrap-email=client@example.com"
```

La commande doit se terminer après avoir indiqué que le rôle a été provisionné. En cas d'erreur, vérifier l'adresse, le profil local et les paramètres de la base; ne pas relancer avec une base distante.

4. Se reconnecter pour obtenir un nouveau JWT, puis confirmer le rôle retourné par `GET /users/me` :

```powershell
$login = Invoke-RestMethod `
    -Method Post `
    -Uri "http://localhost:8081/users/login" `
    -ContentType "application/json" `
    -Body (@{
        email = "client@example.com"
        password = "Local-Password-123!"
    } | ConvertTo-Json)

$adminToken = $login.accessToken
$adminHeaders = @{ Authorization = "Bearer $adminToken" }

Invoke-RestMethod `
    -Method Get `
    -Uri "http://localhost:8081/users/me" `
    -Headers $adminHeaders |
    ConvertTo-Json -Depth 5
```

La réponse de `/users/me` doit afficher `"role": "ROLE_ADMIN"`.

5. Valider le document en attente avec son UUID réel :

```powershell
$documentId = "4efc113c-d8d2-469a-aa48-d29d694f55f8"

Invoke-RestMethod `
    -Method Put `
    -Uri "http://localhost:8081/kyc/admin/documents/$documentId/verify" `
    -Headers $adminHeaders |
    ConvertTo-Json -Depth 5
```

Le compte `PENDING_KYC` passe actuellement à `ACTIVE` lorsqu'un document est vérifié. Cette règle est provisoire et doit être confirmée par produit/conformité. Ce mécanisme d'élévation local ne doit jamais être utilisé comme procédure de production; le provisionnement admin de production, son audit et sa gouvernance restent à concevoir.

## 5. Erreurs fréquentes

| HTTP | Cause habituelle | Vérification |
|---|---|---|
| `400` | JSON invalide, champ requis manquant, type KYC invalide ou fichier refusé | Vérifier le contrat OpenAPI et les types `PASSPORT`, `NATIONAL_ID`, `DRIVING_LICENSE` |
| `401` | Jeton absent/expiré/invalide ou identifiants incorrects | Se reconnecter et envoyer `Authorization: Bearer <accessToken>` |
| `403` | Utilisateur authentifié sans le rôle `ADMIN` sur une route KYC admin | Utiliser un compte administrateur provisionné par un processus approuvé |
| `404` | UUID de ressource inexistant | Vérifier l'identifiant retourné par l'API |
| `409` | Email déjà enregistré ou décision KYC répétée/invalide | Utiliser une adresse différente ou vérifier l'état du document |
| `413` | Requête/fichier au-dessus de la limite multipart | Respecter la limite configurée et la marge de taille de requête |
| `500` | Erreur interne, base ou stockage local indisponible | Examiner les logs côté service avec le `X-Correlation-ID`, sans y copier de secret |

Les réponses d'erreur contiennent notamment `status`, `error`, `message`, `path` et `timestamp`; les erreurs de validation peuvent aussi inclure `validationErrors`.

## 6. Lancer les tests

Avec Java 21 et PostgreSQL configurée comme dans `application-test.yaml` :

```powershell
.\mvnw.cmd clean verify
```

La dernière validation consignée dans le dépôt a réussi avec 51 tests. Les tests utilisent encore la PostgreSQL locale et Docker/CI ne sont pas validés par cette commande seule.

## 7. Limites importantes

- Utiliser uniquement des données fictives en local; ne pas envoyer de documents d'identité réels dans un environnement de développement.
- Le stockage KYC local convient au développement, pas à une production multi-instance.
- Les fichiers ne sont pas analysés par antivirus/CDR.
- L'activation du compte après vérification d'un seul document est une règle provisoire à confirmer avec produit/conformité.
- La vérification d'email, le reset de mot de passe, la limitation des tentatives et la révocation immédiate des sessions ne sont pas encore implémentés.
- Aucun AWS n'est configuré par ces étapes. Le profil `prod` n'est pas exploitable tant qu'un fournisseur KYC durable et un mécanisme de provisionnement administrateur de production ne sont pas disponibles.

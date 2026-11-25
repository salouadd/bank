# Identity Service — guide complet des commandes locales

Ce guide PowerShell décrit, pas à pas, le démarrage du microservice, l'inscription et la connexion, les appels utilisateur, le dépôt KYC et les actions d'administration. Il s'agit d'un parcours de développement local, pas d'une procédure de production.

## 0. Avant de commencer

Ouvrir **PowerShell** à la racine du dépôt :

```powershell
Set-Location "C:\Users\bnsal\IdeaProjects\identity-service"
Get-Location
```

Prérequis : Docker Desktop pour le parcours Compose, ou Java 21 et PostgreSQL pour le parcours Maven. L'API est disponible sur `http://localhost:8081`.

Dans les exemples, les variables `$token`, `$headers`, `$adminToken` et `$adminHeaders` sont conservées en mémoire dans la fenêtre PowerShell courante. Si vous ouvrez une autre fenêtre, refaites la connexion pour recréer ces variables. Ne publiez ni mot de passe ni JWT.

## 1. Démarrer PostgreSQL et l'API avec Docker Compose

Définir un mot de passe local de développement, puis construire et démarrer les deux conteneurs :

```powershell
$env:LOCAL_POSTGRES_PASSWORD = "LocalOnly-ChangeThis-123!"
docker compose up --build -d
```

Vérifier que les conteneurs tournent et que l'API est prête :

```powershell
docker compose ps
Invoke-RestMethod -Method Get -Uri "http://localhost:8081/actuator/health/readiness"
```

La réponse de readiness doit afficher `UP`. Si le service n'est pas encore prêt, attendre quelques secondes puis réessayer. Pour suivre les logs, dans une deuxième fenêtre PowerShell :

```powershell
Set-Location "C:\Users\bnsal\IdeaProjects\identity-service"
docker compose logs -f identity-service
```

L'API et PostgreSQL ne sont publiées que sur l'interface locale. `docker compose down` arrête les conteneurs en conservant les données. Ne pas ajouter `-v` sauf si vous souhaitez aussi supprimer les données locales.

## 2. Variante : démarrer l'API avec Maven Wrapper

Utiliser cette variante si PostgreSQL est déjà démarrée localement. Dans la fenêtre de l'application, définir la connexion à la base (remplacer le mot de passe par celui de votre PostgreSQL), activer le profil `local`, puis démarrer le service :

```powershell
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/identity_db"
$env:SPRING_DATASOURCE_USERNAME = "postgres"
$env:SPRING_DATASOURCE_PASSWORD = "votre-mot-de-passe-local"
$env:SPRING_PROFILES_ACTIVE = "local"
.\mvnw.cmd spring-boot:run
```

Garder cette fenêtre ouverte. Dans une autre fenêtre, les requêtes HTTP sont envoyées à `http://localhost:8081`. Si Compose a déjà démarré le service sur ce port, ne démarrez pas également l'application avec Maven.

## 3. Vérifier la santé de l'API et ouvrir Swagger

Les commandes suivantes ne nécessitent pas de connexion :

```powershell
Invoke-RestMethod -Method Get -Uri "http://localhost:8081/actuator/health"
Invoke-RestMethod -Method Get -Uri "http://localhost:8081/actuator/health/readiness"
```

Documentation interactive et contrat OpenAPI :

```powershell
Start-Process "http://localhost:8081/swagger-ui/index.html"
Start-Process "http://localhost:8081/v3/api-docs"
```

## 4. Inscrire un utilisateur (facultatif si le compte existe déjà)

L'inscription donne le rôle `ROLE_USER` et le statut `PENDING_KYC`; le client ne peut pas choisir son rôle. Cet exemple crée un compte de test. Utiliser une adresse différente si l'adresse est déjà enregistrée :

```powershell
$registerBody = @{
    email = "nouveau-client@example.com"
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

La réponse attendue est HTTP `201 Created`. Une adresse déjà utilisée renvoie `409 Conflict`. Ne jamais mettre un mot de passe réel dans un ticket ou dans des logs.

## 5. Se connecter et appeler les routes utilisateur

### 5.1 Connexion

Pour le compte admin local utilisé dans les essais précédents :

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
$headers = @{ Authorization = "Bearer $token" }
$login | Select-Object tokenType, expiresIn
```

La réponse fournit un JWT (`accessToken`), le type `Bearer` et sa durée de vie en secondes. Le jeton expire; en cas de `401`, refaire la connexion et recréer les variables d'en-têtes.

Pour un autre compte, remplacez l'adresse et le mot de passe dans `$loginBody` par ceux utilisés lors de son inscription.

### 5.2 Lire le profil connecté

```powershell
Invoke-RestMethod `
    -Method Get `
    -Uri "http://localhost:8081/users/me" `
    -Headers $headers |
    ConvertTo-Json -Depth 5
```

La réponse contient notamment le rôle et le statut (`PENDING_KYC` ou `ACTIVE`).

### 5.3 Lister les documents KYC du compte

```powershell
Invoke-RestMethod `
    -Method Get `
    -Uri "http://localhost:8081/kyc/documents" `
    -Headers $headers |
    ConvertTo-Json -Depth 5
```

Cette route ne retourne que les documents de l'utilisateur authentifié.

## 6. Déposer un document KYC

Un PDF, JPEG ou PNG réel est requis; renommer un fichier texte en `.pdf` ne suffit pas. Le contenu doit correspondre à l'extension et la limite locale par défaut est 10 MiB.

Trouver un PDF à la racine du projet et vérifier son chemin :

```powershell
$pdf = Get-ChildItem -File -Filter *.pdf | Select-Object -First 1
if ($null -eq $pdf) { throw "Aucun PDF trouvé à la racine du projet. Placez-y un fichier de test valide." }
$pdfPath = $pdf.FullName
$pdfPath
Test-Path $pdfPath
```

Utiliser uniquement un document de test fictif, pas une pièce d'identité réelle. Envoyer le formulaire multipart avec `curl.exe` (les valeurs possibles pour `documentType` sont `PASSPORT`, `NATIONAL_ID` et `DRIVING_LICENSE`) :

```powershell
$uploadJson = curl.exe -sS `
    -X POST "http://localhost:8081/kyc/upload" `
    -H "Authorization: Bearer $token" `
    -F "documentType=PASSPORT" `
    -F "documentNumber=TEST123456" `
    -F "file=@${pdfPath};type=application/pdf"

$uploadedDocument = $uploadJson | ConvertFrom-Json
$uploadedDocument | ConvertTo-Json -Depth 5
$documentId = $uploadedDocument.id
```

La réponse attendue est HTTP `201`; elle contient un identifiant et un numéro masqué, jamais le numéro complet. Conserver `$documentId` pour les étapes suivantes. Si `curl.exe` indique qu'il ne peut pas lire le fichier, revérifier `$pdfPath` et `Test-Path $pdfPath`. Si l'API renvoie que le contenu n'est pas un PDF, vérifier que le fichier est réellement un PDF valide.

## 7. Obtenir le rôle administrateur local

Cette étape est déjà faite pour `client@example.com` dans la base locale utilisée lors des essais. **Ne la relancez pas si `/users/me` affiche déjà `ROLE_ADMIN`.**

Le provisionnement ponctuel ne crée pas de compte et n'est autorisé qu'en profil `local`, avec une base de données sur `localhost`/loopback et sans démarrer le serveur web. Il est interdit pour `prod` et toute base distante. Pour un autre compte de test, l'inscrire d'abord (étape 4).

Avec Docker Compose, ouvrir une nouvelle fenêtre PowerShell, définir les mêmes paramètres que la base Compose, puis lancer le provisionnement en remplaçant l'email :

```powershell
Set-Location "C:\Users\bnsal\IdeaProjects\identity-service"
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/identity_db"
$env:SPRING_DATASOURCE_USERNAME = "postgres"
$env:SPRING_DATASOURCE_PASSWORD = "LocalOnly-ChangeThis-123!"

.\mvnw.cmd spring-boot:run "-Dspring-boot.run.jvmArguments=-Dspring.profiles.active=local -Didentity.admin.bootstrap-only=true -Didentity.admin.bootstrap-email=client@example.com"
```

Le mot de passe de la variable doit être exactement celui défini pour Compose à l'étape 1. La commande doit se terminer après avoir indiqué que le rôle a été provisionné. Ne pas lancer cette commande sur une base distante ou en production.

## 8. Se reconnecter en tant qu'administrateur

Après le provisionnement, refaire une connexion pour obtenir un nouveau JWT. Exécuter dans la fenêtre où vous ferez les requêtes d'administration :

```powershell
$adminLoginBody = @{
    email = "client@example.com"
    password = "Local-Password-123!"
} | ConvertTo-Json

$adminLogin = Invoke-RestMethod `
    -Method Post `
    -Uri "http://localhost:8081/users/login" `
    -ContentType "application/json" `
    -Body $adminLoginBody

$adminToken = $adminLogin.accessToken
$adminHeaders = @{ Authorization = "Bearer $adminToken" }

$adminProfile = Invoke-RestMethod `
    -Method Get `
    -Uri "http://localhost:8081/users/me" `
    -Headers $adminHeaders

$adminProfile | ConvertTo-Json -Depth 5
```

Vérifier que la réponse indique `"role": "ROLE_ADMIN"`. Si le rôle affiché est `ROLE_USER`, le provisionnement a probablement ciblé une autre base; vérifier l'URL et les identifiants PostgreSQL employés par le service et par la commande ponctuelle.

## 9. Examiner et décider les dossiers KYC

### 9.1 Liste des documents en attente

```powershell
$pendingDocuments = Invoke-RestMethod `
    -Method Get `
    -Uri "http://localhost:8081/kyc/admin/pending" `
    -Headers $adminHeaders

$pendingDocuments | ConvertTo-Json -Depth 5
```

Choisir l'UUID (`id`) du document dont le statut est `PENDING`, puis le copier dans `$documentId` :

```powershell
$documentId = "COLLER-ICI-L-UUID-DU-DOCUMENT-PENDING"
```

Ne pas utiliser l'UUID d'un document déjà `VERIFIED` ou `REJECTED`. Le document `4efc113c-d8d2-469a-aa48-d29d694f55f8` des essais a déjà été vérifié; une décision ne peut s'appliquer qu'une seule fois à un document en attente.

### 9.2 Télécharger le fichier pour examen (facultatif)

Le fichier est téléchargé dans le répertoire courant. Remplacer l'UUID par celui choisi ci-dessus :

```powershell
Invoke-WebRequest `
    -Method Get `
    -Uri "http://localhost:8081/kyc/admin/documents/$documentId/file" `
    -Headers $adminHeaders `
    -OutFile ".\document-kyc-a-examiner"
```

### 9.3 Vérifier le document

À exécuter uniquement si le document est `PENDING` et que vous souhaitez l'approuver :

```powershell
$verifiedDocument = Invoke-RestMethod `
    -Method Put `
    -Uri "http://localhost:8081/kyc/admin/documents/$documentId/verify" `
    -Headers $adminHeaders

$verifiedDocument | ConvertTo-Json -Depth 5
```

La réponse doit indiquer `verificationStatus: VERIFIED`. Le comportement implémenté fait passer le compte `PENDING_KYC` à `ACTIVE` lorsqu'un document est vérifié. Confirmer ensuite le profil :

```powershell
Invoke-RestMethod `
    -Method Get `
    -Uri "http://localhost:8081/users/me" `
    -Headers $adminHeaders |
    ConvertTo-Json -Depth 5
```

### 9.4 Rejeter le document

À utiliser à la place de la vérification, seulement si le document est `PENDING`. Le motif est obligatoire et limité à 1000 caractères :

```powershell
$rejectBody = @{
    rejectionReason = "Le document de test est illisible; merci d'en déposer un autre."
} | ConvertTo-Json

$rejectedDocument = Invoke-RestMethod `
    -Method Put `
    -Uri "http://localhost:8081/kyc/admin/documents/$documentId/reject" `
    -Headers $adminHeaders `
    -ContentType "application/json" `
    -Body $rejectBody

$rejectedDocument | ConvertTo-Json -Depth 5
```

Un document rejeté ne peut pas ensuite être vérifié par une nouvelle décision; déposer un nouveau document si nécessaire.

## 10. Vérifier la JWKS publique

Cette route ne demande pas de JWT. Elle publie la clé publique utilisée par les consommateurs pour vérifier les signatures JWT :

```powershell
Invoke-RestMethod `
    -Method Get `
    -Uri "http://localhost:8081/.well-known/jwks.json" |
    ConvertTo-Json -Depth 8
```

## 11. Utiliser Swagger UI avec le JWT

Swagger n'envoie pas de JWT tant que vous ne l'avez pas autorisé. Après l'étape 8 :

1. Ouvrir `http://localhost:8081/swagger-ui/index.html`.
2. Cliquer sur **Authorize** (icône cadenas).
3. Dans `bearerAuth`, coller la valeur de `$adminToken` (le jeton uniquement; Swagger applique le schéma Bearer), puis cliquer sur **Authorize** et fermer la fenêtre.
4. Ouvrir `PUT /kyc/admin/documents/{id}/verify`, saisir l'UUID d'un document qui est encore `PENDING`, puis cliquer sur **Execute**.

Si la requête Swagger n'a pas d'en-tête `Authorization`, elle échouera en `401`. Un JWT valide d'un compte sans rôle admin produit `403` pour une route `/kyc/admin/**`.

## 12. Tests et arrêt

Lancer les tests depuis la racine du dépôt avec Java 21 :

```powershell
.\mvnw.cmd clean verify
```

Arrêter les conteneurs sans supprimer les données :

```powershell
docker compose down
```

## 13. Erreurs fréquentes

| Code | Signification et action |
|---|---|
| `400` | Paramètre ou fichier invalide; vérifier les champs et la signature réelle PDF/JPEG/PNG. |
| `401` | JWT absent, invalide ou expiré; se reconnecter et reconstruire `Authorization = "Bearer <jeton>"`. |
| `403` | JWT valide mais compte sans rôle `ROLE_ADMIN`; vérifier `/users/me`. |
| `404` | UUID de document inconnu; reprendre l'UUID retourné par l'API. |
| `409` | Email déjà inscrit ou décision impossible sur un document qui n'est plus `PENDING`; consulter la liste et l'état du document. |
| `413` | Fichier trop volumineux; respecter la limite configurée de 10 MiB. |
| Erreur de connexion PostgreSQL | Vérifier que la base tourne, puis comparer l'URL, le nom d'utilisateur et le mot de passe utilisés par l'API et le provisionnement admin. |

## 14. Limites de l'environnement local

- N'utiliser que des comptes et documents fictifs pour les essais.
- Le stockage de documents local est conçu pour le développement, pas pour un déploiement multi-instance.
- Le contrôle antivirus/CDR n'est pas configuré.
- L'activation après vérification d'un seul document est une règle implémentée à confirmer avec produit/conformité.
- Le provisionnement admin décrit ici est strictement local; un processus de production auditable reste à concevoir.

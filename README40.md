# État d'avancement — Identity, API Gateway et Financial Coach

Ce document est un compte rendu opérationnel de l'environnement Docker Desktop décrit pendant le déploiement. Il précise ce que fait chaque microservice, ce qui a été fait, les résultats vérifiés, les tests à reprendre et les éléments qui restent à terminer.

> **État en date du 25 novembre 2026 :** Identity et API Gateway fonctionnent dans Docker Swarm et la communication Gateway → Identity est vérifiée. Une copie locale de test d'Identity a aussi démarré avec Docker Compose sur le port `18081` pour naviguer dans Swagger. Financial Coach n'est pas encore déployé. Ce Swarm Docker Desktop à un seul nœud est un environnement de test, pas une architecture de production haute disponibilité.

## 1. Vue d'ensemble

```text
Navigateur / application frontend
              |
              | HTTP en test, HTTPS à mettre en place avant production
              v
API Gateway — hôte localhost:8080, réseau overlay "gateway"
       |                       |
       |                       +--> Redis pour les fonctions du Gateway
       v
Identity Service — DNS Swarm identity-service:8081
       |
       +--> PostgreSQL Identity (réseau interne Identity)

Financial Coach — PAS ENCORE DÉPLOYÉ
       |--> PostgreSQL Coach (service de test démarré séparément)
       |--> JWKS public d'Identity sur le réseau "gateway"
       |--> Transaction Service (pas encore déployé/identifié)
       +--> Fournisseur SCA (à confirmer/configurer)
```

Le navigateur ne peut pas résoudre le nom Swarm `identity-service`. Les services du réseau overlay, comme le Gateway et plus tard le Coach, peuvent le résoudre. Le point d'entrée prévu pour un frontend est le Gateway, pas le port interne d'Identity.

## 2. Rôle de chaque microservice

### Identity Service

Responsabilités :

- inscrire des utilisateurs et authentifier leur email/mot de passe;
- conserver les comptes et les données KYC dans PostgreSQL et le stockage configuré;
- émettre des JWT signés en RSA/RS256;
- donner accès au profil de l'utilisateur authentifié;
- publier une clé publique de vérification via JWKS;
- gérer le cycle de vie KYC et les autorisations administratives.

Routes principales :

| Méthode | Route Identity | Accès |
|---|---|---|
| `POST` | `/users/register` | Public |
| `POST` | `/users/login` | Public |
| `GET` | `/users/me` | JWT requis |
| `GET` | `/.well-known/jwks.json` | Public |
| `GET` | `/actuator/health/readiness` | Public |
| `GET` | `/actuator/health/liveness` | Public |
| `GET` | `/v3/api-docs` | Public dans la configuration actuelle |
| `GET` | `/swagger-ui/index.html` | Public dans la configuration actuelle |

Contrat des JWT :

- algorithme : `RS256`;
- `iss` : `identity-service`;
- `aud` : `financial-coach-service`;
- claims applicatifs : `sub` (email, conservé pour compatibilité interne), `userId` (UUID), `role`, `status`;
- claims de temps : `iat` et `exp`;
- le JWKS expose uniquement les paramètres publics; il ne doit jamais exposer la clé privée.

Les services consommateurs récupèrent la clé publique depuis `http://identity-service:8081/.well-known/jwks.json`. Ils ne reçoivent pas le secret de clé privée.

### API Gateway

Responsabilités :

- point d'entrée réseau pour le frontend et les clients;
- acheminer les requêtes vers les microservices;
- appliquer les contrôles transversaux prévus par son implémentation (par exemple validation JWT, limites de débit et journalisation);
- joindre Identity via `http://identity-service:8081` et le JWKS configuré.

État constaté :

- stack `gateway` déployée;
- service Swarm `gateway_api-gateway` en mode `global`, une tâche sur l'unique nœud;
- image locale `neobank/api-gateway:local` construite sur la machine;
- port hôte `8080` publié;
- `gateway_redis` à `1/1`;
- `/healthz` a été utilisé comme contrôle HTTP du Gateway.

Les chemins d'inscription/connexion exposés par le Gateway n'ont pas encore été confirmés dans cette session. Il ne faut donc pas supposer que le Gateway relaie automatiquement `/users/register` et `/users/login` avec ces mêmes chemins. Voir les vérifications de la section 7.

### Financial Coach

Responsabilités attendues d'après le dépôt Financial Coach :

- fournir les fonctionnalités de coaching budgétaire et d'analyse financière;
- utiliser PostgreSQL pour ses données;
- utiliser un fournisseur d'IA configuré (Groq dans la configuration discutée);
- valider les JWT Identity avec le JWKS, l'issuer et l'audience convenus;
- appeler le Transaction Service et, selon la configuration, le fournisseur SCA.

État constaté :

- dépôt `financial-ai-bank` cloné sous `C:\Users\lenovo\financial-ai-bank`;
- image `financial-coach-service:local` construite;
- overlay externe `platform-backend` créé;
- service PostgreSQL de test `financial-coach-db` démarré à `1/1` avec un volume local;
- une clé Groq a été obtenue par l'utilisateur;
- le service applicatif Financial Coach n'a **pas** été déployé.

Blocages : l'adresse/port du Transaction Service et le fournisseur SCA (URL et credential, ou décision de désactivation et modification de configuration pour un environnement de test) ne sont pas encore confirmés. Le secret `financial_coach_runtime_config` avait été créé alors que ses valeurs finales n'étaient pas validées; ne pas le considérer comme prêt. Les secrets Swarm sont immuables : établir la configuration finale, puis créer un nouveau secret versionné ou remplacer celui-ci après avoir vérifié qu'aucun service ne l'utilise.

## 3. Pourquoi les étapes de déploiement ont été faites

| Étape | Pourquoi | Résultat observé |
|---|---|---|
| Garder `sub` comme email et `userId` comme UUID | Le code Identity utilise `sub` pour retrouver le compte; changer sa signification casserait l'authentification interne | Contrat interne préservé; `userId` reste l'identifiant UUID |
| Aligner issuer/audience et valider `aud` | Le consommateur doit rejeter les JWT émis pour un autre service | Valeurs convenues : `identity-service` et `financial-coach-service`; validation de l'audience ajoutée et scénarios de tests correspondants |
| Utiliser des clés RSA persistantes en profil `prod` | Une clé éphémère après redémarrage invaliderait des JWT encore valides | Secrets Swarm de clé publique et privée créés pour Identity; JWKS public vérifié |
| Ne pas donner la clé privée au Gateway/Coach | Les consommateurs vérifient les signatures mais n'ont pas à pouvoir signer des jetons | Ils utilisent l'URL JWKS publique |
| Créer l'overlay `gateway` et ajouter un alias DNS | Les appels inter-services ne doivent pas dépendre de l'adresse IP d'un conteneur | Depuis Gateway, `identity-service:8081` répond |
| Garder le port Identity non publié dans le stack Swarm | Seul le Gateway doit être le point d'entrée réseau de l'architecture Swarm | Port `8081` interne, non publié par le service Swarm |
| Adapter le répertoire temporaire au démarrage | Tomcat ne démarrait pas car `/tmp/app` était en lecture seule ou non inscriptible | La tâche Swarm stable a été observée avec `-Djava.io.tmpdir=/dev/shm`; le readiness est revenu à `UP` |
| Construire l'image du Gateway sur le PC Docker | Swarm ne pouvait pas télécharger le tag privé/local `neobank/api-gateway:local` | Image construite localement, puis tâche recréée par `docker service update --force`; Gateway à `1/1` |
| Construire un Compose local isolé pour Swagger | L'ancienne image locale occupait déjà `8081` et renvoyait des réponses d'une version antérieure | `docker-compose.browser.yml` utilise `18081` pour Identity et `15432` pour PostgreSQL, avec conteneurs dédiés |

### Dépannage du répertoire temporaire

Identity Swarm a d'abord échoué avec une erreur Tomcat de création de `/tmp/app`. Un `docker service update` temporaire a ajouté un tmpfs mais le montage n'a pas persisté comme attendu après redéploiement. La configuration active observée sur la tâche fonctionnelle utilisait `/dev/shm`.

Le Compose local navigateur a eu le même symptôme : le tmpfs monté sur `/tmp/app` masquait le dossier créé et appartenant à l'utilisateur non-root de l'image. Le fichier `docker-compose.browser.yml` de ce dépôt a été corrigé pour retirer `read_only` et le tmpfs, uniquement dans cette configuration de développement. L'utilisateur a ensuite indiqué que Swagger fonctionnait.

**Attention :** le `docker-stack.yml` versionné de ce dépôt Identity contient toujours `-Djava.io.tmpdir=/tmp/app` et un tmpfs sur `/tmp/app`. Il faut rendre le correctif Swarm durable et validé dans le fichier effectivement déployé avant un futur redéploiement; les modifications manuelles via `docker service update` peuvent être remplacées par `docker stack deploy`.

## 4. Accès depuis un navigateur

### Swagger de la copie locale de test Identity

La configuration `docker-compose.browser.yml` est prévue pour lancer une copie locale distincte du Swarm :

- Identity navigateur : `http://127.0.0.1:18081`;
- PostgreSQL navigateur : port `15432` sur la machine, port `5432` dans le réseau Compose;
- Swagger UI : <http://127.0.0.1:18081/swagger-ui/index.html>;
- OpenAPI : <http://127.0.0.1:18081/v3/api-docs>;
- readiness : <http://127.0.0.1:18081/actuator/health/readiness>;
- liveness : <http://127.0.0.1:18081/actuator/health/liveness>;
- JWKS : <http://127.0.0.1:18081/.well-known/jwks.json>.

Dans le clone sur le PC Windows, les commandes ont été lancées depuis `C:\Users\lenovo\bank`. Commandes de gestion :

```powershell
$env:LOCAL_POSTGRES_PASSWORD = Read-Host "Mot de passe temporaire local"
docker compose -p identity-browser -f .\docker-compose.browser.yml up --build --detach
docker compose -p identity-browser -f .\docker-compose.browser.yml ps
docker compose -p identity-browser -f .\docker-compose.browser.yml logs --tail 80 identity-service
```

Le navigateur doit être ouvert sur la même machine que Docker Desktop. Cette copie utilise une base et potentiellement des clés JWT locales éphémères; ses jetons ne doivent pas être attendus comme valides dans le Swarm. Utiliser uniquement des comptes de test.

Pour arrêter seulement cette copie, sans effacer la base :

```powershell
docker compose -p identity-browser -f .\docker-compose.browser.yml down
```

Ne pas ajouter `--volumes` si les données locales doivent être conservées.

### API Gateway

Le Gateway est disponible sur <http://localhost:8080>. Son endpoint de santé testé est :

```text
http://localhost:8080/healthz
```

Il s'agit d'un endpoint technique et non d'une interface frontend. Le Gateway Swarm a été configuré pour joindre Identity par son DNS interne; le navigateur, lui, ne doit pas appeler `http://identity-service:8081`, car ce nom n'existe que sur le réseau Swarm. Pour un frontend, il faut confirmer les routes Gateway et faire appeler ces routes par le navigateur.

Un Swagger Identity n'est pas automatiquement fourni par le Gateway. L'accès direct au Swagger est actuellement réalisé via la copie Compose locale sur le port `18081`. Ne publiez pas le port interne Identity sur un réseau externe uniquement pour contourner cette séparation.

## 5. État de l'environnement Docker

Dernier état communiqué par l'utilisateur (un seul nœud `docker-desktop`) :

| Ressource | État observé |
|---|---|
| `identity_identity-service` | `1/1` |
| `identity_postgres-db` | `1/1` |
| `gateway_api-gateway` | `1/1`, mode `global`, port hôte `8080` |
| `gateway_redis` | `1/1` |
| `financial-coach-db` | `1/1`, lancé séparément |
| `financial-coach` application | Pas déployée |
| Identity readiness via Gateway | `{"status":"UP"}` |
| JWKS via Gateway | Réponse JSON avec clé publique RSA/RS256 |
| Swagger local de test | L'utilisateur a confirmé que cela fonctionne après correction du Compose navigateur |

La sortie `docker service ps` contient souvent les tâches précédentes arrêtées/échouées. Pour le diagnostic, regarder la tâche la plus récente, son erreur éventuelle et le nombre actuel de répliques, pas seulement la présence d'anciennes tâches historiques.

## 6. Tests Identity à effectuer

### 6.1 Santé, OpenAPI et JWKS dans le navigateur

Sur la machine qui exécute la copie locale Compose, ouvrir les URLs de la section 4. Résultats attendus :

- readiness/liveness : HTTP `200`, JSON `{"status":"UP"}`;
- JWKS : JSON avec une clé publique RSA (`kty`, `kid`, `n`, `e`, `alg=RS256`);
- aucun champ privé de clé RSA (`d`, `p`, `q`, `dp`, `dq`, `qi`, `oth`);
- Swagger UI chargée et routes Identity décrites.

PowerShell :

```powershell
curl.exe -i http://127.0.0.1:18081/actuator/health/readiness
curl.exe -i http://127.0.0.1:18081/.well-known/jwks.json
curl.exe -i http://127.0.0.1:18081/v3/api-docs
```

### 6.2 Inscription puis connexion

Dans PowerShell, créer un compte de test unique. Utiliser un mot de passe temporaire, jamais un vrai mot de passe :

```powershell
$baseUrl = "http://127.0.0.1:18081"
$email = "identity-test-$([guid]::NewGuid().ToString('N'))@example.test"
$password = Read-Host "Mot de passe temporaire du compte de test"

$registration = @{
    email = $email
    password = $password
    firstName = "Test"
    lastName = "Identity"
    dateOfBirth = "1995-04-12"
    address = "Adresse de test"
    phoneNumber = "+33123456789"
} | ConvertTo-Json

Invoke-RestMethod -Method Post `
  -Uri "$baseUrl/users/register" `
  -ContentType "application/json" `
  -Body $registration
```

Puis se connecter :

```powershell
$login = @{ email = $email; password = $password } | ConvertTo-Json
$auth = Invoke-RestMethod -Method Post `
  -Uri "$baseUrl/users/login" `
  -ContentType "application/json" `
  -Body $login

$token = $auth.accessToken
if ([string]::IsNullOrWhiteSpace($token)) {
    throw "La réponse de connexion ne contient pas accessToken."
}
"Connexion réussie; JWT reçu. Ne pas l'afficher ni le partager."
```

Résultats attendus : l'inscription réussit sans renvoyer le mot de passe en clair; la connexion réussit avec les bons identifiants. Le même email doit être refusé à l'inscription suivante. Un mauvais mot de passe doit produire un refus d'authentification.

### 6.3 Profil avec et sans authentification

```powershell
$headers = @{ Authorization = ("Bear" + "er " + $token) }
Invoke-RestMethod -Method Get -Uri "$baseUrl/users/me" -Headers $headers
```

Résultats attendus :

- avec un JWT de cette même instance locale : réponse de profil;
- sans Authorization : HTTP `401`;
- avec JWT falsifié/expiré ou de mauvais issuer/audience : rejet;
- un rôle soumis par le client lors de l'inscription ne doit pas permettre de devenir administrateur.

Ne pas passer un jeton de l'Identity Compose au Gateway Swarm : la copie locale peut générer ses propres clés; le Gateway Swarm valide avec les clés publiques de l'Identity Swarm.

### 6.4 Vérification non destructive de l'accès anonyme

```powershell
$status = curl.exe -sS -o NUL -w "%{http_code}" "$baseUrl/users/me"
if ($status -ne "401") {
    throw "Statut attendu 401, reçu $status."
}
"Accès anonyme correctement refusé."
```

### 6.5 Test du chemin réseau Swarm Gateway → Identity

Le test de disponibilité a déjà réussi depuis le conteneur Gateway :

```powershell
docker ps --filter "name=gateway_api-gateway" --format "table {{.ID}}`t{{.Names}}"
docker exec <ID-ou-nom-gateway> curl --fail http://identity-service:8081/actuator/health/readiness
docker exec <ID-ou-nom-gateway> curl --fail http://identity-service:8081/.well-known/jwks.json
```

Remplacer le placeholder par le nom/ID actuel du Gateway. Résultats attendus : readiness `UP` et JWKS public. Ces tests vérifient le réseau interne, pas le routage des routes d'inscription/login depuis le navigateur.

### 6.6 Tests automatisés Maven

Depuis le dépôt Identity, avec Java 21 et une base de test accessible :

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

Tests ciblés :

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress -Dtest=JwtKeyProviderTest test
.\mvnw.cmd --batch-mode --no-transfer-progress -Dtest=SecurityAuthorizationTest test
```

La validation précédente indique que le build Maven du JAR et les 7 tests `JwtKeyProviderTest` ont réussi. `clean verify` n'a pas abouti à une validation complète parce que PostgreSQL était inaccessible sur `localhost:5432` au moment de ce test. Il faut relancer la suite sur une base configurée et vérifier le rapport final avant d'affirmer que tous les tests passent.

Les cas couverts par les tests ajoutés incluent claims JWT, UUID `userId`, signature RS256 vérifiable via JWKS, absence de clé privée dans le JWKS, issuer/audience, routes protégées/publiques, profil production sans clé éphémère et validation d'un JWT non expiré après redémarrage avec les mêmes clés persistantes.

## 7. Appeler Identity à travers le Gateway

Le navigateur doit appeler `http://localhost:8080`, mais il faut connaître les chemins réellement configurés par le dépôt Gateway. Sur le PC Windows, depuis `C:\Users\lenovo\api-gateway`, rechercher les routes et filtres :

```powershell
Get-ChildItem .\src -Recurse -File -Include *.java,*.yml,*.yaml,*.properties |
  Select-String -Pattern 'users/register|users/login|IDENTITY_SERVICE_URL|identity-service|RouteLocator'
```

Interprétation :

- si le Gateway définit une route de proxy vers Identity, utiliser exactement son préfixe et son chemin;
- si aucune route n'existe, demander/implémenter explicitement le routage de `POST /users/register` et `POST /users/login`;
- ne pas appeler une route protégée sans JWT;
- une réponse `{"code":"UNAUTHORIZED","message":"Token absent, invalide ou expiré"}` indique qu'un filtre Gateway a rejeté cette requête; elle ne prouve pas à elle seule que la route d'inscription ou de connexion est incorrecte;
- les routes d'inscription/login doivent être publiques; les routes comme `/users/me` nécessitent un JWT;
- aucun JWT obtenu depuis la copie locale port `18081` ne doit être présumé valide avec l'Identity Swarm.

Une fois le chemin Gateway confirmé, faire les appels depuis PowerShell ou le frontend sur `http://localhost:8080/<route-confirmée>`. En environnement partagé/production, utiliser HTTPS, définir CORS pour l'origine frontend exacte, et choisir une stratégie sûre de stockage de session/token avec l'équipe frontend.

## 8. Étapes de conteneurisation locale — travail actuel

**Objectif de cette phase :** construire les images Identity et API Gateway, les faire communiquer sur un réseau Docker privé, puis tester le parcours à travers le Gateway. Il ne s'agit pas encore de publier les images sur Docker Hub/ECR ni de déployer sur AWS.

### Étape 1 — confirmer le contrat entre les services

Dans le dépôt API Gateway, identifier les routes de proxy pour `POST /users/register`, `POST /users/login` et les routes protégées. Confirmer aussi le nom exact de la variable ou propriété qui configure l'URL Identity. Dans les conteneurs, l'adresse doit utiliser le nom du service Docker, par exemple `http://identity-service:8081`, jamais `localhost` : dans un conteneur, `localhost` désigne ce conteneur lui-même.

Vérifier que le Gateway peut accéder à `http://identity-service:8081/.well-known/jwks.json` s'il valide les JWT. Il utilise la clé publique du JWKS; la clé privée reste uniquement dans Identity. Utiliser les routes et propriétés réellement présentes dans le dépôt Gateway, sans inventer de préfixe ou de variable.

### Étape 2 — construire les deux images en local

Identity possède déjà un `Dockerfile` multi-stage. Depuis la racine de ce dépôt, construire son image :

```powershell
docker build -t identity-service:dev .
```

Dans le dépôt API Gateway, vérifier qu'un `Dockerfile` existe et qu'il expose le port prévu (le Gateway actuel écoute sur `8080`). S'il n'existe pas, ajouter et tester ce Dockerfile dans ce dépôt avant de poursuivre. Construire ensuite l'image depuis sa racine :

```powershell
docker build -t api-gateway:dev .
```

Ne pas mettre de mot de passe, token, clé JWT ou fichier `.env` contenant des secrets dans les images. Pour cette phase locale, fournir les valeurs de test par configuration locale ignorée par Git; les secrets de production seront traités séparément.

### Étape 3 — réunir les conteneurs dans un même projet Compose

Créer un fichier Compose d'intégration, idéalement dans un dossier de travail qui peut référencer les deux dépôts. Il doit contenir les services `postgres-db`, `identity-service` et `api-gateway`. Réutiliser le service PostgreSQL et les volumes déjà décrits dans `docker-compose.yml`; adapter son mot de passe local à la configuration de développement existante.

Configurer les éléments suivants dans ce Compose :

- Identity se connecte à PostgreSQL avec `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres-db:5432/identity_db`.
- Le Gateway utilise sa vraie propriété de configuration avec la valeur `http://identity-service:8081`; son URL JWKS, si nécessaire, est `http://identity-service:8081/.well-known/jwks.json`.
- Les trois services partagent le réseau privé par défaut du même projet Compose. Les noms `postgres-db` et `identity-service` sont leurs noms DNS internes.
- Seul le Gateway publie un port vers l'hôte (par exemple `8080:8080`). Ne pas publier PostgreSQL ni le port Identity pour tester le parcours Gateway.
- Le Gateway démarre après Identity et Identity après PostgreSQL; ajouter des healthchecks ou des mécanismes de retry afin que le démarrage des dépendances ne dépende pas uniquement de l'ordre de lancement.
- Le Dockerfile Gateway est construit depuis son dépôt avec un `build.context` adapté. Les chemins de contexte doivent être relatifs au fichier Compose et pointer vers les deux dépôts clonés.

Ne pas lancer les deux fichiers Compose existants séparément en supposant qu'ils partagent automatiquement leur réseau : chaque projet Compose crée normalement son propre réseau. L'intégration doit les réunir dans un même fichier/projet, ou configurer explicitement un réseau partagé.

### Étape 4 — démarrer et diagnostiquer

Depuis le dossier contenant le Compose d'intégration :

```powershell
docker compose -f .\docker-compose.integration.yml config
docker compose -f .\docker-compose.integration.yml up --build -d
docker compose -f .\docker-compose.integration.yml ps
docker compose -f .\docker-compose.integration.yml logs --tail 100 identity-service
docker compose -f .\docker-compose.integration.yml logs --tail 100 api-gateway
```

La commande `config` doit réussir avant le démarrage. Vérifier que les services sont démarrés, puis que le Gateway atteint les endpoints de santé Identity et le JWKS en utilisant le DNS `identity-service`. En cas d'échec, vérifier d'abord les logs, le port d'écoute Identity (`8081` dans le conteneur), le nom DNS et la propriété de configuration Gateway.

### Étape 5 — valider de bout en bout

Depuis PowerShell ou le frontend, appeler le Gateway sur `http://localhost:8080` avec les chemins réellement confirmés à l'étape 1 :

1. Vérifier `/healthz` du Gateway et la readiness Identity via le Gateway si une route de santé est configurée.
2. Créer un compte de test via la route Gateway de register, puis se connecter via la route Gateway de login.
3. Utiliser le JWT retourné pour appeler une route protégée, par exemple le profil courant si cette route est relayée.
4. Vérifier que le Gateway valide le JWT avec le JWKS d'Identity et que les appels échouent correctement si le jeton est absent ou invalide.

Le succès de ces tests valide la communication conteneur-à-conteneur et le routage configuré; il ne signifie pas que les images sont publiées ou que le déploiement AWS est prêt. Arrêter l'environnement sans supprimer les volumes avec :

```powershell
docker compose -f .\docker-compose.integration.yml down
```

### Étape 6 — préparer ensuite la publication

Après validation locale seulement, choisir un registre (Docker Hub ou Amazon ECR), publier les images avec des tags versionnés/immuables et documenter les valeurs de configuration attendues. La publication et le déploiement AWS sont une phase ultérieure, hors du périmètre actuel.

## 9. Ce qu'il reste à faire

### Priorité 1 — rendre Identity Swarm reproductible

- Corriger dans le fichier de stack Identity suivi par Git le problème temporaire `/tmp/app`; la tâche active a nécessité une configuration `/dev/shm`, mais le `docker-stack.yml` suivi n'est pas encore aligné.
- Redéployer avec le fichier corrigé et vérifier `docker stack services identity`, la commande Java et les montages.
- Vérifier que les clés privées restent exclusivement dans les secrets Identity et que le JWKS ne contient que la clé publique.
- Relancer les tests Maven complets avec PostgreSQL disponible.

### Priorité 2 — confirmer le routage Gateway

- Chercher dans le dépôt Gateway les routes exactes de register/login et le filtre d'authentification.
- Tester inscription et login au travers du Gateway, puis `/users/me` avec le JWT de l'Identity Swarm.
- Vérifier si le Gateway publie ses routes vers le navigateur avec le bon CORS; `/healthz` seul ne constitue pas un frontend.
- Tester les appels depuis une page frontend uniquement après confirmation des URL.

### Priorité 3 — terminer Financial Coach

- Obtenir de l'équipe l'adresse interne et le port du Transaction Service sur `platform-backend`.
- Identifier le fournisseur SCA retenu et obtenir son URL et credential par canal sécurisé. Pour un essai sans SCA, faire confirmer la désactivation et adapter/tester le profil/configuration; ne mettre ni URL ni token fictifs.
- Mettre à jour le secret `financial_coach_runtime_config` avec le mot de passe de la base Coach, la vraie clé Groq et les valeurs SCA définitives si activé. Créer un nouveau nom de secret/version pour éviter une suppression risquée d'un secret attaché.
- Confirmer que le service PostgreSQL Coach est durable, que le mot de passe correspond au secret monté, que ses migrations s'exécutent et que les sauvegardes sont prévues.
- Configurer `FINANCIAL_COACH_IMAGE`, `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `JWKS_URI`, `JWT_ISSUER`, `JWT_AUDIENCE`, `SERVICES_TRANSACTION_SERVICE_BASE_URL`, `SCA_PROVIDER_BASE_URL` et `GATEWAY_OVERLAY_NETWORK`.
- Déployer seulement quand toutes les dépendances sont réelles, puis vérifier readiness, logs, accessibilité du JWKS, validation JWT, accès base et appels aval.

### Priorité 4 — production réelle

- remplacer les tags `:local` par des images immuables dans un registre accessible aux nœuds Swarm;
- ne pas assimiler Docker Desktop mono-nœud à un Swarm résilient;
- établir stockage et sauvegardes restaurables pour PostgreSQL et KYC;
- utiliser HTTPS/TLS au point d'entrée, CORS strict, gestion sécurisée des secrets et surveillance;
- renouveler tout secret/token de jointure exposé et ne jamais envoyer de secret, JWT ou clé privée dans les tickets ou conversations.

## 10. Commandes de suivi rapide

```powershell
docker stack ls
docker stack services identity
docker stack services gateway
docker service ls
docker service ps --no-trunc identity_identity-service
docker service ps --no-trunc gateway_api-gateway
docker service logs --tail 80 identity_identity-service
docker service logs --tail 80 gateway_api-gateway
```

Pour la copie locale Swagger :

```powershell
docker compose -p identity-browser -f .\docker-compose.browser.yml ps
docker compose -p identity-browser -f .\docker-compose.browser.yml logs --tail 80 identity-service
```

Ne partagez pas les contenus de fichiers de secrets, variables d'environnement sensibles, JWT, mots de passe, clés RSA ou tokens de fournisseurs lorsque vous demandez de l'aide.

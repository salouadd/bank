# Identity Service — guide de déploiement, résultats et tests

Ce guide reprend le travail effectué pour faire communiquer Identity Service avec l'API Gateway dans Docker Swarm, explique les décisions prises et donne des procédures de vérification, dont l'accès depuis un navigateur.

> **Périmètre actuel :** l'intégration Gateway → Identity a été testée sur Docker Desktop. Financial Coach n'est pas encore déployé : sa base existe, mais ses dépendances SCA et Transaction Service ne sont pas encore configurées. Les commandes de production ci-dessous doivent être adaptées et validées par l'équipe responsable avant toute exposition réelle.

## 1. Architecture et contrat d'intégration

```text
Navigateur / client
       |
       v
API Gateway : gateway_api-gateway, port hôte 8080
       |
       | réseau overlay externe "gateway"
       v
Identity Service : DNS identity-service, port interne 8081
       |
       | réseau overlay privé identity_identity_backend
       v
PostgreSQL Identity : postgres-db:5432
```

Le Financial Coach devra rejoindre le réseau `gateway`, obtenir les clés de vérification via le JWKS public et appliquer exactement les valeurs JWT ci-dessous.

| Paramètre | Valeur par défaut / valeur utilisée |
|---|---|
| Port interne Identity | `8081` |
| DNS Swarm sur l'overlay | `identity-service` |
| Issuer JWT (`iss`) | `identity-service` |
| Audience JWT (`aud`) | `financial-coach-service` |
| Algorithme | `RS256` |
| URL JWKS interne | `http://identity-service:8081/.well-known/jwks.json` |

Claims émis : `sub` (e-mail, conservé pour la compatibilité interne), `userId` (UUID), `role`, `status`, `iss`, `aud`, `iat` et `exp`. Le Gateway et les consommateurs JWT ne reçoivent que la clé publique. La clé privée reste un secret du service Identity.

## 2. Pourquoi ces étapes ont été faites et résultats constatés

| Étape | Pourquoi | Résultat observé |
|---|---|---|
| Auditer les routes, les usages de `sub` et la configuration JWT | Conserver la compatibilité avec les filtres internes tout en alignant le contrat du Coach | `sub` reste l'e-mail; `userId` reste l'UUID; l'issuer et l'audience par défaut correspondent au contrat indiqué |
| Imposer la validation de l'audience JWT | Un JWT signé n'est pas destiné automatiquement à tous les services | La validation compare l'audience configurée; des tests couvrent les claims, l'audience, RS256 et le JWKS |
| Charger les clés RSA persistantes depuis les secrets Swarm en profil `prod` | Empêcher l'émission de JWT avec une clé éphémère au redémarrage et ne pas distribuer la clé privée | Secrets Swarm présents : `identity_jwt_private_key` et `identity_jwt_public_key`; le Coach/Gateway utilisent le JWKS |
| Créer l'overlay `gateway` et joindre Identity à cet overlay | Fournir un DNS de service stable sans publier le port Identity à l'hôte | Depuis le conteneur Gateway, `identity-service:8081` a résolu et répondu |
| Corriger le stockage temporaire pour le système de fichiers racine en lecture seule | Tomcat devait créer un répertoire temporaire au démarrage | Après plusieurs tentatives avec `/tmp/app`, le service a démarré avec `java.io.tmpdir=/dev/shm` dans le stack utilisé sur la machine Windows |
| Construire puis forcer le renouvellement du Gateway | Swarm ne pouvait pas récupérer l'image locale avant sa construction | `neobank/api-gateway:local` construite localement; après `docker service update --force`, Gateway est `1/1` |
| Vérifier readiness et JWKS depuis le Gateway | Confirmer le vrai chemin réseau inter-service, pas seulement l'état local du conteneur | Readiness a répondu `{"status":"UP"}`; JWKS a répondu avec une clé publique RSA/RS256 |

### État Swarm relevé pendant le dépannage

Les services suivants étaient actifs (`1/1`, ou `1/1` sur le nœud unique pour le mode global) :

- `identity_identity-service` — image locale `identity-service:local`;
- `identity_postgres-db` — PostgreSQL Identity;
- `gateway_api-gateway` — image locale `neobank/api-gateway:local`, port hôte `8080`;
- `gateway_redis`;
- `financial-coach-db` — PostgreSQL de test pour Coach, démarrée séparément.

La stack `financial-coach` n'a **pas** été déployée. Une clé Groq a été obtenue, mais l'URL/token SCA et le Transaction Service restent à fournir/configurer. Le secret `financial_coach_runtime_config` avait été créé avant que les valeurs finales soient prêtes; ne présumez pas qu'il contient la configuration finale. Les secrets Swarm sont immuables : recréez-le avec une procédure de rotation sûre lorsque tous les champs sont validés.

### Point important à corriger durablement

Le `docker-stack.yml` présent dans ce dépôt configure encore `/tmp/app` comme `java.io.tmpdir` et monte un tmpfs sur `/tmp/app`. La machine Windows a démarré après un ajustement local du stack de déploiement vers `/dev/shm`, mais cet ajustement **n'est pas présent dans le fichier suivi de ce dépôt** à la rédaction de ce guide. Un futur `docker stack deploy` depuis ce dépôt peut donc réintroduire l'échec Tomcat avec une racine en lecture seule.

Avant le prochain déploiement, faites une correction durable, testez-la dans le même environnement Swarm, puis documentez-la dans le fichier de stack réellement versionné. Vérifiez la commande active et les montages après chaque déploiement :

```powershell
docker service inspect identity_identity-service --format '{{json .Spec.TaskTemplate.ContainerSpec.Args}}'
docker service inspect identity_identity-service --format '{{json .Spec.TaskTemplate.ContainerSpec.Mounts}}'
```

La cible temporaire choisie doit être accessible en écriture pour l'utilisateur du conteneur et compatible avec la configuration effective. Ne supposez pas qu'un `docker service update` manuel est durable : un déploiement de stack peut le remplacer.

## 3. Accès depuis un navigateur

### Développement local avec Docker Compose

Le `docker-compose.yml` de ce dépôt publie Identity uniquement sur l'interface locale (`127.0.0.1:8081`). Si les ports/noms sont déjà utilisés ou si l'utilisateur non-root ne peut pas écrire dans le tmpfs `/tmp/app`, utilisez `docker-compose.browser.yml` : ce fichier est réservé aux essais locaux, utilise les ports `18081`/`15432` et laisse le répertoire temporaire préparé dans l'image accessible en écriture. L'application reste non-root, mais cette configuration locale n'a pas le système de fichiers racine en lecture seule. Depuis la racine du dépôt, dans PowerShell :

```powershell
$env:LOCAL_POSTGRES_PASSWORD = Read-Host "Mot de passe PostgreSQL local"
docker compose up --build --detach
docker compose ps
```

Quand le service est démarré, ouvrir dans le navigateur :

- Swagger UI : <http://127.0.0.1:8081/swagger-ui/index.html>
- Documentation OpenAPI JSON : <http://127.0.0.1:8081/v3/api-docs>
- Readiness : <http://127.0.0.1:8081/actuator/health/readiness>
- Liveness : <http://127.0.0.1:8081/actuator/health/liveness>
- JWKS public : <http://127.0.0.1:8081/.well-known/jwks.json>

Swagger permet d'appeler les routes `POST /users/register`, `POST /users/login` et, après connexion, `GET /users/me`. Dans Swagger, utiliser **Authorize** et le schéma d'authentification JWT si l'interface le demande. N'utilisez que des comptes de test; ne saisissez pas de vrai mot de passe bancaire.

Arrêter l'environnement local :

```powershell
docker compose down
```

`docker compose down --volumes` supprime aussi les données PostgreSQL et KYC locales; ne l'exécutez que si leur suppression est voulue.

### Swarm courant

Le stack Identity **ne publie pas le port `8081` sur l'hôte**. Cela évite d'exposer Identity directement; l'accès inter-service passe par le réseau privé/overlay et l'API Gateway. Par conséquent, les URLs `127.0.0.1:8081` ci-dessus ne sont pas nécessairement disponibles dans le navigateur pendant que seul Swarm est utilisé.

Le test effectué dans cette configuration est depuis le conteneur Gateway :

```powershell
docker ps --filter "name=gateway_api-gateway" --format "table {{.ID}}`t{{.Names}}"
$gatewayContainer = "<ID-ou-nom-affiché>"
docker exec $gatewayContainer curl --fail http://identity-service:8081/actuator/health/readiness
docker exec $gatewayContainer curl --fail http://identity-service:8081/.well-known/jwks.json
```

Remplacez le placeholder par l'ID/nom réellement listé. Ne publiez pas le port interne d'Identity sur Internet pour rendre Swagger accessible. Pour consulter directement Swagger dans un navigateur, utilisez l'environnement local Compose, ou faites mettre en place une route d'administration protégée selon la politique de l'équipe.

## 4. Tests de vérification manuels pour Identity

Les commandes suivantes ciblent le mode local Compose (`http://127.0.0.1:8081`). Elles ne nécessitent aucune vraie clé RSA de production. Utilisez un email de test unique et un mot de passe de test conforme aux validations de l'application.

### 4.1 Santé du service

Dans le navigateur, ouvrir les URLs de santé de la section précédente. Ou dans PowerShell :

```powershell
Invoke-RestMethod http://127.0.0.1:8081/actuator/health/liveness
Invoke-RestMethod http://127.0.0.1:8081/actuator/health/readiness
```

Résultat attendu : statut `UP`. La readiness vérifie aussi la disponibilité de PostgreSQL.

### 4.2 Inscription

```powershell
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
  -Uri "http://127.0.0.1:8081/users/register" `
  -ContentType "application/json" `
  -Body $registration
```

Résultat attendu : une réponse de succès sans mot de passe en clair. Un second essai avec le même e-mail doit être refusé (conflit/validation selon le contrat d'erreur).

### 4.3 Connexion et claims du JWT

```powershell
$login = @{ email = $email; password = $password } | ConvertTo-Json
$auth = Invoke-RestMethod -Method Post `
  -Uri "http://127.0.0.1:8081/users/login" `
  -ContentType "application/json" `
  -Body $login

$token = $auth.accessToken
if ([string]::IsNullOrWhiteSpace($token)) { throw "La réponse ne contient pas accessToken." }
"JWT reçu (ne pas publier): longueur $($token.Length)"
```

Résultat attendu : connexion valide et JWT signé. Une connexion avec un mauvais mot de passe doit échouer en `401`. Un compte suspendu ne doit pas obtenir de jeton utilisable.

Les claims attendus sont `sub`, `userId`, `role`, `status`, `iss`, `aud`, `iat`, `exp`; vérifier que `userId` est un UUID, `iss` vaut `identity-service`, `aud` vaut `financial-coach-service`, et que l'en-tête JWT annonce `RS256` avec un `kid`. Ne collez jamais le JWT dans un site de décodage public ou dans un ticket. L'endpoint JWKS fournit la clé publique pour une vérification locale.

### 4.4 Profil authentifié et accès anonyme

```powershell
$headers = @{ Authorization = ("Bear" + "er " + $token) }
Invoke-RestMethod -Method Get `
  -Uri "http://127.0.0.1:8081/users/me" `
  -Headers $headers
```

Résultat attendu : profil de l'utilisateur connecté, sans informations sensibles non nécessaires. Sans en-tête `Authorization`, la route doit répondre `401` :

```powershell
$status = curl.exe -sS -o NUL -w "%{http_code}" `
  http://127.0.0.1:8081/users/me
if ($status -ne "401") {
    throw "Échec du test : statut attendu 401, reçu $status."
}
"Test anonyme réussi : /users/me répond 401."
```

### 4.5 JWKS et clé publique uniquement

```powershell
$jwks = Invoke-RestMethod http://127.0.0.1:8081/.well-known/jwks.json
$jwks.keys | Format-List
```

Résultat attendu : une ou plusieurs clés contenant les paramètres publics RSA (dont `kty`, `alg`, `kid`, `n`, `e`). Aucun champ privé (`d`, `p`, `q`, `dp`, `dq`, `qi`, `oth`) ne doit apparaître. L'endpoint ne demande pas de JWT.

### 4.6 OpenAPI et erreurs HTTP

- Swagger UI et `/v3/api-docs` doivent être accessibles sans authentification dans la configuration actuelle.
- `/users/register` et `/users/login` sont publics.
- `/users/me` sans JWT : `401`.
- JWT falsifié, expiré, de mauvais issuer ou de mauvaise audience : rejet attendu (`401`).
- Données invalides à l'inscription : rejet de validation (généralement `400`).
- Le rôle utilisateur ne doit pas pouvoir être choisi librement par le client.

## 5. Intégration Swarm Identity ↔ Gateway

Commandes de diagnostic sur le nœud Swarm :

```powershell
docker stack services identity
docker stack services gateway
docker service ps --no-trunc identity_identity-service
docker service ps --no-trunc gateway_api-gateway
docker service logs --tail 80 identity_identity-service
docker service logs --tail 80 gateway_api-gateway
```

Vérifier réseau, commande de démarrage et ports :

```powershell
docker service inspect identity_identity-service --format '{{json .Spec.TaskTemplate.Networks}}'
docker service inspect identity_identity-service --format '{{json .Spec.TaskTemplate.ContainerSpec.Args}}'
docker service inspect identity_identity-service --format '{{json .Endpoint.Spec.Ports}}'
```

Dans le dépannage réalisé, le Gateway était en mode `global`, une réplique sur le seul nœud Docker Desktop, exposé sur le port hôte `8080`. L'Identity était en mode répliqué et sans port publié. La commande de démarrage observée dans la tâche stable utilisait :

```text
-Djava.io.tmpdir=/dev/shm
```

Le test depuis le Gateway a renvoyé :

```text
{"status":"UP"}
```

Le JWKS a répondu avec `kty: RSA` et `alg: RS256`. Les tâches en échec listées par `docker service ps` étaient des tentatives historiques; l'état courant à vérifier est la tâche la plus récente et le nombre de répliques du service.

## 6. Tests automatisés et état de validation connu

Depuis la racine du dépôt Identity, avec Java 21 :

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

Test ciblé des autorisations/routes :

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress -Dtest=SecurityAuthorizationTest test
```

Test ciblé du chargement et du contrat des clés/JWT :

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress -Dtest=JwtKeyProviderTest test
```

L'historique de validation disponible indique que le build Maven du JAR a réussi et que les sept tests de `JwtKeyProviderTest` ont réussi. `clean verify` n'a pas été entièrement validé à ce moment-là : la connexion PostgreSQL à `localhost:5432` était indisponible, empêchant l'exécution des tests de contexte/routes. Relancez la suite dans un environnement avec PostgreSQL fonctionnel avant de considérer la validation complète.

Les scénarios automatisés ajoutés couvrent notamment les claims, la signature RS256 vérifiable via JWKS, l'absence de clé privée dans JWKS, l'audience, les routes publiques/protégées, le profil `prod` et la validation d'un JWT non expiré après redémarrage avec les mêmes clés persistantes.

## 7. Secrets, données et précautions

- Ne jamais committer mot de passe, token, clé RSA privée ou fichier de secrets.
- Ne jamais monter ni transmettre la clé privée JWT au Gateway ou au Coach.
- Conserver les secrets de production dans Docker Swarm Secrets ou un gestionnaire de secrets approuvé; garder les fichiers sources hors du dépôt.
- Le profil `local` peut générer des clés JWT éphémères : les jetons ne sont pas destinés à survivre au redémarrage local. Le profil `prod` exige des clés persistantes.
- Les volumes Swarm nommés sont locaux au nœud; un unique nœud portant le label de données doit être choisi et les sauvegardes/restaurations PostgreSQL et KYC doivent être prévues.
- Docker Swarm a ignoré `security_opt` lors de `docker stack deploy`. Ne supposez pas que cette option Compose est appliquée par Swarm; vérifiez les propriétés réellement actives.
- Les fichiers `docker-stack.yml` doivent pointer vers des images publiées et immuables en production. Les tags locaux `*:local` conviennent uniquement au Swarm local où les images sont présentes.
- Si un token de jointure Swarm ou tout secret est exposé, révoquez/renouvelez-le et remplacez le secret concerné.

## 8. État de Financial Coach au dernier point de suivi

Le dépôt `financial-ai-bank` a été cloné et son image `financial-coach-service:local` construite. Le réseau overlay `platform-backend` a été créé et une base PostgreSQL `financial-coach-db` a démarré avec un volume persistant local au nœud. La clé Groq a été obtenue.

Le Coach n'est pas encore déployé. Restent à obtenir/configurer avant déploiement :

1. l'URL et le token du fournisseur SCA, ou une décision explicite et un changement de configuration pour désactiver SCA dans l'environnement de test;
2. l'URL/nom de service et le port du Transaction Service joignable depuis `platform-backend`;
3. une vérification du secret `financial_coach_runtime_config`, à recréer avec le mot de passe final de la base, la clé IA et le token SCA final;
4. les variables d'environnement propres à l'environnement et le contrôle que le schéma PostgreSQL correspond aux migrations du Coach.

Ne déployez pas le Coach avec une URL fictive ou un token placeholder. Le contrat JWT attendu par le Coach est `iss=identity-service`, `aud=financial-coach-service`, JWKS `http://identity-service:8081/.well-known/jwks.json`.

# Conteneuriser Identity et le API Gateway en local

## Objectif

Cette étape consiste à construire les images Docker d'Identity et du API Gateway, puis à vérifier qu'ils communiquent dans un même réseau Docker. **Il n'est pas question ici de pousser les images sur Docker Hub/ECR ni de déployer sur AWS.**

Le navigateur appelle le Gateway sur `localhost:8080`. À l'intérieur du réseau Docker, le Gateway appelle Identity via son nom de service, `identity-service:8081`. Dans un conteneur, `localhost` désigne le conteneur lui-même et ne doit donc pas être utilisé pour joindre un autre service.

```text
Navigateur --http://localhost:8080--> API Gateway
                                           |
                                           | http://identity-service:8081
                                           v
                                      Identity Service
                                           |
                                           | jdbc:postgresql://postgres-db:5432/identity_db
                                           v
                                        PostgreSQL
```

## 1. Préparer les deux dépôts

Il faut avoir les dépôts Identity et API Gateway clonés sur la même machine, Docker Desktop démarré et Docker Compose disponible. Les exemples ci-dessous supposent que les services peuvent être démarrés depuis un même fichier Compose.

Avant de configurer le Gateway, vérifier dans son code :

- les routes de proxy vers `POST /users/register`, `POST /users/login` et les routes protégées;
- la propriété ou variable d'environnement réellement utilisée pour l'URL de base d'Identity;
- la configuration JWKS si le Gateway valide lui-même les JWT.

Configurer cette URL avec `http://identity-service:8081` et, si nécessaire, le JWKS avec `http://identity-service:8081/.well-known/jwks.json`. Ne pas inventer le nom de la propriété : utiliser celui que le Gateway prend effectivement en charge. Identity conserve la clé privée; le Gateway n'a besoin que de la clé publique publiée par JWKS.

## 2. Construire les images Docker

Identity possède déjà un Dockerfile multi-stage. Depuis la racine du dépôt Identity :

```powershell
docker build -t identity-service:dev .
```

Depuis la racine du dépôt API Gateway, vérifier la présence de son Dockerfile, puis construire l'image. Le Gateway doit écouter sur le port `8080` dans le conteneur :

```powershell
docker build -t api-gateway:dev .
```

Ne pas inclure de mot de passe, token, clé privée ou fichier contenant des secrets dans une image. Pour le développement local, fournir les valeurs de test par variables d'environnement ou fichier local ignoré par Git.

## 3. Créer le Compose d'intégration

Créer `docker-compose.integration.yml` dans un dossier de travail qui peut servir de point de lancement commun. Le fichier doit définir PostgreSQL, Identity et le Gateway dans **un seul projet Compose**. Compose leur crée ainsi un réseau commun et chaque service est joignable par son nom.

Exemple de structure à adapter à la configuration réelle du Gateway :

```yaml
services:
  postgres-db:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: identity_db
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: ${LOCAL_POSTGRES_PASSWORD:?Define LOCAL_POSTGRES_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d identity_db"]
      interval: 5s
      timeout: 5s
      retries: 10
      start_period: 15s

  identity-service:
    image: identity-service:dev
    environment:
      SPRING_PROFILES_ACTIVE: local
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres-db:5432/identity_db
      SPRING_DATASOURCE_USERNAME: postgres
      SPRING_DATASOURCE_PASSWORD: ${LOCAL_POSTGRES_PASSWORD:?Define LOCAL_POSTGRES_PASSWORD}
      IDENTITY_JWT_ISSUER: identity-service
      IDENTITY_JWT_AUDIENCE: financial-coach-service
    depends_on:
      postgres-db:
        condition: service_healthy
    volumes:
      - kyc_data:/app/uploads

  api-gateway:
    image: api-gateway:dev
    # Remplacer par les vraies variables du Gateway, par exemple :
    # environment:
    #   <VARIABLE_URL_IDENTITY>: http://identity-service:8081
    #   <VARIABLE_URL_JWKS>: http://identity-service:8081/.well-known/jwks.json
    ports:
      - "8080:8080"
    depends_on:
      - identity-service

volumes:
  postgres_data:
  kyc_data:
```

Le bloc `environment` du Gateway doit être complété avec les **noms exacts** trouvés à l'étape 1 avant de lancer les tests. Si le Gateway attend une autre audience JWT, aligner `IDENTITY_JWT_AUDIENCE` avec son contrat. Le compose de développement existant expose Identity sur le port hôte `8081`; dans ce Compose d'intégration, il n'est pas nécessaire de publier ce port puisque le Gateway utilise le réseau privé. Ne publier ni PostgreSQL ni Identity pour les tests du parcours via Gateway.

Le chemin du fichier Compose peut être dans un dossier distinct des dépôts. Dans ce cas, les commandes doivent être lancées depuis ce dossier ou préciser le chemin avec `-f`. Les images de l'exemple sont pré-construites et taguées localement; reconstruire chaque image après avoir modifié le code correspondant.

## 4. Démarrer l'environnement et vérifier l'état

Définir un mot de passe réservé à la base locale dans la session PowerShell, sans l'écrire dans le dépôt :

```powershell
$env:LOCAL_POSTGRES_PASSWORD = Read-Host "Mot de passe PostgreSQL local"
```

Depuis le dossier du fichier Compose :

```powershell
docker compose -f .\docker-compose.integration.yml config
docker compose -f .\docker-compose.integration.yml up -d
docker compose -f .\docker-compose.integration.yml ps
docker compose -f .\docker-compose.integration.yml logs --tail 100 postgres-db
docker compose -f .\docker-compose.integration.yml logs --tail 100 identity-service
docker compose -f .\docker-compose.integration.yml logs --tail 100 api-gateway
```

La commande `config` doit réussir avant le démarrage. Attendre que PostgreSQL soit sain et qu'Identity ait terminé son démarrage. Le `depends_on` simple du Gateway contrôle l'ordre de lancement, pas la disponibilité applicative d'Identity : le Gateway doit réessayer les connexions en cas de démarrage plus lent.

Vérifier depuis un conteneur du même réseau que ces adresses répondent :

- `http://identity-service:8081/actuator/health/readiness`
- `http://identity-service:8081/.well-known/jwks.json`

Si l'image du Gateway ne contient pas d'outil HTTP, utiliser un conteneur temporaire de diagnostic attaché au réseau Compose. Ne pas utiliser `localhost:8081` depuis le Gateway.

## 5. Tester le parcours complet par le Gateway

Utiliser `http://localhost:8080` depuis l'hôte et les chemins Gateway confirmés dans son dépôt. Ne pas supposer que les chemins Identity sont automatiquement exposés sans préfixe par le Gateway.

1. Vérifier la santé du Gateway (`/healthz`, si cet endpoint est activé).
2. Créer un compte de test via la route Gateway de register.
3. Se connecter via la route Gateway de login et récupérer le JWT de test.
4. Appeler une route protégée relayée par le Gateway, par exemple le profil courant, avec ce JWT.
5. Vérifier que le JWKS utilisé par le Gateway correspond à l'Identity de ce même Compose et que les requêtes sans jeton ou avec un jeton invalide sont rejetées.

En cas d'erreur, contrôler les logs des deux applications, les routes configurées, le nom de service `identity-service`, le port interne `8081`, la configuration JWKS et les valeurs issuer/audience.

## 6. Arrêter l'environnement

Pour arrêter les conteneurs en conservant les données locales :

```powershell
docker compose -f .\docker-compose.integration.yml down
```

Ne pas ajouter `--volumes` si les données de test PostgreSQL et KYC doivent être conservées. Pour recommencer avec une base vide, supprimer les volumes seulement après avoir confirmé que leur contenu peut être effacé.

## Critères de fin de cette phase

- Les images Identity et Gateway sont construites localement.
- PostgreSQL et Identity démarrent et passent leur contrôle de santé.
- Le Gateway résout `identity-service` et atteint Identity/JWKS sur le réseau Compose.
- Inscription, connexion et au moins un appel protégé sont vérifiés via le Gateway.

Une fois ces points validés, la prochaine phase pourra traiter la publication des images sur Docker Hub ou Amazon ECR. Ce README ne décrit pas cette publication ni un déploiement AWS.

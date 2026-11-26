# Prompt d'intégration Identity → API Gateway

Copiez le prompt ci-dessous et exécutez-le depuis le dépôt du microservice **API Gateway**. Il demande à l'agent de commencer par inspecter le Gateway et d'adapter l'intégration à sa technologie et à ses conventions existantes.

---

## Prompt à exécuter dans le dépôt API Gateway

```text
Tu travailles dans le dépôt du microservice API Gateway. Ta mission est d'intégrer réellement le microservice Identity décrit dans ce prompt au Gateway, en préparant et en testant la communication entre leurs conteneurs Docker locaux. Ne fais aucune publication sur Docker Hub ou ECR et aucun déploiement AWS, Swarm ou cloud.

RÈGLES DE TRAVAIL

1. Commence par inspecter le dépôt courant : framework et version, routes existantes, filtres/middlewares de sécurité, configuration, Dockerfile, Compose, tests et conventions. Réutilise les patterns en place.
2. N'invente ni framework, ni nom de variable d'environnement, ni route publique. Si une valeur ou un choix d'architecture est inconnu, examine le code et la configuration. Si cela reste inconnu, signale le blocage clairement plutôt que de créer une valeur fictive.
3. Implémente les changements nécessaires dans ce dépôt, avec des tests. Ne te contente pas d'un plan ou de documentation.
4. N'expose aucun secret, mot de passe, JWT ou clé privée dans le code, l'image Docker, le dépôt, les logs ou les réponses d'erreur. Ne demande et ne crée aucun vrai secret.
5. Ne modifie pas les contrats existants du Gateway sans nécessité. Évite les changements sans rapport avec cette intégration. À la fin, indique les fichiers modifiés, les tests exécutés et les étapes manuelles restantes.

CONTEXTE DOCKER ET RÉSEAU

- En local, les trois services doivent pouvoir tourner dans un même projet/réseau Docker Compose : PostgreSQL, Identity et API Gateway.
- Le nom DNS Docker du service Identity est `identity-service`; son port d'écoute interne est `8081`.
- L'URL interne Identity à configurer pour les appels depuis le Gateway est :
  `http://identity-service:8081`
- Le document JWKS, si le Gateway valide lui-même les JWT, est :
  `http://identity-service:8081/.well-known/jwks.json`
- Ne configure jamais `localhost:8081` comme adresse Identity depuis le conteneur Gateway : `localhost` y désigne le conteneur Gateway lui-même.
- Le Gateway écoute actuellement sur le port conteneur `8080`, publié localement en `http://localhost:8080`.
- Dans Docker Compose, seul le Gateway doit être publié sur l'hôte pour le test de bout en bout. Le port Identity n'a pas besoin d'être publié pour que le Gateway le joigne; PostgreSQL ne doit pas être publié pour ce parcours.
- Le fichier de stack Identity existant utilise des réseaux Docker Swarm. Ces réseaux Swarm ne sont pas le réseau Compose et ne doivent pas être considérés comme automatiquement partagés.
- Si un Compose d'intégration existe déjà, adapte-le. Sinon, ajoute si approprié un Compose d'intégration local ou documente exactement comment le Composer avec les deux dépôts. Les chemins de build doivent viser les bons dépôts sans supposer un chemin absolu spécifique à une machine.

CONTRAT DU MICROSERVICE IDENTITY (VÉRIFIÉ DANS SON CODE)

Adresse et documentation :
- Port HTTP interne : `8081`.
- OpenAPI JSON : `GET /v3/api-docs`.
- Swagger UI : `GET /swagger-ui/index.html`.
- JWKS public : `GET /.well-known/jwks.json`.
- Santé readiness : `GET /actuator/health/readiness`.
- Santé liveness : `GET /actuator/health/liveness`.

Routes métier :
- `POST /users/register` est publique; en succès Identity renvoie HTTP `201` avec un objet utilisateur.
- `POST /users/login` est publique; en succès Identity renvoie HTTP `200` avec `{ "accessToken": "...", "tokenType": "Bearer", "expiresIn": <secondes> }`.
- `GET /users/me` nécessite un utilisateur authentifié.
- Les routes KYC existent aussi, mais ne les expose pas automatiquement : inspecte les besoins et les routes déjà prévues dans le Gateway. Les routes `/kyc/admin/**` sont protégées par le rôle `ADMIN`.
- Toutes les autres routes Identity requièrent une authentification, sauf exceptions explicites listées plus haut dans la configuration de sécurité Identity.

Corps des requêtes :
- Inscription : `email`, `password`, `firstName`, `lastName` sont requis; `dateOfBirth`, `address`, `phoneNumber` sont facultatifs. Email max 255 caractères; mot de passe de 8 à 72 caractères; prénom/nom max 100; adresse max 255; téléphone max 30. `dateOfBirth` est une date ISO `YYYY-MM-DD`.
- Login : `email` et `password` sont requis; email max 255 caractères; mot de passe max 72.
- La réponse de `GET /users/me` est un objet utilisateur contenant `id` (UUID), `email`, `role`, `status`, `firstName`, `lastName`, `createdAt`.
- L'inscription crée un utilisateur `USER` au statut initial `PENDING_KYC`.
- Erreurs attendues Identity : `400` validation, `401` authentification/identifiants invalides, `403` droits insuffisants, `404`, `409` conflit (par exemple email déjà utilisé), `413` taille dépassée, `500` erreur interne générique. Préserve le statut et le corps de réponse pertinents lors du proxy; ne masque pas une erreur Identity comme une réponse de succès.

Authentification par JWT :
- Identity émet des JWT signés RSA avec l'algorithme `RS256`.
- Les paramètres du JWT sont configurés côté Identity par :
  `IDENTITY_JWT_ISSUER` (valeur par défaut `identity-service`),
  `IDENTITY_JWT_AUDIENCE` (valeur par défaut `financial-coach-service`),
  `IDENTITY_JWT_EXPIRATION_MS` (valeur par défaut 900000 ms / 15 minutes),
  `IDENTITY_JWT_ACTIVE_KEY_ID` (kid actif).
- Dans le profil prod, Identity requiert une paire de clés persistantes et désactive les clés éphémères. Le Gateway ne reçoit jamais la clé privée. En local, les clés peuvent être éphémères : les jetons ne sont alors valides que contre l'instance Identity ayant émis ces jetons et tant qu'elle conserve sa paire de clés.
- Le header JWT contient `alg=RS256` et un `kid` qui correspond au champ `kid` d'une clé publiée par JWKS.
- Claims produits : `iss` = issuer configuré (par défaut `identity-service`); `aud` = audience configurée (par défaut `financial-coach-service`); `sub` = email; `userId` = UUID utilisateur; `role`; `status`; `iat`; `exp`.
- Le JWKS renvoie un objet `{ "keys": [...] }`; les clés sont RSA de vérification avec `kty`, `use=sig`, `alg=RS256`, `kid`, `n`, `e`. La réponse est mise en cache publiquement pendant 5 minutes. Aucun paramètre privé RSA ne doit être transmis au Gateway.
- Ne suppose pas que l'audience vaut le nom du Gateway. Inspecte le besoin du Gateway et le contrat des consommateurs; vérifie `iss`, `aud`, `exp`, signature et `kid` avec une bibliothèque JWT déjà présente ou un mécanisme compatible et maintenu. N'accepte pas `alg=none` ni un algorithme différent de RS256. Lors d'un kid inconnu, actualise le JWKS une fois avec contrôle de fréquence, puis rejette le jeton si la clé reste inconnue.
- Identity ne fournit pas de route de refresh token dans le contrat vérifié. N'en invente pas.
- Quand le Gateway relaie une requête authentifiée vers Identity, il doit préserver `Authorization: Bearer <JWT>` si présent. Il ne doit pas fabriquer de jeton ou transférer des identifiants utilisateurs vers des logs.

COMPORTEMENT ATTENDU DU GATEWAY

1. Crée ou complète une configuration explicite et externalisée pour l'adresse de base Identity et le JWKS, en suivant la convention de configuration réelle du Gateway. Les valeurs Docker locales doivent être `http://identity-service:8081` et `http://identity-service:8081/.well-known/jwks.json`. N'utilise pas ces noms de variables comme s'ils existaient déjà : choisis les noms conformes au projet et documente-les.
2. Configure les routes de proxy du Gateway vers Identity pour au minimum les opérations register, login et `/users/me`, selon le style de routage déjà présent. Vérifie les chemins publics réellement présentés par le Gateway et évite les collisions avec ses autres routes. Le mapping doit préserver correctement méthode HTTP, chemin, query string, corps JSON, headers utiles, statut et corps de réponse Identity.
3. Les endpoints register et login doivent rester appelables sans JWT. Si le Gateway possède un filtre d'authentification global, ajoute les exceptions précises pour ces méthodes et chemins, sans ouvrir toutes les routes Identity.
4. Pour `/users/me` et les routes protégées retenues, exige et relaie le Bearer token. Si le Gateway valide les JWT, valide la signature RS256 à partir du JWKS ainsi que `iss`, `aud` (valeur cohérente avec l'Identity configurée), `exp` et `kid`. Ne fais pas confiance à des claims de rôle envoyés séparément par le client; les rôles doivent provenir du JWT validé ou d'une réponse fiable Identity.
5. Ne duplique pas la logique métier Identity (inscription, stockage de mot de passe, statut de compte, émission de JWT). Identity demeure l'autorité pour ces fonctionnalités. Le Gateway se limite au routage et aux contrôles transversaux qu'il implémente déjà.
6. Gère les erreurs de manière explicite : Identity indisponible ne doit jamais produire une réponse 2xx; renvoie le statut d'erreur Gateway normalisé par le projet et journalise un diagnostic sans données sensibles. Ajoute timeout et comportement de retry uniquement selon les bibliothèques/patterns du Gateway; ne retry pas aveuglément une requête POST de création susceptible d'avoir déjà été exécutée.
7. Respecte ou propage `X-Correlation-ID` selon le mécanisme existant. Ne journalise jamais le corps du login, le mot de passe, `accessToken`, l'en-tête Authorization, les fichiers KYC ou la clé privée.
8. Configure CORS dans le Gateway, si nécessaire pour le navigateur, selon les origines de développement réellement utilisées et les conventions existantes. Identity désactive actuellement CORS dans sa configuration Spring Security; ne compte donc pas sur Identity pour résoudre CORS côté navigateur.
9. Vérifie que le Dockerfile du Gateway écoute sur `0.0.0.0:8080` (ou le port réellement utilisé par le projet) et que ses tests/healthcheck conviennent au conteneur. Ne change pas le port sans vérifier les fichiers Compose et le code.

TESTS ET VALIDATION À EFFECTUER

- Ajouter des tests ciblés pour le routage register/login, la propagation méthode/chemin/corps et la transmission du JWT vers `/users/me`.
- Tester que register/login demeurent publics et que les routes protégées refusent une requête sans jeton si le Gateway impose l'authentification.
- Si une validation JWT Gateway est ajoutée ou existe, tester signature RS256, issuer/audience, expiration, kid connu/inconnu, clé JWKS, et rejet d'un algorithme non pris en charge.
- Tester la préservation des statuts d'erreur Identity, notamment `401`, `403`, `409`, ainsi que le comportement Identity indisponible/timeout.
- Ne mets aucun vrai token dans les tests; génère des clés et jetons de test éphémères ou utilise les mocks du projet.
- Exécuter les tests ciblés, puis le build/typecheck existant du dépôt Gateway. Corriger les échecs dus à tes changements.
- Valider les fichiers Compose (`docker compose config`) et documenter les commandes de démarrage et d'arrêt locales sans détruire les volumes.
- Les tests de parcours doivent prouver que le Gateway joint le service par le DNS Docker `identity-service`, et non par `localhost`.

LIVRABLE

Implémente l'intégration dans ce dépôt avec les changements minimaux nécessaires : configuration, routes/filtres, Docker/Compose si le projet les contient, tests et documentation directement liée. Termine par un résumé concis des fichiers modifiés, des tests réellement exécutés et de tout blocage restant. Ne prétends pas qu'un test, une route, une variable ou un déploiement a été vérifié si tu ne l'as pas exécuté.
```

---

## Valeurs Identity de référence

Ces valeurs ont été vérifiées dans le code et la configuration de ce dépôt Identity :

| Élément | Valeur |
|---|---|
| Port interne | `8081` |
| Base URL depuis le réseau Docker | `http://identity-service:8081` |
| Register | `POST /users/register` — public |
| Login | `POST /users/login` — public |
| Profil courant | `GET /users/me` — authentifié |
| JWKS | `GET /.well-known/jwks.json` — public |
| Readiness / liveness | `/actuator/health/readiness`, `/actuator/health/liveness` |
| Signature des tokens | `RS256`, avec `kid` |
| Issuer par défaut | `identity-service` |
| Audience par défaut | `financial-coach-service` |
| Validité par défaut | 900000 ms (15 minutes) |
| Réponse login | `accessToken`, `tokenType: Bearer`, `expiresIn` en secondes |

L'audience par défaut correspond au paramètre actuellement configuré pour Identity. Si le contrat du Gateway ou d'un autre consommateur exige une audience différente, il faut la décider explicitement et aligner la configuration Identity et les vérifications de tous les consommateurs; ne pas modifier silencieusement l'audience dans le seul Gateway.

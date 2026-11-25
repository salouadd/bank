# Dossier de transmission à l'équipe Gateway

Ce guide détaille les étapes pour recueillir, vérifier et transmettre à l'équipe Gateway les informations nécessaires à l'intégration d'Identity. Il distingue les faits visibles dans le dépôt des valeurs propres à l'environnement, qui doivent être confirmées avant le déploiement.

> **Important :** les valeurs `localhost`, `127.0.0.1` et `host.docker.internal` du parcours local ne constituent pas des adresses de production. Le Gateway doit pouvoir joindre Identity depuis son propre conteneur et son réseau Swarm. Ne transmettez jamais de clé privée, de mot de passe, de token de registre ou de JWT.

## Étape 1 — Identifier le rôle attendu du Gateway

Demandez à l'équipe Gateway de préciser ce qu'elle attend d'Identity :

- Le Gateway relaie-t-il les routes login et inscription vers Identity, ou l'authentification est-elle exécutée par un autre composant ?
- Quelles routes Identity doivent être accessibles aux clients à travers le Gateway ?
- Quelles routes sont réservées aux appels internes, notamment le JWKS et les contrôles de santé ?
- Le Gateway valide-t-il lui-même les JWT, ou transmet-il les tokens à un service qui les valide ?
- Quels rôles et quelles permissions le Gateway doit-il appliquer ?
- Comment le Gateway identifie-t-il l'utilisateur : `sub`, `userId`, ou les deux ?
- Quelles réponses d'erreur, limites de débit et règles CORS sont attendues ?

Ne supposez pas que le Gateway doit exposer toutes les routes Identity. Décidez explicitement si les opérations KYC et les routes de documentation doivent être accessibles par son intermédiaire.

## Étape 2 — Recueillir les informations de déploiement Identity

Demandez à l'équipe Identity de fournir les éléments suivants pour l'environnement cible, et non les exemples de développement :

| Information à demander | Valeur à transmettre au Gateway |
|---|---|
| Nom complet de l'image et registre | |
| Tag immuable ou digest de l'image déployée | |
| Nom DNS du service Identity sur le réseau Swarm partagé | |
| Port interne et protocole écoutés par Identity | |
| URL interne de base joignable par le Gateway | |
| URL du JWKS joignable par le Gateway | |
| Chemin et méthode de readiness | |
| TLS requis entre Gateway et Identity ? Où TLS est-il terminé ? | |
| Réseaux Swarm que les deux services doivent partager | |
| Timeouts et limites de requêtes (notamment upload KYC) | |
| Contact opérationnel et procédure de support | |

Le service de ce dépôt expose le port `8081` et sa configuration Spring Boot ne fixe pas `server.address`; il écoute donc par défaut sur les interfaces du conteneur. Dans le `docker-compose.yml` local, la publication `127.0.0.1:8081:8081` limite toutefois l'accès depuis la machine hôte. Ce mappage local n'est pas le nom d'hôte à configurer dans un Gateway en production.

En Swarm, si le Gateway et Identity partagent un réseau overlay, faire confirmer par l'équipe de déploiement le nom DNS de service et le port interne utilisés, par exemple `http://identity-service:8081`. N'utiliser cet exemple que si le nom du service, le réseau partagé et le protocole ont été réellement configurés ainsi. Pour le développement Docker Desktop, le dépôt d'architecture mentionne `host.docker.internal:8081`; cette adresse n'est pas une configuration Swarm de production.

## Étape 3 — Fournir les routes Identity et leur contrat

Le dépôt décrit les routes ci-dessous. Vérifiez le contrat effectif de la version déployée avec le document OpenAPI (`/v3/api-docs`) et l'équipe Identity. Base locale indicative : `http://localhost:8081`; remplacez-la par l'URL interne approuvée de l'étape 2.

| Méthode et route | Accès constaté | Utilité / remarque |
|---|---|---|
| `POST /users/login` | Public | Connexion; JSON avec `email` et `password`; réponse avec `accessToken`, `tokenType` (`Bearer`) et `expiresIn` en secondes. |
| `POST /users/register` | Public | Inscription; le rôle est attribué côté serveur. Vérifier le schéma exact dans OpenAPI. |
| `GET /users/me` | JWT Bearer requis | Profil courant; retourne notamment `id`, `email`, `role` et `status`. |
| `GET /.well-known/jwks.json` | Public | Clés publiques RSA permettant la vérification des JWT. |
| `GET /actuator/health/readiness` | Public dans la configuration | Readiness Identity; le groupe inclut l'état de readiness et la base de données. À utiliser par l'orchestrateur selon le contrat opérationnel validé. |
| `GET /v3/api-docs` | Public dans la configuration | Contrat OpenAPI; ne l'exposer via le Gateway que si cela est voulu. |
| `GET /swagger-ui/index.html` | Public dans la configuration | Interface Swagger; ne l'exposer via le Gateway que si cela est voulu. |

Les autres routes, notamment `/kyc/**`, doivent être ajoutées au périmètre Gateway uniquement après confirmation des besoins, des autorisations, des limites de taille et des risques de publication. Les routes `/kyc/admin/**` exigent un rôle administrateur côté Identity.

Communiquez à l'équipe Gateway :

1. La liste blanche des routes autorisées et leur méthode HTTP.
2. Les routes qui doivent transmettre le corps JSON ou `multipart/form-data` sans altération.
3. Les en-têtes nécessaires, notamment `Authorization` pour les routes authentifiées et `X-Correlation-ID` pour la corrélation des requêtes.
4. Les délais maximums et tailles de corps acceptés, confirmés par l'équipe Identity.
5. Les réponses d'erreur à préserver. Le contrat documente principalement `400`, `401`, `403`, `404`, `409`, `413` et `500`.

Le contrat OpenAPI est accessible en local sur `http://localhost:8081/v3/api-docs`; l'équipe Gateway doit consommer la version correspondant à l'image cible, pas une version ancienne ou issue d'une autre branche.

## Étape 4 — Transmettre les paramètres de validation JWT

L'équipe Identity doit confirmer les paramètres ci-dessous pour l'environnement exact. Les valeurs entre crochets sont à renseigner par les équipes :

```text
Algorithme de signature : RS256
Issuer attendu (iss)    : [valeur déployée]
JWKS URI                : [URL interne joignable depuis le Gateway]
Audience (aud)          : [valeur si elle est émise et vérifiée; sinon faire confirmer son absence]
Durée de vie (exp)      : [durée réellement configurée]
Rôles acceptés          : [valeurs exactes émises et attendues]
Identifiant utilisateur : [claim à utiliser et format]
```

### Éléments actuellement visibles dans le code Identity

- Les tokens sont signés en `RS256`.
- Le claim `iss` provient de `IDENTITY_JWT_ISSUER`; sa valeur par défaut est `identity-service`.
- La route JWKS est `GET /.well-known/jwks.json`. Chaque entrée JWK expose notamment `kty`, `use`, `alg`, `kid`, `n` et `e`; le JWKS annonce un cache public de cinq minutes.
- Le JWT contient `sub`, `userId`, `role`, `status`, `iat` et `exp`.
- Dans le code actuel, `sub` contient l'adresse e-mail et `userId` l'UUID du compte. Le Gateway et les consommateurs aval doivent choisir explicitement le claim d'identification approprié; ne pas traiter `sub` comme un UUID.
- L'enum de rôle Identity contient `ROLE_USER` et `ROLE_ADMIN`. La valeur du claim `role` est le nom de cet enum. Si le Gateway attend `USER` et `ADMIN`, les équipes doivent convenir d'un mapping explicite et le tester.
- La durée par défaut définie dans le code est de 900 000 ms, soit 15 minutes. La configuration peut la remplacer via `IDENTITY_JWT_EXPIRATION_MS`; confirmer la valeur réelle du déploiement.
- Le code Identity présenté vérifie l'issuer, la signature RS256, le `kid` correspondant à une clé connue et l'expiration lors de la validation de ses propres routes protégées. Ne pas en déduire une valeur `aud` : demander si une audience est réellement émise et vérifiée.

Le Gateway doit sélectionner la clé JWKS correspondant au `kid` de l'en-tête du JWT, imposer l'algorithme convenu (RS256), puis vérifier la signature, `iss`, `exp` et, si défini, `aud`. Il ne doit pas accepter un token uniquement parce qu'il est décodable.

### Gestion des clés — point bloquant à confirmer

Le profil `prod` désactive les clés éphémères et exige des emplacements de clés configurés. Le dépôt d'architecture signale que les clés peuvent autrement être régénérées au redémarrage, invalidant des tokens encore valides. Avant toute intégration de production, Identity et Gateway doivent convenir de la persistance et de la rotation des clés, de la publication des anciennes/nouvelles clés pendant la période de chevauchement et du comportement de cache JWKS.

Le Gateway ne reçoit et ne stocke que l'URL JWKS et les clés publiques qu'il télécharge. **Ne lui transmettez jamais la clé privée RSA**, ni son fichier, son contenu, un secret qui la contient, ou un accès au secret de signature.

## Étape 5 — Configurer la connectivité et la sécurité réseau

Avec l'équipe qui gère le Swarm :

1. Créez ou identifiez le réseau overlay que partageront Gateway et Identity.
2. Confirmez le nom DNS de service Identity et son port d'écoute interne.
3. Depuis une tâche Gateway de l'environnement cible, vérifiez l'accès à la readiness et au JWKS sur ce réseau.
4. Confirmez si TLS est requis entre les services et où les certificats sont gérés. Ne transmettez pas de certificat privé dans le dossier Gateway.
5. N'utilisez pas `localhost` dans la configuration Gateway pour désigner Identity : dans un conteneur, `localhost` désigne ce même conteneur.
6. Ne publiez pas le port Identity sur toutes les interfaces de l'hôte simplement pour permettre au Gateway de l'atteindre; privilégiez la connectivité interne du réseau prévue.
7. Faites confirmer les règles de pare-feu, politiques de routage, résolution DNS et restrictions de sortie nécessaires pour récupérer le JWKS.

La vérification doit provenir du réseau et du conteneur du Gateway. Un test effectué depuis le poste de développement ne prouve pas que le Gateway en production peut joindre Identity.

## Étape 6 — Définir les variables Gateway sans y mettre de secret

Remettez à l'équipe Gateway les **noms** des paramètres à remplir selon son propre projet. Les noms ci-dessous sont descriptifs, pas nécessairement les noms de variables reconnus par son application :

```text
IDENTITY_BASE_URL=[URL interne de base confirmée]
IDENTITY_JWKS_URI=[URL interne complète de /.well-known/jwks.json]
IDENTITY_EXPECTED_ISSUER=[issuer réel confirmé]
IDENTITY_EXPECTED_ALGORITHM=RS256
IDENTITY_USER_ID_CLAIM=[sub ou userId, selon le contrat adopté]
IDENTITY_ROLE_CLAIM=role
IDENTITY_ROLE_USER=[valeur exacte convenue]
IDENTITY_ROLE_ADMIN=[valeur exacte convenue]
```

L'équipe Gateway doit confirmer les véritables noms de configuration de son microservice et la manière dont ils sont injectés. Ne créez pas de paramètres au hasard et ne placez aucun mot de passe, token, clé privée ou identifiant de registre dans le YAML partagé.

## Étape 7 — Faire les tests d'intégration ensemble

Effectuez les essais dans un environnement non productif, avec un compte et des données de test :

1. Depuis le Gateway, appeler `GET [IDENTITY_BASE_URL]/actuator/health/readiness`; vérifier que le service est prêt.
2. Depuis le Gateway, appeler `GET [IDENTITY_JWKS_URI]`; vérifier une réponse JSON contenant `keys`, avec au moins une clé de signature RSA et un `kid`.
3. Se connecter via le chemin choisi (directement à Identity ou par le Gateway). Vérifier que la réponse contient `accessToken`, `tokenType: Bearer` et `expiresIn`; ne pas enregistrer ni partager le token de test.
4. Vérifier le JWT avec une bibliothèque de validation reconnue et la clé correspondant à `kid`; contrôler `alg`, la signature, `iss`, `exp` et les claims convenus.
5. Vérifier les valeurs réelles de `sub`, `userId` et `role`, en particulier le format e-mail de `sub` et les valeurs `ROLE_USER` / `ROLE_ADMIN` du code actuel.
6. Tester une route authentifiée avec un token valide, puis tester un token expiré/invalide et les rôles insuffisants. Attendre les résultats prévus (`200`, `401` et/ou `403` selon l'opération).
7. Vérifier le comportement quand le JWKS est temporairement indisponible, lorsqu'un nouveau `kid` apparaît et pendant une rotation de clés; définir une stratégie de cache/rafraîchissement sûre.
8. Tester les réponses d'erreur et `X-Correlation-ID` sans journaliser les identifiants, les mots de passe ni les tokens.
9. Si Gateway relaie l'inscription ou les opérations KYC, tester explicitement leurs schémas, méthodes, limites et règles d'accès.
10. Consigner le résultat, les versions d'images, l'environnement, le responsable et les anomalies à corriger. Ne pas utiliser de données personnelles réelles.

## Étape 8 — Envoyer le dossier de handoff

Copiez et remplissez ce résumé dans le canal approuvé de l'équipe Gateway. Les valeurs de configuration non sensibles peuvent être transmises selon les règles de l'organisation; les secrets restent dans le gestionnaire de secrets.

```text
Service émetteur : Identity
Environnement :
Image Identity (tag immuable/digest) :
Nom DNS interne Identity :
Port interne / protocole :
Base URL interne :
JWKS URI :
Issuer attendu (iss) :
Algorithme :
Durée de vie du token :
Claims émis (sub, userId, role, status, iat, exp) :
Valeur réelle de sub :
Valeurs réelles de role :
Claim d'identifiant utilisateur retenu par Gateway :
Routes Identity à exposer via Gateway :
Routes Identity à ne pas exposer :
Readiness et critère de disponibilité :
Réseau Swarm partagé :
TLS / certificats (sans clé privée) :
Gestion de cache et rotation JWKS :
Référence au contrat OpenAPI de cette version :
Résultat des tests d'intégration :
Responsables et date de validation :
Points bloquants / actions restantes :
```

## Contrôle final avant mise en production

Ne validez pas l'intégration tant que les équipes n'ont pas confirmé : l'URL Identity joignable depuis Gateway, l'URL JWKS, l'issuer exact, le mapping des rôles et de l'identifiant utilisateur, la gestion durable et la rotation des clés, les routes autorisées, et les résultats des tests bout en bout. Le fait que le JWT soit signé avec RS256 ne suffit pas à lui seul à établir un contrat compatible.

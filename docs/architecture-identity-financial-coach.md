# Architecture et fonctionnement : Identity Service et Financial Coach Service

Ce document décrit les deux dépôts à partir de leur code et de leur configuration :

- `identity-service` : `C:\Users\bnsal\IdeaProjects\identity-service`
- `financial-coach-service` : `C:\Users\bnsal\IdeaProjects\financial-coach-service`

Il distingue le fonctionnement implémenté des points d’intégration qui restent à corriger ou à confirmer.

## 1. Vue d’ensemble

`identity-service` gère les comptes, l’authentification et le KYC. `financial-coach-service` gère les fonctions de coaching budgétaire et vérifie les jetons émis par le premier service. Ils sont deux applications Spring Boot indépendantes, chacune avec sa propre base PostgreSQL.

```text
Client
  | 1. inscription / connexion
  v
Identity Service :8081  ---> PostgreSQL identity_db
  | émet un JWT signé RS256
  | publie sa clé publique via /.well-known/jwks.json
  v
Client -- Authorization: Bearer <JWT> --> Financial Coach Service :8082
                                            |--> PostgreSQL coach_db
                                            |--> OpenAI (analyse budgétaire)
                                            |--> Transaction Service (détection de salaire)
```

Le Financial Coach ne lit pas directement la base de données de l’Identity Service. Il identifie l’utilisateur grâce aux informations contenues dans le JWT.

## 2. Identity Service

### Rôle

Ce service gère l’identité et les justificatifs KYC :

- inscription et connexion ;
- profil utilisateur courant ;
- dépôt de documents KYC et décision d’un administrateur ;
- émission de JWT signés avec RSA/RS256 et publication de la clé publique au format JWKS.

### Fonctionnement d’une connexion

1. Le client envoie ses identifiants à `POST /users/login`.
2. Le service vérifie le mot de passe avec BCrypt.
3. En cas de succès, il émet un JWT valable 15 minutes.
4. Le JWT contient notamment `userId`, `role` et `status`. Son sujet (`sub`) est l’adresse e-mail.
5. Pour ses propres routes protégées, Identity Service vérifie le JWT et recharge l’utilisateur en base.

### Routes principales

| Route | Utilisation |
|---|---|
| `POST /users/register` | Créer un compte |
| `POST /users/login` | Se connecter et obtenir un JWT |
| `GET /users/me` | Obtenir le profil de l’utilisateur connecté |
| `POST /kyc/upload` | Déposer un justificatif KYC |
| `GET /kyc/documents` | Consulter ses justificatifs |
| `GET /kyc/admin/pending` | Lister les justificatifs en attente (administrateur) |
| `PUT /kyc/admin/documents/{id}/verify` | Valider un justificatif (administrateur) |
| `PUT /kyc/admin/documents/{id}/reject` | Rejeter un justificatif (administrateur) |
| `GET /.well-known/jwks.json` | Publier la clé publique RSA utilisée pour vérifier les JWT |

### Données et exécution

PostgreSQL stocke les comptes, profils et statuts KYC. Flyway gère le schéma. Les documents sont écrits dans `uploads/`, monté depuis le système de fichiers avec Docker Compose. Le service écoute sur le port `8081`.

## 3. Financial Coach Service

### Rôle

Ce service fournit des fonctions de budget et d’épargne. Il ne crée pas les comptes et ne délivre pas de JWT : il accepte les JWT de l’Identity Service comme un serveur de ressources OAuth2.

Ses domaines fonctionnels sont :

- profils budgétaires et répartition d’un revenu selon des ratios configurables (par défaut 50/30/20) ;
- analyse des dépenses par IA et recommandations ;
- détection de revenus récurrents à partir du Transaction Service ;
- coffres d’épargne, intentions de verrouillage et opérations confirmées ;
- historique des recommandations, consentement, export et effacement des données ;
- journalisation d’audit.

### Routes principales

Les routes fonctionnelles sont protégées et commencent par `/api/v1/coach` :

| Route | Utilisation |
|---|---|
| `GET /api/v1/coach/profile` / `PUT /api/v1/coach/profile` | Lire ou mettre à jour le profil budgétaire |
| `POST /api/v1/coach/allocate` | Calculer une répartition budgétaire |
| `POST /api/v1/coach/analyze-budget` | Analyser des dépenses et générer des conseils |
| `GET /api/v1/coach/salary/detect` | Rechercher un salaire récurrent dans les transactions |
| `/api/v1/coach/vaults` | Créer, lister, renommer et supprimer des coffres |
| `/api/v1/coach/vaults/{vaultId}/lock-intent` | Créer une intention de verrouillage |
| `/api/v1/coach/vaults/{vaultId}/lock-confirm` | Confirmer le verrouillage avec une clé d’idempotence |
| `GET /api/v1/coach/recommendations` | Lire l’historique des recommandations |
| `/api/v1/coach/me/consent` | Lire ou modifier le consentement au traitement IA |
| `GET /api/v1/coach/me/export` / `DELETE /api/v1/coach/me/data` | Exporter ou effacer les données du coach |

Le service stocke ses propres données dans `coach_db`, distincte de `identity_db`. Il écoute sur le port `8082`. Flyway gère son schéma.

Pour l’analyse IA, les dépenses sont filtrées par catégorie, agrégées et arrondies avant l’appel Spring AI/OpenAI. La réponse est ensuite filtrée et bornée par des règles Java. `OPENAI_API_KEY` doit être configurée avec une vraie clé pour utiliser le fournisseur OpenAI ; la valeur par défaut du Compose est une valeur de développement.

Pour détecter un salaire, le service appelle le Transaction Service configuré par `services.transaction-service.base-url`, lui transmet le JWT reçu, récupère les entrées des 90 derniers jours, puis cherche des crédits périodiques et propose une répartition budgétaire. Ce Transaction Service est une dépendance séparée, non démarrée par les deux Compose décrits ici.

## 4. Comment les deux services collaborent

### Vérification du JWT

1. Le client s’inscrit et se connecte auprès d’Identity Service.
2. Il envoie le JWT reçu au Financial Coach avec l’en-tête `Authorization: Bearer <JWT>`.
3. Financial Coach récupère la clé publique depuis `/.well-known/jwks.json` à l’adresse configurée par `JWKS_URI`, puis vérifie la signature et l’expiration du JWT.
4. Il extrait le rôle `role` du JWT et exige le rôle utilisateur pour les routes `/api/v1/coach/**`.
5. Le service utilise l’identifiant utilisateur pour isoler les profils, recommandations et coffres dans sa propre base.

Cette vérification est locale au Financial Coach après récupération de la clé publique : il n’interroge pas Identity Service pour chaque requête.

### Réseau Docker local

Les fichiers Compose des projets démarrent chacun leur service et leur base séparément. Le Compose du coach configure par défaut :

```text
JWKS_URI=http://host.docker.internal:8081/.well-known/jwks.json
```

et ajoute `host.docker.internal:host-gateway`. Cela permet au conteneur du coach, sous Docker Desktop, d’atteindre le port `8081` publié par le Compose Identity. Il faut donc démarrer Identity Service avant d’appeler le coach. Les deux Compose ne créent pas un réseau Docker commun et ne démarrent pas automatiquement l’un l’autre.

Exemple de démarrage depuis PowerShell :

```powershell
cd C:\Users\bnsal\IdeaProjects\identity-service
docker compose up --build -d

cd C:\Users\bnsal\IdeaProjects\financial-coach-service
docker compose up --build -d
```

Les deux bases sont isolées. Le Compose du coach ne publie pas le port de sa base ; celui d’Identity publie PostgreSQL sur `5432`.

## 5. Contrats et points à corriger avant de considérer l’intégration complète

### Identifiant utilisateur : incompatibilité entre `sub` et `userId`

Identity Service place l’e-mail dans `sub` et l’identifiant UUID dans le claim `userId`. Or plusieurs contrôleurs du Financial Coach font `UUID.fromString(jwt.getSubject())`. Avec le JWT actuellement émis, le sujet est l’e-mail : ces routes échoueront lors de la conversion en UUID.

Les contrôleurs profil budgétaire et coffres lisent déjà `userId` en priorité, avec un repli vers `sub`. Les contrôleurs d’analyse budgétaire, d’allocation, de détection de salaire, de confidentialité, d’historique et de verrouillage utilisent directement `sub`. Il faut harmoniser l’extraction pour utiliser le claim `userId` dans tous les cas, ou définir un contrat cohérent sans casser le filtre d’authentification d’Identity Service, qui utilise actuellement `sub` comme e-mail.

### Clés RSA régénérées au redémarrage

La configuration actuelle d’Identity Service génère la paire RSA en mémoire au démarrage. Un redémarrage produit une nouvelle clé : les JWT encore valides signés par l’ancienne clé ne seront plus vérifiables avec le JWKS courant. En production, la clé privée doit être persistée et gérée de façon sécurisée.

### Consentement et traitement IA

Le Financial Coach expose des routes de consentement. Toutefois, le chemin d’analyse IA consulté appelle le fournisseur IA sans vérifier ce consentement dans `AiExpenseAuditorService`. Il faut vérifier que le consentement est contrôlé avant tout envoi de données au fournisseur, et définir les règles de conservation et de minimisation correspondantes.

### Configuration de déploiement

Les valeurs de démonstration des mots de passe PostgreSQL ne conviennent pas à la production. Il faut fournir les secrets via l’environnement ou un gestionnaire de secrets. Pour un déploiement sur un réseau différent, `JWKS_URI` doit viser une adresse joignable depuis le conteneur coach ; `host.docker.internal` est un choix de développement local et ne remplace pas une configuration de production.

## 6. Résumé

- **Identity Service** est la source d’identité : comptes, authentification, KYC et émission des JWT.
- **Financial Coach Service** est le consommateur du JWT : coaching budgétaire, épargne, recommandations et fonctions IA.
- Leur intégration est faite par JWT signé RS256 et JWKS, avec deux bases de données distinctes.
- Le code configure ce mécanisme, mais l’extraction de `userId` depuis le JWT doit être corrigée dans plusieurs contrôleurs du coach avant que tous les parcours soient fonctionnels.

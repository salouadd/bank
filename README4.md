# Identity Service — bilan du projet et travaux restants

Ce document est un **résumé consolidé** de l'état du microservice après les étapes de travail décrites dans `README3.md`. Il distingue les fonctionnalités présentes dans le dépôt, les vérifications réellement exécutées et les points encore bloquants. Il ne constitue ni une certification de sécurité ni une autorisation de mise en production.

Le projet vise à préparer le socle applicatif avant de choisir et mettre en œuvre son architecture AWS. **Aucune ressource AWS ni aucun déploiement AWS ne sont couverts ici.**

## 1. Résumé exécutif

Le microservice dispose maintenant des bases fonctionnelles d'identité et KYC, d'une authentification JWT RS256/JWKS, d'un contrôle d'accès initial, de migrations Flyway, de configurations local/test/prod, d'une documentation OpenAPI générée et d'un workflow de scans CI séparé par type de contrôle.

Dernière vérification locale documentée : `.\mvnw.cmd clean verify` réussit avec **51 tests, 0 échec et 0 erreur**, sur la base PostgreSQL locale existante. Cette vérification ne valide pas les conteneurs, l'exécution du workflow GitHub, une base de test isolée, le stockage durable ni la préparation réelle à la production.

**Conclusion : le service n'est pas prêt pour la production ni pour AWS.** Les blocages majeurs sont :

- décisions métier et conformité de l'étape 6 non confirmées;
- aucun stockage KYC durable multi-instance ni analyse antivirus/CDR;
- aucun mécanisme de provisionnement admin approuvé pour la production (un outil ponctuel restreint au profil `local` est maintenant disponible pour les essais);
- protections anti-abus et limitation des tentatives de connexion manquantes;
- tests PostgreSQL isolés, scénarios de charge et CI réelle non validés;
- politiques de rétention, d'audit, de sauvegarde/restauration et de révocation interservices à finaliser.

## 2. Fonctionnalités présentes

### Comptes et authentification

- `POST /users/register` crée un compte `ROLE_USER` au statut provisoire `PENDING_KYC` et son profil.
- Les adresses e-mail sont nettoyées et normalisées de manière partagée; une contrainte unique normalisée en base protège aussi contre les doublons concurrents.
- Les mots de passe sont hachés avec BCrypt; la limite applicative tient compte de la limite de 72 octets UTF-8.
- `POST /users/login` délivre un JWT Bearer signé RS256. Un compte suspendu ne peut pas obtenir de jeton.
- Le filtre JWT recharge l'utilisateur depuis la base sur les routes protégées et refuse les comptes suspendus, y compris avec un jeton déjà émis.
- `GET /users/me` expose le profil de l'utilisateur authentifié.

### JWT et JWKS

- `GET /.well-known/jwks.json` expose les clés publiques RSA au format JWKS; la clé privée n'est jamais publiée.
- Les JWT comprennent notamment `iss`, `sub`, `userId`, `role`, `status`, `iat` et `exp`; le `kid` identifie la clé de signature. La durée par défaut est de 15 minutes.
- Les clés persistantes sont configurables par propriétés. Le profil de production désactive les clés éphémères et exige les clés configurées.
- Plusieurs clés publiques peuvent être utilisées pendant une rotation. Des tests couvrent la persistance/redémarrage, la rotation avec chevauchement, une signature altérée et un `kid` inconnu.

### KYC

- `POST /kyc/upload` accepte des fichiers PDF, JPEG ou PNG jusqu'à la limite configurée (10 MiB par défaut); le service vérifie une signature de contenu et la cohérence avec l'extension.
- Le service stocke une clé opaque au lieu du chemin fourni par le client et masque le numéro de document dans les réponses.
- `GET /kyc/documents` liste uniquement les documents liés à l'utilisateur authentifié.
- Les routes `/kyc/admin/**` requièrent `ROLE_ADMIN`; elles permettent la liste des dossiers en attente, leur validation/rejet et le téléchargement en pièce jointe avec `nosniff`.
- Une validation de fichier est suivie d'un nettoyage en cas d'échec de persistance/rollback de la base.
- Le stockage fourni est **local uniquement**. La signature de fichier ne remplace ni antivirus/CDR ni validation complète du format.

### Sécurité, exploitation et documentation

- API stateless; HTTP Basic et formulaire désactivés. Routes Actuator limitées à la santé avec détails masqués.
- Profils de configuration `local`, `test` et `prod`; fermeture gracieuse, limites Tomcat/multipart, SQL non affiché et Open Session in View désactivé.
- Corrélation des requêtes via `X-Correlation-ID`, UUID validé ou généré par le service.
- Dockerfile prévu pour un utilisateur non-root et un système de fichiers en lecture seule; Compose est réservé au développement et exige `LOCAL_POSTGRES_PASSWORD`.
- OpenAPI est généré aux routes `/v3/api-docs` et `/swagger-ui/index.html`. Le contrat et les guides d'exploitation sont dans `docs/API.md` et `docs/OPERATIONS.md`.
- Les seuils de scans et la procédure d'exception temporaire sont décrits dans `docs/SECURITY-EXCEPTIONS.md`.

## 3. Routes principales

| Méthode et route | Accès | Fonction |
|---|---|---|
| `POST /users/register` | Public | Création d'un compte utilisateur |
| `POST /users/login` | Public | Authentification et émission d'un JWT |
| `GET /users/me` | JWT valide | Lecture du profil courant |
| `POST /kyc/upload` | JWT valide | Dépôt d'un document KYC |
| `GET /kyc/documents` | JWT valide | Liste des documents du compte courant |
| `GET /kyc/admin/pending` | `ROLE_ADMIN` | Liste des dossiers en attente |
| `PUT /kyc/admin/documents/{id}/verify` | `ROLE_ADMIN` | Vérification d'un document en attente |
| `PUT /kyc/admin/documents/{id}/reject` | `ROLE_ADMIN` | Rejet d'un document avec motif |
| `GET /kyc/admin/documents/{id}/file` | `ROLE_ADMIN` | Téléchargement du document |
| `GET /.well-known/jwks.json` | Public | Publication des clés publiques JWT |
| `GET /actuator/health`, `/readiness`, `/liveness` | Public | Vérifications de santé sans détails internes |
| `GET /v3/api-docs`, `/swagger-ui/**` | Public | Consultation du contrat OpenAPI |

Les autres routes Actuator sont protégées. Les erreurs attendues utilisent notamment `400`, `401`, `403`, `404`, `409` ou `413`; les erreurs imprévues retournent un message `500` générique.

Le contrat ne définit pas encore de versionnement de routes (`/v1`). Le champ de version OpenAPI ne remplace pas une politique de compatibilité/versionnement URI.

## 4. Historique synthétique des étapes

| Étape | Réalisation actuelle | État |
|---|---|---|
| 0 — Contrat initial | Routes et comportement relevés; exécution Docker et migrations vierges/depuis V1 non vérifiées | Partielle |
| 1 — Règles métier | Statuts provisoires, suspension et transitions KYC appliqués; activation après un document reste une hypothèse | Partielle, décisions métier requises |
| 2 — DTO et erreurs | Normalisation e-mail, unicité, validations, minimisation KYC et format d'erreurs renforcés | Partielle, couverture/migrations à compléter |
| 3 — Autorisation | JWT stateless, refus `401`/`403`, routes admin contrôlées et santé limitée; provisionnement ponctuel local pour les tests | Partielle, provisionnement production auditable et anti-abus manquants |
| 4 — JWT/JWKS | Clés RSA configurables, publication multi-clés, procédure de rotation documentée | Partielle, consommateurs réels, audience et révocation à préciser |
| 5 — KYC | Stockage abstrait, stockage local, validation de signature, clé opaque, téléchargement admin et tests de nettoyage | Partielle, aucun stockage durable ni antivirus/CDR |
| 6 — Parcours de compte | Pas de vérification e-mail, reset de mot de passe, révocation de session ni fermeture/suppression | En attente de décisions produit/conformité et du canal e-mail |
| 7 — Exécution/configuration | Profils, health probes, corrélation, durcissement Docker/Compose documentés/implémentés | Partielle, Docker et fournisseur de stockage non validés |
| 8 — Tests | Tests de service, MVC, sécurité, JWT/JWKS et KYC; dernière suite : 45 tests | Partielle, CI/base isolée/charge à faire |
| 9 — CI et documentation | Workflow séparé de scans, OpenAPI et guides API/opérations/exceptions | Partielle, workflow GitHub et seuils à valider |

> Les nombres de tests dans certains anciens comptes rendus de `README3.md` correspondent à des états antérieurs. La vérification la plus récente est `clean verify` avec 51 tests.

## 5. CI et seuils de sécurité définis

Le workflow `.github/workflows/devsecops-pipeline.yml` contient des jobs distincts pour :

- compilation/tests Maven avec PostgreSQL;
- détection de secrets Gitleaks;
- SAST Semgrep;
- scan des dépendances Trivy;
- build et scan de l'image Trivy;
- DAST OWASP ZAP Baseline sur le Compose local.

Les seuils sont configurés pour bloquer sur les secrets détectés, les findings Semgrep signalés en erreur, les vulnérabilités `HIGH`/`CRITICAL` Trivy et les alertes ZAP `FAIL`. Les rapports sont conservés comme artefacts pour une durée définie dans le workflow. Les exceptions doivent avoir une justification, un propriétaire et une expiration.

**La CI n'a pas encore été exécutée ni observée sur GitHub dans le cadre du dernier travail.** Les protections de branche doivent exiger les checks correspondants. Le ZAP actuel n'est pas authentifié et ne couvre donc pas les parcours privés ou administrateur. Les résultats d'un scan sont des signaux à examiner, pas une preuve d'absence de vulnérabilité ou de conformité.

## 6. Travaux restants, par priorité

### Priorité 1 — Décisions produit, conformité et sécurité

1. Confirmer les documents KYC exigés, la règle d'activation, les accès autorisés avant KYC complet et la politique pour les comptes rejetés/suspendus.
2. Définir vérification d'e-mail, récupération/changement de mot de passe, révocation de sessions et fermeture de compte; choisir le canal d'envoi d'e-mails.
3. Définir rétention/suppression KYC, audit administratif, limites de connexion et MFA selon le risque métier.
4. Fixer la stratégie des claims `aud`, des consommateurs, du cache JWKS et de la révocation des JWT en dehors de ce service.

### Priorité 2 — Bloqueurs d'exploitation avant toute production

1. Implémenter et tester un stockage privé durable multi-instance derrière `DocumentStorage`; définir chiffrement, sauvegarde, contrôle d'accès, rétention et suppression.
2. Ajouter antivirus/CDR et un traitement maîtrisé des fichiers suspects; concevoir la réconciliation des objets orphelins.
3. Mettre en place un provisionnement initial de production contrôlé, auditable et sans mot de passe par défaut. L'outil local actuel n'est pas prévu pour la production.
4. Implémenter limitation de débit et protections contre les essais automatisés/abus d'inscription et de dépôt.
5. Valider TLS, CORS et les en-têtes proxy dans un environnement contrôlé. Ne pas exposer directement les ports applicatifs.
6. Définir et exécuter des procédures réelles de sauvegarde/restauration, de rotation de clés et de gestion d'incident avec les équipes responsables.

### Priorité 3 — Compléter les preuves de test

1. Faire pointer le profil `test` vers une base dédiée jetable, et non vers la base PostgreSQL locale de développement.
2. Tester migrations Flyway sur une base vide et depuis chaque version de schéma supportée.
3. Ajouter tests d'intégration PostgreSQL pour inscription concurrente, contraintes réelles, rollback transactionnel et comportements KYC.
4. Ajouter tests d'accès croisé, de limites de débit, de fichiers hostiles et d'échec/indisponibilité du stockage.
5. Établir des tests de charge reproductibles sur connexion, consultation et dépôt, avec seuils de latence/erreurs définis par le produit.
6. Tester la rotation JWKS avec de vrais consommateurs et leurs caches, ainsi que l'impact d'une révocation.

### Priorité 4 — Valider la CI et le contrat API

1. Exécuter le workflow sur GitHub et corriger les erreurs réelles de jobs/outils, versions, Compose et rapports.
2. Activer les checks requis dans la protection de branche; faire approuver les seuils et exceptions par les responsables sécurité.
3. Examiner les findings Semgrep/Trivy/ZAP au lieu d'interpréter le succès du scan comme une certification.
4. Envisager un ZAP authentifié et cadrer précisément les routes publiques/privées de l'analyse.
5. Décider d'une stratégie de versionnement OpenAPI/URI et maintenir les exemples contre le comportement effectivement testé.

## 7. Vérification actuelle et démarrage local

La dernière validation de code consignée est :

```powershell
.\mvnw.cmd clean verify
```

Résultat : **BUILD SUCCESS — 51 tests, 0 échec, 0 erreur**. Flyway a validé cinq migrations sur la base locale PostgreSQL 18.6. La version PostgreSQL locale est plus récente que les versions supportées/testées annoncées par la version Flyway utilisée; cette configuration doit être vérifiée sur une version supportée.

Docker n'était pas disponible dans l'environnement de travail. Les commandes suivantes et le build de l'image n'ont donc pas été validés localement :

```powershell
$env:LOCAL_POSTGRES_PASSWORD = "<mot-de-passe-local>"
docker compose up --build
```

Compose est uniquement destiné au développement. Ne jamais utiliser un mot de passe local ni le stockage disque local en production.

Pour lancer Maven directement, Java 21 et PostgreSQL doivent être disponibles et `application-local.yaml` configuré pour la base locale :

```powershell
.\mvnw.cmd spring-boot:run
```

## 8. Critères avant d'entamer l'étude de déploiement AWS

AWS pourra être étudié comme étape distincte lorsque :

- les règles métier/KYC et les exigences produit/conformité sont approuvées;
- l'admin est provisionné de façon sûre, les rôles et limites anti-abus sont opérants;
- stockage KYC durable, privé, contrôlé et sauvegardé est disponible;
- parcours de compte et règles de révocation sont décidés et implémentés selon le besoin;
- tests d'intégration isolés, migrations, concurrence, rollback et charge passent;
- rotation des clés est vérifiée avec les consommateurs réels;
- CI est exécutée, ses seuils approuvés et ses checks imposés sur les branches;
- restauration, incidents, journaux d'audit et rétention ont des procédures vérifiées;
- la configuration de production démarre sans secrets par défaut et avec TLS/proxy/CORS contrôlés.

**État global : socle applicatif en progrès, non prêt pour la production.** Continuer les décisions et blocages applicatifs avant de créer des ressources AWS. Pour les détails étape par étape et les hypothèses initiales, consulter `README3.md`; pour le contrat et les procédures, consulter `docs/API.md`, `docs/OPERATIONS.md` et `docs/SECURITY-EXCEPTIONS.md`.

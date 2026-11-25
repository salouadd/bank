# Identity Service — état du projet et feuille de route

Ce document décrit **ce qui existe déjà dans ce dépôt**, comment le lancer et les étapes recommandées pour le terminer. Il complète le guide technique présent dans `docs/guide-complet-identity-service.md`. Les éléments listés comme « à faire » ne sont pas encore considérés comme implémentés.

## 1. Résumé du microservice

`identity-service` est une API REST Spring Boot qui centralise les comptes utilisateurs, l'authentification et le processus KYC (vérification de l'identité). Elle écoute sur le port **8081** et utilise PostgreSQL.

Technologies relevées dans le projet : Java 21, Spring Boot 3.4.5, Spring Security, Spring Data JPA, PostgreSQL, Flyway, JJWT, Lombok, Maven et Docker Compose.

Ce service n'inclut pas d'interface web, de traitement de paiements ni de gestion de comptes bancaires. Il fournit une identité et un jeton que d'autres services peuvent vérifier.

## 2. Ce qui est déjà fait

- **Inscription** via `POST /users/register` : validation des champs, détection d'un courriel déjà utilisé, normalisation de l'adresse, mot de passe haché avec BCrypt, création du compte et du profil.
- **Connexion** via `POST /users/login` : vérification du mot de passe BCrypt et émission d'un JWT signé avec RSA/RS256. Le jeton contient l'identifiant utilisateur, le rôle et le statut; son sujet est le courriel. La durée actuelle est de 15 minutes.
- **Authentification stateless** : le filtre JWT vérifie la signature et l'expiration, recharge le compte en base et crée l'autorité Spring Security.
- **Profil courant** via `GET /users/me`, protégé par JWT.
- **KYC** : dépôt d'un justificatif, consultation de ses propres justificatifs et actions d'administration pour lister les dossiers en attente, valider ou rejeter un dossier.
- **Publication JWKS** via `GET /.well-known/jwks.json`, pour que des services consommateurs puissent vérifier les JWT avec la clé publique.
- **Gestion des erreurs** centralisée pour les validations, les conflits de courriel, les identifiants invalides et les ressources introuvables prises en charge.
- **Persistance** PostgreSQL avec les tables `users`, `profiles` et `kyc_records`. Flyway fournit les migrations V1 et V2; Hibernate est réglé pour valider le schéma plutôt que le modifier.
- **Exécution locale et conteneurisée** : wrapper Maven, Dockerfile multi-étape, Compose pour l'API et PostgreSQL, volume local pour les fichiers KYC.
- **CI/CD de sécurité** dans `.github/workflows/devsecops-pipeline.yml` : analyse de secrets, Semgrep, tests/build Maven, Trivy, construction d'image et scan ZAP. Les rapports sont publiés comme artefacts.

## 3. Routes présentes

| Méthode | Route | Accès configuré | Fonction |
|---|---|---|---|
| `POST` | `/users/register` | Public | Créer un utilisateur et son profil |
| `POST` | `/users/login` | Public | Vérifier les identifiants et obtenir un JWT |
| `GET` | `/users/me` | Utilisateur authentifié | Lire le profil courant |
| `POST` | `/kyc/upload` | Utilisateur authentifié | Déposer un justificatif multipart |
| `GET` | `/kyc/documents` | Utilisateur authentifié | Lister les justificatifs du compte courant |
| `GET` | `/kyc/admin/pending` | `ROLE_ADMIN` | Lister les dossiers en attente |
| `PUT` | `/kyc/admin/documents/{id}/verify` | `ROLE_ADMIN` | Valider un justificatif |
| `PUT` | `/kyc/admin/documents/{id}/reject` | `ROLE_ADMIN` | Rejeter un justificatif avec motif |
| `GET` | `/.well-known/jwks.json` | Public | Publier la clé publique de vérification |

Pour les routes privées, envoyer `Authorization: Bearer <JWT>`. L'inscription accepte `email`, `password`, `firstName`, `lastName`, et éventuellement `dateOfBirth`, `address`, `phoneNumber`. Le mot de passe doit comporter au moins 8 caractères. Pour le dépôt KYC, envoyer un formulaire multipart avec `documentType` (`PASSPORT`, `NATIONAL_ID` ou `DRIVING_LICENSE`), `documentNumber` et `file`.

## 4. Lancer et vérifier le projet

### Prérequis

- Java 21 si l'application est lancée depuis Maven.
- Docker Desktop si PostgreSQL ou l'application sont lancés avec Compose.
- PowerShell ouvert à la racine du dépôt.

### Option A — lancer tout avec Docker Compose

```powershell
docker compose up --build -d
docker compose ps
docker compose logs -f identity-service
```

Le service devrait répondre sur `http://localhost:8081` et PostgreSQL sur le port `5432`. Compose attend que PostgreSQL soit prêt avant de lancer l'API. Pour arrêter les conteneurs sans supprimer les données :

```powershell
docker compose down
```

Ne pas ajouter `-v` si les données locales doivent être conservées.

### Option B — lancer seulement PostgreSQL avec Docker

```powershell
docker compose up -d postgres-db
.\mvnw.cmd spring-boot:run
```

### Vérifications fonctionnelles manuelles

1. Vérifier la santé de l'application : `http://localhost:8081/actuator/health`.
2. Créer un utilisateur avec `POST /users/register`.
3. Se connecter avec ce même courriel et mot de passe via `POST /users/login`; conserver `accessToken`.
4. Appeler `GET /users/me` en ajoutant l'en-tête `Authorization: Bearer <accessToken>`.
5. Vérifier que `GET /.well-known/jwks.json` retourne un objet avec `keys`.
6. Tester le dépôt KYC avec un utilisateur authentifié.
7. Tester les routes `/kyc/admin/**` avec un compte administrateur provisionné de façon contrôlée. L'inscription publique crée uniquement un compte utilisateur normal.

La commande `.\mvnw.cmd test` lance les tests présents. Actuellement, le seul test applicatif identifié est un test de démarrage du contexte Spring; il ne couvre pas à lui seul ces parcours.

## 5. Ce qu'il reste à faire — ordre recommandé

Les étapes ci-dessous sont ordonnées pour traiter d'abord les risques et les défauts de contrat avant d'étendre les fonctionnalités.

### Étape 1 — Fixer le contrat fonctionnel des statuts utilisateur et KYC

**Constat actuel :** l'inscription crée directement un utilisateur `ACTIVE`; `PENDING_KYC` existe mais n'est pas utilisé à l'inscription. La connexion et le filtre ne refusent pas explicitement les comptes suspendus ou en attente KYC.

1. Décider avec les règles métier si un nouveau compte doit être `PENDING_KYC` ou `ACTIVE`.
2. Définir précisément ce que signifient `ACTIVE`, `PENDING_KYC` et `SUSPENDED`, et quelles transitions KYC font évoluer le statut du compte.
3. Appliquer la règle dans `UserService`, `AuthService` et/ou le filtre d'authentification, de façon cohérente.
4. Ajouter des tests prouvant qu'un compte suspendu ou en attente n'accède pas aux fonctions qui doivent lui être interdites.
5. Vérifier les réponses HTTP et messages renvoyés au client pour chaque statut.

**Terminé quand :** les transitions sont documentées et testées, et aucun compte bloqué ne peut utiliser les routes selon la règle retenue.

### Étape 2 — Rendre les clés JWT persistantes et la configuration effective

**Constat actuel :** la paire RSA est générée en mémoire à chaque démarrage; après redémarrage, la clé JWKS change et les jetons émis précédemment ne peuvent plus être vérifiés. La durée JWT est codée en dur et les propriétés JWT du YAML ne pilotent pas le `JwtService`.

1. **Choisir le mécanisme AWS.** Pour une première version, stocker une clé privée PKCS#8 dans AWS Secrets Manager, chiffrée avec une clé KMS, et permettre au seul rôle IAM de la tâche ECS de la lire. Pour une protection renforcée, utiliser une clé asymétrique AWS KMS non exportable et adapter le signataire JWT pour appeler `kms:Sign`; le rôle de la tâche ne doit accéder qu'à la clé concernée.
2. **Créer et identifier la clé.** Générer/importer une paire RSA adaptée à RS256 (ou créer une clé de signature RSA dans KMS). Attribuer un `kid` stable et unique à cette version de clé. Ne jamais placer la clé privée dans Git, l'image Docker, le dépôt d'artefacts ou les logs.
3. **Charger la clé dans l'application.** Remplacer la génération dans `RsaKeyConfig` par un chargement depuis le fournisseur choisi. Avec KMS, faire signer le contenu exact `base64url(header) + "." + base64url(payload)` avec RSASSA-PKCS1-v1_5-SHA-256; ne pas signer un JWT complet ou un autre encodage. Récupérer la clé publique depuis KMS et la mettre en cache.
4. **Publier une JWKS conforme.** Publier la clé publique correspondant au `kid` actif. Encoder le modulus `n` et l'exposant `e` comme des entiers non signés en Base64 URL sans padding; ajouter un test interopérable avec une bibliothèque JWT/JWKS indépendante.
5. **Rendre la configuration explicite.** Injecter l'identifiant/ARN de clé, la durée de vie du JWT et les paramètres nécessaires via une configuration validée. Supprimer la propriété HS256 inutilisée du YAML actuel : le service utilise RSA/RS256, pas cette propriété.
6. **Déployer et vérifier.** Après déploiement, émettre un JWT, redémarrer/remplacer une tâche ECS et vérifier que le même `kid` et la même clé JWKS permettent toujours de valider le jeton. Confirmer aussi qu'un rôle IAM non autorisé ne peut ni lire la clé, ni signer.
7. **Organiser la rotation.** Créer une nouvelle clé avec un nouveau `kid`, publier temporairement l'ancienne et la nouvelle clé publique dans la JWKS, puis faire signer uniquement avec la nouvelle. Attendre au minimum la durée maximale des JWT plus une marge d'horloge et de cache des consommateurs avant de retirer l'ancienne clé. Prévoir une révocation d'urgence.

**Terminé quand :** la clé reste stable entre deux démarrages, la durée annoncée correspond à la durée réelle du JWT et une rotation est vérifiable.

### Étape 3 — Sécuriser le provisionnement des administrateurs et l'accès

**Constat actuel :** les routes KYC d'administration sont limitées à `ROLE_ADMIN`, mais l'inscription publique ne crée que des `ROLE_USER`. Il faut donc définir un moyen contrôlé de créer le premier administrateur, sans ajouter un choix de rôle à l'inscription publique.

1. Choisir un mécanisme d'amorçage contrôlé (processus d'exploitation sécurisé, migration ou commande administrative restreinte).
2. Ne pas permettre au client public de transmettre un rôle administrateur.
3. Ajouter des tests `401` sans authentification, `403` pour un utilisateur normal et succès pour un administrateur sur chaque route d'administration.
4. Réduire les routes Actuator accessibles publiquement aux seuls endpoints strictement nécessaires.
5. Configurer CORS pour les origines réellement utilisées si un navigateur doit appeler l'API; actuellement CORS est désactivé.
6. Ajouter une protection contre les tentatives répétées de connexion (limitation de débit/verrouillage selon la politique retenue).

**Terminé quand :** le compte administrateur est provisionné sans chemin public d'élévation de privilèges et les tests prouvent la séparation des rôles.

### Étape 4 — Fiabiliser et protéger les documents KYC

**Constat actuel :** le code vérifie l'extension du fichier, mais pas sa taille, son contenu réel ou son type MIME réel. Les documents sont écrits sous `uploads/kyc`; le chemin est renvoyé dans la réponse. Une transaction SQL ne supprime pas automatiquement un fichier copié si l'enregistrement en base échoue. Une pièce inexistante déclenche actuellement `IllegalArgumentException`, alors que la réponse 404 est prévue pour `ResourceNotFoundException`.

1. Définir les formats, la taille maximale, les règles de ré-envoi et la durée de conservation autorisés.
2. Valider le contenu réel du fichier (et pas seulement son nom/extension), rejeter les fichiers invalides et retourner une erreur client cohérente.
3. Conserver les fichiers hors d'un répertoire servi publiquement; définir chiffrement, droits d'accès, sauvegarde et suppression.
4. Remplacer l'exposition du chemin interne par un identifiant ou une URL contrôlée. Si le téléchargement est nécessaire, ajouter une route qui vérifie le propriétaire ou le rôle administrateur.
5. Gérer le nettoyage du fichier si l'écriture SQL échoue, et documenter le comportement en cas d'échec partiel.
6. Remplacer les exceptions génériques pour les dossiers introuvables par `ResourceNotFoundException` afin d'obtenir un HTTP 404.
7. Vérifier les transitions autorisées (par exemple ne pas valider deux fois un dossier déjà rejeté sans règle métier explicite).
8. Tester les fichiers vides, trop volumineux, aux extensions trompeuses, les accès non autorisés et les erreurs de stockage.

**Terminé quand :** les documents ne sont accessibles qu'aux personnes autorisées, les erreurs de dépôt sont maîtrisées et le cycle de vie des fichiers est couvert par des tests.

### Étape 5 — Étendre les tests automatisés

Le test existant vérifie principalement que le contexte démarre. Avant toute mise en production, ajouter des tests ciblés :

1. **Services :** inscription normale, courriel dupliqué, mot de passe BCrypt, connexion invalide, statuts de compte et transitions KYC.
2. **Contrôleurs :** formats invalides, champs requis, codes `201`, `200`, `400`, `401`, `403`, `404` et `409`.
3. **Sécurité :** JWT absent, malformé, expiré ou signé par une mauvaise clé; accès utilisateur versus administrateur.
4. **Base de données :** migrations Flyway sur PostgreSQL vierge et mise à niveau depuis la version précédente.
5. **KYC :** dépôt, validation, rejet, limites de fichier et nettoyage en cas d'échec.
6. Lancer les tests sur chaque modification et dans la CI; le build Docker actuel utilise `-DskipTests`, il ne remplace donc pas les tests Maven.

**Terminé quand :** les scénarios principaux sont reproductibles automatiquement et les tests échouent lorsqu'une règle d'accès ou une transition métier est cassée.

### Étape 6 — Préparer l'application pour la production (sans dépendre encore d'AWS)

Cette étape concerne le **code et sa configuration applicative**, en restant portable. Le choix et la création des ressources AWS viendront dans une phase distincte, après avoir rendu l'application prête à être déployée.

1. Retirer les valeurs de démonstration des configurations applicatives et définir un contrat de configuration externe pour les secrets et paramètres; ne pas choisir ni coder maintenant un fournisseur AWS.
2. Séparer les profils local, test et production; désactiver l'affichage SQL et les réglages de diagnostic en production.
3. Rendre les clés de signature persistantes via un fournisseur de clés interchangeable, avec rotation et identifiants `kid`; le mode local de développement ne doit pas être pris pour un mode de production.
4. Définir une interface de stockage KYC durable et sécurisée, distincte du système de fichiers local, puis garder le stockage local comme implémentation de développement/test.
5. Configurer l'application pour TLS derrière un proxy de confiance, des limites de requêtes et des en-têtes de proxy documentés; l'infrastructure TLS sera créée ultérieurement.
6. Définir logs sans mots de passe, JWT ou données KYC, ainsi que les métriques, alertes et politiques de conservation nécessaires.
7. Restreindre Actuator, appliquer les permissions minimales au conteneur et établir une stratégie de mises à jour et de retour arrière.
8. Vérifier ces choix dans un environnement de préproduction avant de réaliser le déploiement sur le fournisseur retenu.

### Étape 7 — Rendre la CI réellement bloquante et documenter l'API

**Constat actuel :** un pipeline de sécurité existe, mais plusieurs commandes de scan utilisent `|| true` ou `--exit-code 0`. Les rapports peuvent être produits sans faire échouer le pipeline lorsqu'un problème est détecté.

1. Décider quelles catégories et sévérités doivent bloquer une PR.
2. Faire échouer le job sur ces seuils, tout en conservant une procédure explicite pour les exceptions justifiées.
3. Vérifier que les rapports sont produits même si un job précédent échoue et qu'ils sont accessibles aux personnes autorisées.
4. Ajouter une documentation OpenAPI/Swagger du contrat, des corps de requête, des réponses et des codes d'erreur.
5. Décrire le processus de versionnement et de dépréciation des routes.

**Terminé quand :** la CI bloque effectivement les problèmes au seuil défini et qu'un consommateur peut utiliser l'API sans lire le code Java.

### Étape 8 — Compléter les parcours de compte selon le besoin produit

Les routes observées n'exposent actuellement pas de parcours de vérification d'adresse e-mail, de réinitialisation de mot de passe, de révocation/renouvellement de jeton ou de suppression de compte.

1. Confirmer lesquels sont requis par le produit et les obligations applicables.
2. Pour chaque parcours retenu, définir expiration, authentification, anti-abus, audit et réponses d'erreur.
3. Implémenter les fonctionnalités sans stocker de jetons de réinitialisation ou secrets en clair.
4. Ajouter les tests fonctionnels, sécurité et expiration avant de publier les nouvelles routes.

Ne pas démarrer ces fonctionnalités avant d'avoir défini les règles métier et le niveau de sécurité attendu.

## 6. Dépendance avec les autres microservices

Les consommateurs peuvent valider les JWT avec `/.well-known/jwks.json`; ils ne doivent pas accéder directement à la base de données Identity. Le contrat actuel met l'adresse e-mail dans `sub` et l'UUID dans le claim `userId`. Tout service consommateur doit utiliser le claim adapté et ne pas convertir `sub` en UUID. La persistance des clés RSA est nécessaire pour que cette intégration reste fonctionnelle après un redémarrage.

## 7. Ordre de travail conseillé

Pour avancer sans multiplier les changements simultanés :

1. Décider et tester les règles de statut/KYC.
2. Stabiliser les clés JWT et leur configuration.
3. Sécuriser le provisionnement admin et tester les autorisations.
4. Sécuriser le stockage, la validation et les erreurs de fichiers KYC.
5. Compléter les tests unitaires et d'intégration.
6. Durcir la configuration, les secrets et le déploiement.
7. Rendre les scans CI bloquants selon des seuils décidés.
8. Ajouter OpenAPI puis les parcours fonctionnels demandés par le produit.

**Le projet constitue une base fonctionnelle de développement, mais ne doit pas être considéré prêt pour la production bancaire tant que les clés, les rôles, les documents KYC, les tests et la configuration d'environnement n'ont pas été durcis et vérifiés.**

## 8. Inspection de préparation de l'application à la production

### Portée et conclusion

Cette inspection est une revue statique des fichiers présents dans ce dépôt : Java, configuration Spring, migrations SQL, Docker, Compose, tests et workflow GitHub Actions. Aucun environnement déployé, journal d'exécution ou test de pénétration n'a été inspecté. L'objectif de cette phase est de rendre le **microservice lui-même** robuste et déployable en production, sans implémenter de services ou de ressources propres à AWS. L'infrastructure AWS sera traitée ensuite.

**Conclusion : le code actuel n'est pas encore prêt pour un déploiement de production, quel que soit l'hébergeur.** Les points suivants sont des travaux prioritaires avant d'exposer le service.

### Résultats de l'inspection

| Priorité | Constat vérifié dans le dépôt | Travail à faire dans le microservice |
|---|---|---|
| Bloquant | `RsaKeyConfig` crée une nouvelle paire RSA en mémoire à chaque démarrage. Les instances et redémarrages peuvent donc ne pas partager la même clé; les JWT précédents deviennent invérifiables. | Concevoir une configuration/fournisseur de clés persistant et interchangeable, appliquer la rotation `kid` et tester les redémarrages ainsi que plusieurs instances, sans intégrer maintenant un SDK AWS. |
| Bloquant | `JwksController` encode directement `BigInteger.toByteArray()` pour `n` et `e`. Cette représentation signée peut inclure un octet `00` initial; JWKS attend un entier non signé, ce qui peut faire échouer des bibliothèques clientes. | Encoder les entiers conformément au format JWK, puis tester émission et validation avec une bibliothèque indépendante. |
| Bloquant | Les fichiers KYC sont conservés dans `uploads/kyc` sur le système de fichiers local du conteneur et le chemin interne est renvoyé dans les réponses. Ce stockage n'est ni durable ni partagé entre plusieurs instances et le chemin n'est pas une URL de téléchargement contrôlée. | Concevoir une abstraction de stockage avec implémentation locale pour développement/test et stockage durable injectable pour production. Retourner un identifiant plutôt qu'un chemin local et autoriser chaque consultation. |
| Bloquant | Des valeurs de développement sont présentes dans `application.yaml` et `docker-compose.yml` (mot de passe PostgreSQL par défaut et propriété de clé HS256 statique). La propriété HS256 n'est pas utilisée par le code, qui signe actuellement en RS256, mais sa présence rend le contrat de sécurité ambigu. | Supprimer la propriété JWT inutilisée et éviter les secrets par défaut dans les profils de production. Les secrets devront être fournis par un mécanisme adapté à l'environnement, choisi dans la phase de déploiement. |
| Élevée | Compose publie PostgreSQL sur `5432`; cela convient au développement local, mais ne constitue pas une configuration de production. | Séparer la configuration locale de la configuration de production, activer TLS côté client pour la base et documenter les exigences réseau, de sauvegarde et de restauration sans créer maintenant de ressources cloud. |
| Élevée | Le filtre ne bloque pas explicitement les comptes `SUSPENDED` ou `PENDING_KYC`; l'inscription les marque actuellement `ACTIVE`. | Définir les règles métier puis les appliquer à la connexion et aux requêtes authentifiées; couvrir les scénarios par tests. |
| Élevée | Les routes admin exigent `ROLE_ADMIN`, mais l'inscription publique ne crée que `ROLE_USER` et aucun mécanisme contrôlé de provisionnement admin n'est fourni. | Créer un processus d'amorçage admin réservé à l'exploitation; ne jamais accepter un rôle admin dans l'API publique; tester `401`, `403` et l'accès admin. |
| Élevée | Le numéro de document et le chemin local sont inclus dans `KycDocumentResponse`; les routes de revue admin retournent des dossiers KYC. L'extension du fichier est contrôlée, mais pas son contenu réel ni une politique explicite de taille/rétention. | Minimiser les données de réponse, valider contenu et taille, protéger et auditer les accès, et définir suppression/rétention selon les exigences applicables. Le chiffrement concret sera configuré avec le fournisseur retenu. |
| Élevée | `SecurityConfig` autorise publiquement `/actuator/**`; `application.yaml` active `show-sql`. | N'exposer que les endpoints de santé nécessaires, désactiver le SQL détaillé en production et vérifier qu'aucune donnée sensible ne se retrouve dans les logs. |
| Élevée | Plusieurs scans CI sont configurés pour ne pas bloquer le pipeline (`|| true`, `--exit-code 0`). Le Dockerfile compile avec `-DskipTests`; il existe seulement un test de démarrage applicatif. | Définir des seuils qui font échouer la PR, compléter les tests de sécurité/métier et garder un build/test distinct du build d'image. |
| À corriger | `UserService` vérifie le doublon avant de normaliser le courriel; `AuthService` ne normalise pas le courriel à la connexion. Les variantes de casse/espaces peuvent donc provoquer un échec incohérent, voire une erreur base au lieu d'un conflit. | Normaliser de façon identique à l'inscription, à la connexion et aux recherches; tester les variantes et la concurrence sur les inscriptions. |
| À corriger | `KycService` signale un document introuvable avec `IllegalArgumentException`, alors que la réponse 404 est prévue pour `ResourceNotFoundException`. Le filtre JWT intercepte largement les exceptions d'analyse sans distinguer les erreurs de jeton des erreurs inattendues. | Employer des exceptions métier/HTTP explicites, éviter les 500 pour les erreurs utilisateur et ne pas masquer les défaillances inattendues. |
| À corriger | Le dépôt ne contient pas de configuration CORS; CORS est explicitement désactivé. Les analyses de sécurité génèrent des rapports mais peuvent réussir même avec des alertes. | Définir uniquement les origines nécessaires si un navigateur appelle l'API; fixer une politique de seuils et d'exceptions pour les scans. |

### Phase ultérieure — infrastructure AWS (hors du travail applicatif actuel)

Une fois les points de préparation du microservice traités, l'infrastructure pourra être conçue séparément. ECS Fargate est une option à évaluer, pas un choix déjà acté :

1. Construire et tester l'image dans la CI, la scanner, puis la publier dans ECR avec une version immuable.
2. Déployer l'API dans des tâches ECS en sous-réseaux privés, derrière un Application Load Balancer; terminer TLS avec un certificat ACM et limiter les règles réseau au strict nécessaire.
3. Utiliser RDS PostgreSQL en sous-réseaux privés, avec chiffrement KMS, TLS, sauvegardes et rétention définies. Autoriser la base uniquement depuis le groupe de sécurité des tâches.
4. Accorder au rôle IAM de la tâche uniquement les actions requises : lire le secret DB ou obtenir un jeton d'authentification DB, accéder au bucket KYC et, si retenu, signer avec la clé KMS. Séparer ce rôle du rôle d'exécution ECS.
5. Créer un bucket S3 privé pour les documents avec blocage d'accès public, chiffrement KMS, journaux d'accès et politique de cycle de vie. Autoriser seulement la tâche à lire/écrire les objets nécessaires.
6. Envoyer les logs vers CloudWatch sans JWT, mots de passe, numéros de documents ni contenu de fichier; configurer métriques, alertes, health checks et alarmes sur erreurs, latence, saturation et échecs de tâches.
7. Exécuter les migrations Flyway selon une procédure de déploiement contrôlée; vérifier la stratégie de sauvegarde/restauration de RDS et tester le retour arrière.
8. Définir l'infrastructure dans Terraform, CloudFormation ou CDK, puis déployer d'abord en préproduction. Vérifier les permissions IAM, les règles réseau, la restauration, la montée en charge et le remplacement d'une tâche avant la production.

Ces étapes AWS sont volontairement différées. Le choix exact (ECS ou EKS, paramètres de disponibilité, région, rétention, conformité et reprise après sinistre) dépendra des exigences du produit et de l'organisation. **Pour le travail actuel, on se concentre sur le code, ses contrats, sa sécurité, sa configuration portable et ses tests; on ne crée pas encore les ressources AWS.**
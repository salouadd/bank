# Identity Service — plan de réalisation fonctionnelle et sécurisée

## Objectif

Ce document est le plan de travail pour faire évoluer le microservice Identity Service vers une application fonctionnelle, testable et conçue pour une future mise en production. Le travail décrit ici porte d'abord sur le **code, les contrats API, les règles métier, les tests et la configuration portable**. La création de ressources AWS et le déploiement AWS sont reportés à une phase distincte.

La sécurité est abordée avec **OWASP Top 10:2021 — A04:2021, Insecure Design (défauts de conception)** comme fil conducteur. Cela ne signifie pas que traiter A04 suffit à rendre l'application conforme à tout OWASP Top 10, ni à une réglementation bancaire. C'est une démarche de conception et de vérification des risques métier avant et pendant l'implémentation.

## 1. État de départ observé dans le dépôt

Le projet contient une API Spring Boot/Java 21 avec PostgreSQL, Flyway, Spring Security, JPA, BCrypt, JWT RS256/JWKS, contrôleurs d'utilisateur et KYC, Docker Compose et un workflow CI de scans.

Fonctionnalités déjà présentes :

- inscription et connexion ;
- lecture du profil de l'utilisateur authentifié ;
- dépôt et liste des justificatifs KYC ;
- routes d'administration pour lister, valider ou rejeter des justificatifs ;
- création et validation de JWT, publication d'une JWKS ;
- migrations PostgreSQL et gestion centralisée de certaines erreurs.

Les éléments présents ne sont pas encore une preuve de fonctionnement complet ou de préparation à la production. Le test applicatif identifié vérifie seulement le démarrage du contexte. La paire RSA est régénérée au démarrage, les fichiers KYC sont stockés sur disque local, certaines règles de statut et erreurs métier sont incomplètes, et les scans du workflow ne bloquent pas toujours sur une détection.

## 2. OWASP A04:2021 appliqué à ce microservice

OWASP A04 traite principalement des **risques créés par des exigences absentes, des règles métier incohérentes et des contrôles de sécurité non prévus dès la conception**. Ce n'est pas seulement une question de corriger une ligne de code ou d'activer un scanner.

Pour chaque parcours, l'équipe doit :

1. définir les acteurs, les données, les états et les règles métier attendues ;
2. décrire les scénarios d'abus et les conséquences, y compris les actions répétées, concurrentes ou effectuées dans le mauvais ordre ;
3. spécifier les contrôles d'accès, limites, erreurs et événements d'audit associés ;
4. traduire ces règles en tests positifs et négatifs, puis conserver les décisions de conception à jour.

### Scénarios d'abus à traiter explicitement

| Parcours | Question de conception / scénario d'abus | Contrôle et preuve attendus |
|---|---|---|
| Inscription | Un client choisit-il lui-même son rôle, crée-t-il plusieurs comptes ou contourne-t-il l'unicité en variant la casse ou les espaces du courriel ? | Le rôle est déterminé côté serveur; adresse normalisée de manière cohérente; unicité garantie en base; tests de doublon et de concurrence. |
| Connexion | Un attaquant peut-il énumérer les comptes, essayer des mots de passe sans limite ou continuer à utiliser un compte suspendu ? | Message d'échec non révélateur; limites anti-automatisation conçues; politique des statuts appliquée; tests de flux et de limites. |
| JWT | Un jeton survit-il à un redémarrage, peut-il être vérifié par un autre service, ou reste-t-il valable après suspension/révocation ? | Contrat de claims documenté, clés stables, durée et révocation décidées, tests de signature, expiration, rotation et comportement après changement de statut. |
| KYC | Un utilisateur accède-t-il au document d'un autre, soumet-il un fichier déguisé ou fait-il valider/rejeter plusieurs fois un dossier dans un ordre invalide ? | Autorisation par propriétaire ou rôle à chaque accès, validation du contenu, transitions d'état définies et tests d'accès croisé/de transitions. |
| Administration | Comment le premier administrateur est-il créé, et comment éviter l'élévation de privilège par une route publique ou une configuration initiale oubliée ? | Provisionnement contrôlé et auditable; aucune création publique de rôle élevé; tests `401`/`403` et procédure d'exploitation documentée. |
| Données personnelles | Les erreurs, logs ou DTO exposent-ils mot de passe, jeton, numéro de document, chemin interne ou données KYC excessives ? | Réponses minimales, journalisation expurgée, accès audité et politique de conservation/suppression définie. |
| Disponibilité | Des inscriptions, connexions, recherches ou dépôts de gros fichiers peuvent-ils saturer le service ? | Limites de taille et de fréquence définies; limites applicatives explicites; tests de charge/abus et réponses contrôlées. |

### Artefacts de conception à maintenir

Avant chaque évolution sensible, mettre à jour :

- un diagramme de flux de données avec les frontières de confiance (client, Identity Service, base, stockage KYC et futurs consommateurs) ;
- une matrice acteur/action/ressource (anonyme, utilisateur, administrateur, service consommateur) ;
- les machines à états du compte et du dossier KYC ;
- les exigences de sécurité et les scénarios d'abus ci-dessus ;
- les décisions d'architecture sur JWT, clés, stockage, rétention, limites et erreurs.

Le workflow Semgrep configuré avec une règle OWASP ne valide pas à lui seul ces artefacts, les règles métier, ni la conformité OWASP. Il doit compléter, et non remplacer, la revue de conception et les tests.

## 3. Plan d'implémentation par étapes

Chaque étape doit être livrée avec les tests correspondant à ses critères d'acceptation. Ne pas passer à l'étape suivante en masquant un échec de test ou une règle métier encore indécise.

### Étape 0 — Établir le comportement actuel et le contrat de l'API

1. Démarrer PostgreSQL et l'API avec Docker Compose, puis exécuter `.\mvnw.cmd test`.
2. Documenter les requêtes/réponses actuellement fournies pour chaque route, y compris les erreurs et les rôles nécessaires.
3. Vérifier les migrations Flyway sur une base vide et sur une base contenant les migrations précédentes.
4. Enregistrer les règles actuelles, les écarts et les questions métier ouvertes; ne pas transformer une hypothèse en comportement attendu.

**Acceptation :** le projet démarre de façon reproductible; un contrat initial décrit les endpoints existants et le comportement est distingué des fonctionnalités souhaitées.

#### Résultat de la première vérification (17 novembre 2026)

- `.\mvnw.cmd test` **réussit** : 1 test exécuté (`contextLoads`), 0 échec, 0 erreur. Cela vérifie le démarrage du contexte, pas les parcours HTTP ni les règles métier.
- Le test s'est connecté à la base locale `identity_db` sur PostgreSQL **18.6**. Flyway a validé les deux migrations, et le schéma présent était déjà à la version **2**. C'est une vérification sur une base existante : la migration sur base vierge et le chemin de mise à niveau depuis V1 restent à tester.
- La sortie Flyway avertit que PostgreSQL 18.6 est plus récent que les versions testées par cette version de Flyway (jusqu'à 17).
- PostgreSQL local répond sur le port 5432, mais la commande `docker` n'est pas disponible dans cet environnement; le démarrage par Docker Compose n'a donc pas été vérifié.
- Au démarrage, Spring avertit que `open-in-view` est activé et qu'un utilisateur/mot de passe de développement automatique est configuré. Ces points devront être examinés et désactivés/configurés explicitement avant production.

#### Contrat initial observé dans le code

| Méthode et route | Entrée observée | Réponse prévue par le contrôleur | Accès configuré |
|---|---|---|---|
| `POST /users/register` | JSON : `email`, `password` (8 à 72 caractères et au plus 72 octets UTF-8), `firstName`, `lastName`; `dateOfBirth`, `address`, `phoneNumber` optionnels | `201`; `UserResponse` avec `id`, `email`, `role`, `status`, `firstName`, `lastName`, `createdAt`. Validation `400`, courriel déjà utilisé `409`. | Public |
| `POST /users/login` | JSON : `email`, `password` | `200`; `AuthResponse` avec `accessToken`, `tokenType`, `expiresIn`. Identifiants incorrects `401`; validation `400`. | Public |
| `GET /users/me` | En-tête Bearer JWT | `200`; `UserResponse`. | Authentifié |
| `POST /kyc/upload` | Multipart : `documentType`, `documentNumber` (non vide, 100 caractères maximum), `file`; extensions acceptées par le code : PDF, JPG/JPEG, PNG | `201`; `KycDocumentResponse` avec identifiant, utilisateur, type, numéro masqué, statut et date de dépôt. Le type et le contenu du fichier ne sont pas réellement vérifiés par l'extension seule. | Authentifié |
| `GET /kyc/documents` | Aucun paramètre | `200`; liste des documents de l'utilisateur authentifié avec numéro masqué. | Authentifié |
| `GET /kyc/admin/pending` | Aucun paramètre | `200`; liste des documents `PENDING`. | `ROLE_ADMIN` |
| `PUT /kyc/admin/documents/{id}/verify` | UUID de document dans l'URL | `200`; document mis à jour. | `ROLE_ADMIN` |
| `PUT /kyc/admin/documents/{id}/reject` | UUID et JSON `rejectionReason` non vide | `200`; document mis à jour; validation `400`. | `ROLE_ADMIN` |
| `GET /.well-known/jwks.json` | Aucun paramètre | `200`; clé publique RSA dans l'enveloppe JWKS `keys`. | Public |
| `GET /actuator/health` | Aucun paramètre | Endpoint de santé Spring Actuator. | Public selon la règle `/actuator/**`; un endpoint Actuator a été observé comme exposé au démarrage du test. |

Les routes authentifiées utilisent `Authorization: Bearer <JWT>`. L'inscription attribue le rôle `ROLE_USER` et le statut `PENDING_KYC`. Les opérations d'administration ne disposent pas encore d'un processus de création initiale d'administrateur dans le dépôt.

> **Mise à jour après l'étape 1 et l'étape 2 :** l'inscription attribue maintenant `PENDING_KYC` (et non `ACTIVE`) et les réponses KYC exposent `documentNumberMasked`, sans le numéro complet ni le chemin local. La règle actuelle d'activation après la validation d'un document reste provisoire.

Le format d'erreur commun prévu contient `status`, `error`, `message`, `path`, `timestamp` et éventuellement `validationErrors`. Le code gère explicitement les erreurs de validation (`400`), de courriel dupliqué (`409`), d'identifiants invalides (`401`), de transition KYC invalide (`409`) et de ressource absente (`404`). Les formats/paramètres incorrects et les extensions KYC refusées répondent `400`; le dépassement de taille de fichier répond `413`. Les exceptions inattendues retombent sur une réponse générique `500`.

**État de l'étape 0 : partiellement réalisée.** Les tests de contexte sont passés et le contrat statique a été relevé. Il reste à exécuter Compose, à tester les migrations sur une base vierge et depuis une base V1, puis à vérifier les parcours HTTP réels. L'absence de Docker est un blocage d'environnement pour ces vérifications; elle n'est pas considérée comme un résultat positif.

### Étape 1 — Décider les règles métier (OWASP A04)

#### Proposition de règles métier — à confirmer avant de les implémenter

Ces décisions constituent une proposition de départ pour un service d'identité bancaire. Elles ne sont pas encore appliquées au code et ne remplacent pas la validation des exigences produit, conformité et opérations.

| Sujet | Proposition de règle | Conséquence/API à prévoir |
|---|---|---|
| Inscription | Toute inscription publique crée `ROLE_USER` et `PENDING_KYC`. Le client ne peut jamais choisir son rôle ou son statut. | `POST /users/register` renvoie un compte en attente KYC; l'email est nettoyé (espaces périphériques) et normalisé avant vérification d'unicité et stockage. |
| Connexion avant KYC | Un compte `PENDING_KYC` peut s'authentifier afin de consulter son profil et déposer/consulter ses justificatifs; il ne peut pas accéder à des opérations bancaires ou autres fonctions réservées à un compte actif. | Le JWT peut être émis avec `status=PENDING_KYC`. Identity et chaque service consommateur doivent vérifier le statut, pas seulement la validité de la signature et le rôle. |
| Activation KYC | L'utilisateur devient `ACTIVE` uniquement lorsque tous les contrôles/documents obligatoires définis par la politique produit sont vérifiés par un administrateur autorisé. Un seul document vérifié ne suffit pas implicitement si plusieurs justificatifs sont requis. | La liste des justificatifs obligatoires doit être décidée par le produit/conformité; l'activation doit être atomique et testée. |
| Rejet et nouveau dépôt | Le rejet d'un document ne supprime pas l'historique. L'utilisateur peut déposer une nouvelle version; le nouveau dépôt crée un nouvel enregistrement `PENDING`. L'utilisateur reste en attente tant que le KYC requis n'est pas achevé. | Conserver l'ancien dossier et son motif; éviter d'écraser la preuve de décision précédente. |
| Suspension | Un compte `SUSPENDED` ne peut ni obtenir un nouveau JWT ni continuer à utiliser un JWT existant pour des routes protégées. La vérification du statut en base à chaque requête privée est cohérente avec le filtre qui recharge déjà l'utilisateur. | Échec de connexion générique `401`; requête avec un jeton antérieur refusée après suspension. Décider si la réponse d'une session suspendue est `401` ou `403` et la documenter. |
| Transitions de compte | `PENDING_KYC -> ACTIVE` seulement après KYC complet; tout état non suspendu peut être placé en `SUSPENDED` par un administrateur habilité; la réactivation exige une action administrative auditée et une justification. Aucun passage public vers `ROLE_ADMIN` ou `ACTIVE`. | Toute transition invalide est refusée (`409` proposé); chaque changement d'état conserve l'acteur, la date et le motif. |
| Transitions KYC | `PENDING -> VERIFIED` ou `PENDING -> REJECTED` par un administrateur; un dossier terminal n'est pas modifié à nouveau. Une nouvelle soumission est un nouveau dossier. | Une décision répétée ou contradictoire est refusée (`409` proposé); l'action d'administration est auditée. |
| Rôles/admin | L'inscription publique produit seulement `ROLE_USER`. Le premier administrateur et les administrateurs suivants sont provisionnés par un processus d'exploitation contrôlé, avec accès limité et journal d'audit; aucun mot de passe admin par défaut. | Ne pas ajouter de champ `role` au DTO public. Le processus précis de provisionnement doit être décidé avant le déploiement. |
| Courriel | Normaliser de façon identique à l'inscription et à la connexion; rendre l'unicité insensible à la casse également au niveau base de données. Le changement de courriel, s'il est autorisé, exige une nouvelle vérification. | Migration d'unicité normalisée et parcours de vérification à prévoir; ne pas révéler si une adresse existe lors des parcours de récupération. |
| Mot de passe | Refuser les mots de passe compromis/faibles, autoriser les phrases de passe longues, ne pas imposer de règles arbitraires de composition ni tronquer silencieusement. La longueur exacte et l'éventuel MFA sont à valider selon la politique de sécurité. | Mettre à jour validation, messages d'erreur et tests; conserver BCrypt ou un hachage adaptatif approuvé avec paramètres calibrés. |
| Audit/confidentialité | Auditer inscription, décisions et changements de statut, provisionnement admin et accès administratif aux documents. Ne jamais journaliser mots de passe, JWT, clé privée, contenu KYC ou numéro de document complet. | Définir accès, intégrité, rétention et suppression des journaux; ne pas confondre journal applicatif et journal d'audit. |

#### Questions métier à confirmer

Avant de transformer la proposition en comportement, le responsable produit/conformité doit confirmer :

1. Quels justificatifs sont obligatoires pour activer un compte, et quels pays/types de documents sont pris en charge ?
2. L'adresse e-mail doit-elle être vérifiée avant le premier dépôt KYC ou seulement avant l'activation ?
3. Les clients en attente ou rejetés peuvent-ils se connecter et quelles routes exactes leur sont accessibles ?
4. Quel rôle peut suspendre/réactiver un compte, et quelle justification est obligatoire ?
5. Quelle est la durée légale/de service de conservation des documents rejetés, vérifiés et des journaux d'audit ?
6. Quelle politique de mot de passe, de MFA et de limitation des tentatives est requise ?
7. Quels statuts HTTP doivent distinguer compte suspendu, compte en attente et document inexistant, tout en évitant l'énumération de comptes ?

#### Première tranche d'implémentation réalisée

Sur la base de la proposition ci-dessus, le code applique maintenant le comportement provisoire suivant :

- une inscription crée un compte `ROLE_USER` avec le statut `PENDING_KYC` ;
- un compte `PENDING_KYC` peut obtenir un JWT afin d'accéder aux parcours actuellement disponibles de profil et KYC ;
- un compte `SUSPENDED` ne peut pas se connecter et ses anciens JWT ne créent plus d'authentification dans le filtre ;
- un administrateur ne peut décider que sur un dossier KYC `PENDING`; une décision répétée ou contradictoire renvoie `409`, un dossier absent renvoie `404` ;
- la validation d'un document KYC fait passer un utilisateur `PENDING_KYC` à `ACTIVE`; le rejet le laisse `PENDING_KYC` pour pouvoir soumettre un nouveau dossier.

**Hypothèse métier provisoire à confirmer :** cette première tranche active le compte après la validation d'un seul document. Le produit/conformité doit confirmer que cela suffit, ainsi que l'exigence de vérification du courriel, les accès autorisés pendant `PENDING_KYC` et les règles de réactivation d'un compte suspendu. Jusqu'à cette confirmation, cette logique ne doit pas être considérée comme la politique KYC définitive.

Des tests unitaires couvrent la création en attente KYC, l'authentification pending, le refus des comptes suspendus (y compris les anciens JWT), le refus des transitions KYC répétées et les documents inexistants. La suite Maven a été exécutée après cette tranche : **10 tests, 0 échec, 0 erreur**. Il reste à ajouter/exécuter les tests HTTP et la validation métier de la politique KYC avant de marquer l'étape 1 comme terminée.

### Étape 2 — Stabiliser les identifiants, DTO et erreurs API

1. Centraliser la normalisation du courriel et l'appliquer à l'inscription, à la connexion et aux recherches.
2. Garantir l'unicité à la fois par validation métier et contrainte unique en base, y compris pour deux inscriptions concurrentes.
3. Vérifier les validations des DTO d'entrée (format, champs obligatoires, longueurs, valeurs et bornes).
4. Réduire les données personnelles renvoyées dans `UserResponse` et `KycDocumentResponse` au strict nécessaire.
5. Utiliser des exceptions métier explicites : validation invalide, conflit, identifiants invalides, accès refusé et ressource absente.
6. Aligner chaque exception avec un statut HTTP stable (`400`, `401`, `403`, `404`, `409`, `413` le cas échéant); ne pas transformer une erreur utilisateur attendue en `500`.
7. Ne jamais inclure dans les messages d'erreur les mots de passe, JWT, secrets, stack traces ou contenu de documents.

**Acceptation :** mêmes entrées équivalentes produisent le même résultat; la forme des erreurs est documentée et testée; aucune erreur métier attendue ne répond `500`.

#### Première tranche d'implémentation réalisée

- La normalisation du courriel (trim et minuscules avec `Locale.ROOT`) est partagée entre inscription et connexion. Les setters des DTO enlèvent les espaces périphériques avant validation. Le filtre JWT recherche le sujet normalisé.
- La migration `V3__normalize_user_emails.sql` vérifie d'abord les collisions après normalisation, refuse clairement la migration si des doublons existent, normalise les courriels existants puis crée un index unique sur `lower(email)`. La migration V3 a été appliquée avec succès à la base locale existante; aucun résultat sur une base vierge n'est encore établi.
- L'inscription mappe également les violations des contraintes d'unicité connues vers `409`, y compris une collision concurrente, sans convertir les autres erreurs de base en faux doublon.
- Les longueurs des champs de compte et de rejet KYC sont bornées. Les mots de passe sont limités à 72 octets UTF-8 pour éviter la troncature BCrypt; un mot de passe trop long à la connexion produit un échec générique d'identifiants.
- Les réponses KYC ne retournent plus le numéro complet ni le chemin local : le numéro est masqué et seul le DTO public est exposé. Cela réduit les données divulguées, mais une route de lecture sécurisée du document reste nécessaire au traitement KYC et sera traitée dans l'étape 5.
- Les erreurs de validation de paramètres, de corps JSON, de paramètres manquants/type incorrect, de fichiers invalides et de dépassement de taille sont converties en réponses cohérentes (`400` ou `413`).

Après ces changements, `.\mvnw.cmd test` réussit : **13 tests, 0 échec, 0 erreur**. Flyway valide les trois migrations et confirme que la base locale existante est à la version 3. Restent à couvrir par tests HTTP la validation des paramètres et le contrat JSON d'erreur, ainsi que l'inscription concurrente sur PostgreSQL; les migrations sur base vierge ne sont pas encore testées. **État de l'étape 2 : première tranche codée et tests unitaires verts, critères d'acceptation encore partiels.**

### Étape 3 — Appliquer l'autorisation et le cycle de vie des comptes

1. Appliquer les règles de statut décidées à l'étape 1 à la connexion et à chaque requête authentifiée.
2. Vérifier les autorisations au niveau de chaque action et de chaque ressource; ne jamais se fier à un UUID reçu du client comme preuve de propriété.
3. Maintenir une matrice de tests pour accès anonyme, utilisateur propriétaire, autre utilisateur, administrateur et compte suspendu.
4. Séparer clairement les fonctions utilisateur des fonctions d'administration; garder les routes admin protégées côté serveur.
5. Mettre en place le provisionnement du premier admin par un mécanisme d'exploitation contrôlé, avec journal/audit; ne pas ajouter le rôle au DTO d'inscription publique.
6. Définir la gestion des échecs de connexion (messages uniformes, limitation de débit et protection contre les essais automatisés) en fonction des exigences du produit.
7. N'autoriser que les endpoints Actuator nécessaires; les endpoints de diagnostic ne doivent pas être exposés par défaut.

**Acceptation :** tests démontrant les refus d'accès attendus (`401` sans identité, `403` avec rôle insuffisant, `404` selon la politique anti-énumération) et les permissions minimales.

#### Première tranche d'implémentation réalisée

- L'API est explicitement stateless et n'accepte plus HTTP Basic ni formulaire Spring; une requête sans JWT sur une route protégée retourne `401`.
- L'inscription, la connexion et JWKS restent publiques; seul `GET /actuator/health` est autorisé publiquement. Les autres routes Actuator exigent une authentification.
- Les routes `/kyc/admin/**` requièrent le rôle `ADMIN`; un utilisateur ordinaire reçoit `403`. L'authentification JWT continue de charger le compte et son rôle actuels depuis la base, et refuse les comptes suspendus y compris avec un ancien JWT. Les comptes `PENDING_KYC` conservent les accès provisoires définis à l'étape 1.
- Des tests MockMvc vérifient les refus et les accès anonyme, utilisateur standard, administrateur, JWKS et Actuator.

Le provisionnement du premier administrateur de production auditable, la limitation des tentatives de connexion, la matrice complète de propriété des ressources et la définition finale des routes permises aux comptes en attente restent ouverts. Aucun mécanisme public d'élévation de rôle n'est ajouté. Un outil ponctuel de développement existe maintenant : il exige le profil local explicite, une base loopback et s'exécute sans serveur HTTP; il ne constitue pas un processus de provisionnement production. Le health check public devra rester sans détails sensibles dans la configuration opérationnelle.

La suite `.\mvnw.cmd test` passait après cette tranche : **20 tests, 0 échec, 0 erreur**, dont les tests HTTP de sécurité; Flyway validait alors le schéma local V3. **L'étape 3 est partiellement réalisée** : les tests valident le contrôle d'accès sur les routes actuelles, mais les décisions métier, le provisionnement administratif auditable de production, les limites de connexion et une matrice complète des accès aux ressources restent nécessaires avant son acceptation complète.

### Étape 4 — Rendre le contrat JWT/JWKS cohérent et durable

1. Figer les claims et leurs types : le code actuel met l'e-mail dans `sub` et l'identifiant utilisateur UUID dans `userId`; documenter ce contrat pour les autres services.
2. Utiliser des clés RS256 stables entre redémarrages et plusieurs instances via un fournisseur de clés abstrait/configurable. Le choix du fournisseur concret pourra être fait à l'étape de déploiement; aucune clé privée ne doit être commise.
3. Rendre la durée du jeton configurable et validée; supprimer la propriété HS256 inutilisée qui contredit l'implémentation RS256.
4. Générer le `kid` à partir de la version effective de la clé, plutôt que de conserver un identifiant incohérent avec la clé publiée.
5. Encoder `n` et `e` au format JWK en entiers non signés, Base64URL sans padding. Tester le résultat avec un parseur/validateur indépendant.
6. Définir les durées de cache JWKS et de jeton, la rotation, la période de chevauchement des clés et une procédure de révocation d'urgence.
7. Décider la stratégie de révocation à la suspension d'un compte : durée courte du JWT, vérification de statut en requête, version de jeton, liste de révocation ou autre compromis explicite.
8. Tester jeton modifié, expiré, mal signé, mauvais `kid`, clé ancienne pendant rotation, clé inconnue et comportement après redémarrage.

**Acceptation :** un JWT émis avant un redémarrage reste vérifiable selon la politique décidée; tous les validateurs reconnaissent la JWKS; la suspension/révocation suit une règle testée.

#### Première tranche d'implémentation réalisée

- Le contrat émis est `RS256`, avec `kid` configuré, `iss` (par défaut `identity-service`), `sub` contenant le courriel normalisé, `userId` en chaîne UUID, `role` et `status` en chaînes, ainsi que `iat` et `exp` standard. `role` et `status` sont des instantanés : les consommateurs ne doivent pas les traiter comme l'état actuel du compte. Aucun `aud` n'est défini tant que les services consommateurs et leurs audiences ne sont pas connus.
- La durée est configurable par `IDENTITY_JWT_EXPIRATION_MS` (900 000 ms par défaut; maximum 24 h). Le service vérifie l'algorithme `RS256`, le `kid`, la signature et l'émetteur. Un `kid` inconnu ou une signature altérée est refusé.
- En développement, une paire éphémère peut encore être générée pour faciliter le démarrage. Pour le profil `prod`, cette option est désactivée : fournir une paire PEM RSA persistante PKCS#8/X.509 par `IDENTITY_JWT_PRIVATE_KEY_LOCATION` et `IDENTITY_JWT_PUBLIC_KEY_LOCATION`, ainsi qu'un `IDENTITY_JWT_ACTIVE_KEY_ID`. Les fichiers sont chargés via des emplacements Spring Resource, par exemple `file:/chemin/protégé/private.pem`. Les clés ne doivent pas être commitées dans Git ni intégrées à l'image. Le démarrage échoue si le profil `prod` n'a pas de clé persistante ou si la paire ne correspond pas.
- Les clés publiques actives et antérieures configurées sont publiées par JWKS. `n` et `e` sont encodés comme entiers non signés, Base64URL sans padding. La réponse indique `Cache-Control: public, max-age=300`.
- Rotation avec chevauchement : (1) publier la nouvelle clé publique dans `identity.jwt.verification-keys` sous son futur `kid`, en gardant l'ancienne clé active, puis déployer cette configuration sur toutes les instances; (2) définir la nouvelle paire comme clé active et déployer, en gardant l'ancienne clé publique dans la liste; les deux générations de jetons restent alors vérifiables; (3) attendre au moins la durée maximale de vie du JWT plus le cache JWKS, puis retirer l'ancienne clé publique. Une révocation urgente consiste à retirer le `kid` compromis et à redéployer; elle invalide les jetons correspondants dès que la configuration est effective (et, chez les consommateurs, après expiration du cache).
- Le filtre de ce service relit le statut du compte en base à chaque requête protégée; un compte suspendu est donc refusé immédiatement ici. Les consommateurs hors service ne disposent pas de cette vérification et doivent respecter l'expiration courte ou une future stratégie partagée de révocation.

Exemple de configuration de rotation (les chemins désignent des fichiers de clé publique protégés et montés séparément; ne pas mettre les clés privées dans le YAML) :

```yaml
identity:
  jwt:
    active-key-id: auth-2026-02
    private-key-location: file:/run/identity-keys/auth-2026-02-private.pem
    public-key-location: file:/run/identity-keys/auth-2026-02-public.pem
    allow-ephemeral-keys: false
    verification-keys:
      auth-2026-01: file:/run/identity-keys/auth-2026-01-public.pem
```

Le JWKS est testé en reconstruisant la clé RSA publique à partir de `n` et `e`, puis en vérifiant indépendamment un JWT signé. Des tests couvrent aussi le redémarrage avec la même paire, le chevauchement de rotation, un `kid` inconnu et une signature modifiée. La génération/installation des clés dans l'environnement de production, la validation de toutes les politiques de rotation et l'essai de révocation chez les consommateurs restent à effectuer.

La suite `.\mvnw.cmd test` passe après cette tranche : **26 tests, 0 échec, 0 erreur**. **L'étape 4 est partiellement réalisée** : stabilité par fichiers et vérification multi-clé sont implémentées; la stratégie finale d'audience/révocation et la validation réelle entre microservices restent ouvertes.

### Étape 5 — Achever le processus KYC sans fuite ni accès croisé

1. Définir les documents et formats acceptés, la taille maximale, les règles de ré-envoi, le traitement de doublons et la durée de conservation.
2. Vérifier le contenu réel du fichier et pas seulement l'extension ou le `Content-Type` fourni par le client; rejeter les fichiers vides, trop grands ou non conformes.
3. Utiliser un nom généré par le serveur; ne jamais construire le chemin de stockage à partir du nom de fichier soumis.
4. Isoler le stockage derrière une interface; garder une implémentation locale pour le développement et prévoir une implémentation persistante injectable pour le futur déploiement.
5. Enregistrer un identifiant d'objet opaque plutôt qu'un chemin local dans la base et dans les réponses API.
6. Si un téléchargement est nécessaire, ajouter une route qui vérifie le propriétaire ou le rôle admin à chaque requête et ne divulgue pas un lien durable non protégé.
7. Traiter les échecs partiels fichier/base, le nettoyage des objets orphelins et la suppression suivant la politique de rétention.
8. Vérifier les transitions et empêcher les validations/rejets répétés ou contradictoires selon les règles décidées.
9. Auditer les décisions d'administration sans copier le document, son numéro complet ou ses secrets dans les logs.

**Acceptation :** un utilisateur ne peut ni lister ni lire les documents d'un autre; seuls les rôles prévus peuvent examiner le KYC; formats invalides, dépassements et erreurs de stockage sont contrôlés et testés.

#### Première tranche d'implémentation réalisée

- Le dépôt de fichiers est derrière `DocumentStorage`; `LocalDocumentStorage` est l'implémentation locale, avec racine configurable (`IDENTITY_KYC_STORAGE_ROOT`). La base conserve une clé UUID opaque et un type MIME déterminé côté serveur, plus le chemin local ni le nom fourni par le client.
- La migration Flyway V4 renomme `file_url` en `storage_key`, réduit les anciennes valeurs de chemin à leur nom de fichier et ajoute `content_type`; les anciens fichiers locaux existants doivent rester sous la racine configurée, sinon ils ne seront pas lisibles. Vérifier/sauvegarder ces fichiers avant toute migration d'environnement.
- La taille limite est de 10 Mio au niveau multipart et du service (configurable par `IDENTITY_KYC_MAX_FILE_BYTES`). L'extension doit être dans PDF/JPG/JPEG/PNG et correspondre à une signature de fichier reconnue; le `Content-Type` déclaré par le client est ignoré. Une signature seule n'est pas une analyse antivirus ni une validation complète du format.
- Un téléchargement d'examen existe par `GET /kyc/admin/documents/{id}/file`; il est inclus dans `/kyc/admin/**`, donc réservé aux administrateurs, renvoyé en pièce jointe avec `X-Content-Type-Options: nosniff`. Aucune route publique ou utilisateur ne sert le chemin de stockage.
- Si l'écriture en base échoue, l'objet nouvellement créé est supprimé; un rollback transactionnel déclenche aussi un nettoyage. Une panne brutale ou une panne du stockage pendant le nettoyage peut encore laisser un objet orphelin : un processus de réconciliation périodique reste à concevoir.
- Des tests couvrent les signatures/extension et taille, les clés opaques, le nettoyage après échec d'écriture, la lecture/suppression du stockage local, le rejet des clés de chemin et le contrôle du téléchargement réservé à l'administrateur.

Le stockage local est prévu pour le développement; il n'est pas une solution de production multi-instance. À l'étape de déploiement, il faudra fournir un adaptateur de stockage durable privé, des contrôles d'accès au stockage, chiffrement et sauvegardes. Une analyse antivirus/CDR, les journaux d'audit des décisions, la rétention/suppression, les essais de fichiers hostiles et le test réel des accès propriétaire/admin restent à réaliser et doivent être validés avec produit/conformité.

La suite `.\mvnw.cmd test` passe après cette tranche : **35 tests, 0 échec, 0 erreur**; Flyway valide les cinq migrations sur la base locale existante. **L'étape 5 est partiellement réalisée** : les fondations de validation, stockage opaque et route admin sont en place, mais l'acceptation production de l'étape exige encore les contrôles opérationnels et de conformité précisés ci-dessus.

### Étape 6 — Compléter les parcours de compte nécessaires

À confirmer avec le besoin produit avant d'implémenter :

1. Vérification du courriel et activation du compte.
2. Réinitialisation de mot de passe avec jeton à durée limitée, usage unique et réponse non révélatrice de l'existence du compte.
3. Changement de courriel et revalidation de l'identité.
4. Déconnexion/révocation des sessions ou jetons si le besoin produit l'exige.
5. Fermeture du compte, export/suppression des données et conservation légale des éléments KYC.

Pour chaque parcours, définir préalablement expiration, anti-abus, audit, concurrence, erreurs, conservation et conséquences sur les JWT existants.

**Acceptation :** seuls les parcours validés par le produit sont publiés; les jetons temporaires sont protégés, expirent et ne peuvent pas être réutilisés.

#### Point de décision avant implémentation

Aucun de ces parcours n'est actuellement implémenté et le dépôt ne contient pas de service de livraison de courriels. Ne pas ajouter de fausses routes de succès ni des jetons envoyés uniquement dans les logs. Pour poursuivre sans hypothèses implicites, valider les choix suivants :

| Parcours | Proposition technique par défaut à confirmer |
|---|---|
| Vérification du courriel | Compte non vérifié tant qu'un lien à usage unique et à durée limitée n'a pas été utilisé; confirmer si la connexion et le dépôt KYC sont permis avant vérification. |
| Mot de passe oublié | Réponse uniforme `202` qu'un compte existe ou non; jeton aléatoire opaque, stocké uniquement sous forme hachée, usage unique et expiration courte; confirmer le canal d'envoi (un fournisseur SMTP devra être choisi/configuré). |
| Changement d'adresse | Conserver l'ancienne adresse jusqu'à validation de la nouvelle; invalider les jetons de vérification précédents. |
| Déconnexion/révocation | Les JWT d'accès restent valides jusqu'à leur expiration courte (15 minutes par défaut); confirmer si une révocation immédiate est requise, ce qui implique un état de session/version vérifié côté serveur et chez les consommateurs concernés. |
| Fermeture et suppression | Suspendre immédiatement l'accès; confirmer si le compte est supprimé ou anonymisé, les données/KYC soumis à conservation légale, et les délais de rétention avant suppression définitive. |

Préciser aussi le propriétaire de l'envoi des courriels, les limites anti-abus, l'audit et la durée de conservation des jetons d'action. **État : en attente de décisions produit/conformité; aucune modification de code n'est faite pour l'étape 6 tant que ces règles et le canal de courriel ne sont pas validés.**

### Étape 7 — Préparer configuration et exécution de production de façon portable

1. Séparer les profils local, test et production; faire échouer le démarrage de production si un secret ou paramètre obligatoire manque.
2. Remplacer toutes les valeurs par défaut sensibles par une configuration externe; ne pas encore imposer Secrets Manager, KMS ou un fournisseur cloud dans le code métier.
3. Désactiver `show-sql` et les diagnostics verbeux en production; contrôler le niveau et le contenu des logs.
4. Configurer limites de requêtes et de multipart, timeouts, taille des pools, fermeture propre et comportement en cas d'indisponibilité de la base/stockage.
5. Ajouter des endpoints de santé adaptés, sans exposer de détails internes; distinguer disponibilité et readiness.
6. Vérifier que le conteneur utilise un utilisateur non-root, un système de fichiers aussi restreint que possible et des images de base maintenues.
7. Documenter les exigences de proxy/TLS/CORS et ne faire confiance qu'aux en-têtes proxy provenant d'un proxy de confiance.
8. Définir les données à masquer dans les logs et un identifiant de corrélation qui ne contient pas de donnée personnelle.

#### Réalisation de l'étape 7

- Profils `local`, `test` et `prod` séparés; `prod` exige les paramètres de base, les clés RSA persistantes et les paramètres de stockage KYC. Les clés éphémères et le stockage local ne sont pas acceptables en production. Les tests de contexte utilisent maintenant le profil `test`, mais pointent encore vers la base PostgreSQL locale.
- Configuration commune : fermeture gracieuse, limites Tomcat/multipart, pools JDBC bornés, SQL non affiché, Open Session in View désactivé, readiness/liveness Actuator avec détails masqués.
- Les routes de santé `/actuator/health`, `/actuator/health/readiness` et `/actuator/health/liveness` sont publiques; les autres routes Actuator restent protégées. Les réponses et logs portent un `X-Correlation-ID` UUID (fourni valide ou généré par le service); aucune valeur invalide n'est recopiée.
- Image Docker non-root, système de fichiers en lecture seule et volume nommé pour les fichiers en exécution Compose. Compose est réservé au développement local, les ports sont liés à `127.0.0.1` et exige `LOCAL_POSTGRES_PASSWORD` injecté dans l'environnement; aucun mot de passe par défaut n'est codé dans Compose.
- Les en-têtes proxy transférés ne sont pas activés par défaut. En déploiement, terminer TLS sur un proxy contrôlé, n'accepter ses en-têtes que depuis ce proxy, configurer explicitement CORS avec les origines nécessaires et ne jamais exposer directement les ports applicatifs.

**Limites restantes :** seul l'adaptateur KYC local existe; aucun stockage durable multi-instance n'est livré. Le démarrage `prod` doit donc échouer tant qu'un fournisseur adapté n'est pas implémenté/configuré. Les secrets doivent être injectés par l'environnement de déploiement, sans être placés dans un fichier versionné ou l'image. Les tests n'ont pas encore une base isolée et les validations Compose/Docker ainsi que la configuration réelle du proxy/TLS/CORS restent à faire. Docker n'est pas disponible dans l'environnement de travail. Pour lancer Compose localement, définir `LOCAL_POSTGRES_PASSWORD` dans l'environnement avant `docker compose up --build`; ne pas reprendre cette valeur comme secret de production.

**État de l'étape 7 : partiellement réalisée.** `.\mvnw.cmd test` passe (**37 tests, 0 échec, 0 erreur**) avec PostgreSQL locale et les migrations V5; `git diff --check` ne rapporte pas d'erreur de patch. La validation de Compose et de l'image n'a pas pu être faite, car Docker n'est pas installé. L'acceptation production n'est pas atteinte tant que le stockage durable, ses tests d'intégration et les vérifications de déploiement ne sont pas présents.

### Étape 8 — Tester les règles et les abus

Ajouter des tests au plus près des risques :

1. Tests unitaires de service pour inscription, normalisation, doublons, politique de mot de passe, statuts et transitions KYC.
2. Tests MVC/Security pour les routes publiques/privées, les rôles, la propriété des ressources et le format des erreurs.
3. Tests JWT/JWKS indépendants de l'implémentation interne, rotation et démarrage avec plusieurs clés/configurations.
4. Tests de fichiers : vide, trop grand, contenu falsifié, extension trompeuse, chemin malveillant, erreur de stockage et nettoyage.
5. Tests d'intégration PostgreSQL/Flyway, concurrence sur inscription et rollback transactionnel.
6. Tests de non-régression des parcours et tests de charge sur connexion, recherche et dépôt de fichiers.
7. Lancer `.\mvnw.cmd test` localement et dans la CI; ne pas considérer `docker build` comme une validation des tests.

**Acceptation :** chaque règle métier et scénario d'abus A04 possède au moins un test reproductible; les tests échouent lorsqu'un contrôle est retiré.

#### Réalisation de l'étape 8

- Tests de service ajoutés pour le rejet d'un courriel déjà normalisé, le conflit d'unicité survenant entre la pré-vérification et l'insertion, l'adresse inconnue et le mot de passe erroné sans émission de JWT.
- Test MVC ajouté pour vérifier le statut `400` et la structure commune des erreurs de validation sur `/users/login`.
- Tests KYC ajoutés pour un fichier vide, une extension non autorisée et une indisponibilité du stockage; ils vérifient que les métadonnées ne sont pas persistées en cas d'échec.
- La suite contient maintenant **44 tests**, y compris les tests préexistants sur les autorisations admin, JWT/JWKS/rotation, taille et signature de fichiers, chemin de stockage et nettoyage après échec d'écriture.
- `.\mvnw.cmd test` passe avec **44 tests, 0 échec, 0 erreur**. Flyway valide les cinq migrations sur PostgreSQL locale 18.6; Flyway émet encore un avertissement car cette version de PostgreSQL dépasse les versions supportées/testées déclarées par la version utilisée.

**État de l'étape 8 : partiellement réalisée.** La suite de service/MVC passe maintenant (**51 tests, 0 échec, 0 erreur**), mais la configuration `test` utilise encore la base PostgreSQL locale; les tests d'intégration sur base dédiée vierge, le véritable test de concurrence d'inscription et de rollback transactionnel, les essais de charge et l'exécution CI ne sont pas réalisés. Les tests de stockage et de JWKS sont unitaires/intégrés à MockMvc, pas des tests contre les futurs fournisseurs de stockage et consommateurs réels. Ne pas considérer l'étape acceptée ni le service prêt pour AWS avant ces vérifications.

### Étape 9 — Renforcer la CI et la documentation

1. Séparer les jobs de compilation/tests, SAST/secrets, dépendances, image et DAST.
2. Fixer un seuil de blocage explicite; ne pas laisser `|| true` ou `--exit-code 0` neutraliser silencieusement les échecs qui doivent bloquer une PR.
3. Pour chaque exception au seuil, exiger un propriétaire, une justification, une date d'expiration et un suivi.
4. Générer OpenAPI à partir du contrat maintenu; documenter authentification, rôles, exemples, erreurs, limites et versionnement.
5. Documenter procédure de migration, restauration, rotation de clés, incidents de sécurité et accès administrateur.
6. Traiter les rapports SAST/DAST comme des signaux à analyser; un scan réussi ne prouve ni l'absence de vulnérabilité ni la qualité de conception A04.

**Acceptation :** une régression critique définie par la politique fait échouer la CI; le contrat API et les procédures opérationnelles correspondent au comportement testé.

#### Réalisation de l'étape 9

- Le workflow `.github/workflows/devsecops-pipeline.yml` sépare maintenant tests Maven/PostgreSQL, détection de secrets, SAST Semgrep, dépendances Trivy, image conteneur Trivy et DAST ZAP. Les actions GitHub utilisées sont référencées par SHA; outils conteneurisés à versions fixes.
- Seuils bloquants définis : toute détection Gitleaks; findings Semgrep transmis par `--error`; vulnérabilités `HIGH`/`CRITICAL` pour dépendances et image; alertes ZAP `FAIL` (les `WARN` sont rapportées mais non bloquantes avec `-I`). Les `|| true` et `--exit-code 0` de l'ancien workflow ont été retirés. Les rapports sont conservés sept jours; le rapport Gitleaks est expurgé.
- `docs/SECURITY-EXCEPTIONS.md` décrit les champs obligatoires d'une exception temporaire : finding précis, justification, mesures compensatoires, responsable, issue, approbateur et expiration.
- OpenAPI est généré à partir des contrôleurs avec springdoc. `/v3/api-docs` et Swagger UI sont publics; la définition documente Bearer JWT, les rôles et les contraintes KYC. Un test vérifie l'endpoint, le schéma bearer et l'exigence `ROLE_ADMIN`.
- `docs/API.md` décrit routes, authentification, rôles, erreurs, limite KYC de 10 MiB et absence actuelle de versionnement URI. `docs/OPERATIONS.md` décrit migrations/restauration, rotation JWT, incident et limites du provisionnement administrateur.
- `.\mvnw.cmd clean verify` passe avec **51 tests, 0 échec, 0 erreur**, dont le test OpenAPI et les tests du provisionnement admin local.

**Limites restantes :** le workflow n'a pas été exécuté sur GitHub dans cette session. Docker n'est pas installé localement, donc les jobs container, DAST et l'analyse réelle des rapports ne sont pas validés ici. Configurer dans GitHub les checks requis (`Build and tests`, `Secret scanning`, `SAST (Semgrep)`, `Dependency scan (Trivy)`, `Container build and scan` et `DAST (OWASP ZAP baseline)`) pour la protection des branches; un fichier workflow seul ne rend pas les checks obligatoires. La politique de seuils et d'exceptions doit être approuvée par les responsables sécurité. Le ZAP baseline actuel ne s'authentifie pas et ne couvre donc pas les parcours privés/admin; il ne constitue pas un DAST exhaustif. Les scans ne constituent pas une certification. Les procédures opérationnelles sont des garde-fous documentaires, pas des scripts de restauration ni un mécanisme de provisionnement admin.

**État de l'étape 9 : partiellement réalisée.** Workflow et documentation sont en place et les tests locaux réussissent; l'acceptation exige encore exécution CI, configuration des checks requis, revue des seuils/rapports et validation opérationnelle. Les étapes 6, 7, 8 et les lacunes de stockage KYC durable/provisionnement admin restent bloquantes avant AWS.

## 4. Ordre conseillé pour les prochains travaux

1. Décider les règles de statut, KYC, rôles et parcours de compte (étape 1).
2. Compléter tests de base et contrat API (étapes 0 et 2).
3. Corriger l'autorisation et le provisionnement admin (étape 3).
4. Stabiliser les clés, `kid` et JWKS (étape 4).
5. Sécuriser le cycle de vie des documents KYC et abstraire le stockage (étape 5).
6. Ajouter les parcours de compte réellement requis (étape 6).
7. Durcir configuration et conteneur sans dépendance cloud (étape 7).
8. Étendre tests, CI et documentation jusqu'aux critères d'acceptation (étapes 8 et 9).
9. Une fois ces critères atteints, définir séparément l'architecture et le déploiement AWS.

## 5. Critères de sortie avant d'aborder AWS

Le travail applicatif peut être considéré prêt à passer à l'étude AWS seulement lorsque :

- les règles métier et scénarios A04 sont écrits et approuvés ;
- les routes, rôles, erreurs et données retournées sont documentés et testés ;
- les clés et JWKS sont correctes, stables et rotatives selon la politique retenue ;
- les contrôles d'accès couvrent comptes, rôles et propriété des documents ;
- le stockage KYC est abstrait du disque local et ses politiques de sécurité sont définies ;
- les tests automatisés couvrent les parcours nominaux, négatifs et d'abus ;
- la configuration de production ne repose sur aucun secret ou mot de passe par défaut ;
- la CI applique les seuils décidés et conserve les rapports de sécurité ;
- les questions de conformité, de conservation des données et de reprise sont assignées aux responsables concernés.

Ces critères ne valent pas certification de sécurité ou de conformité réglementaire. Ils constituent le socle technique et de conception à examiner avant de choisir les services AWS et de planifier un déploiement.

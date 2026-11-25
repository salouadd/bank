# Préparation du déploiement Swarm — Identity et Financial

Ce document est un questionnaire à transmettre aux équipes **Identity** et **Financial** avant toute modification du fichier de stack Swarm. Compléter une fiche par service et obtenir les confirmations écrites. Ne pas déduire les paramètres de production à partir des fichiers de développement.

> **Blocage de déploiement :** ne pas modifier ni déployer la stack tant que les images, contrats applicatifs, dépendances, secrets et besoins de stockage ne sont pas confirmés. `docker stack deploy` déploie des images déjà construites : il ne construit pas les images et n'accepte pas `build:` comme `docker compose up --build`.

## 1. Questions à poser à chaque équipe

Utiliser cette fiche séparément pour **Identity** et **Financial**.

### A. Image et registre

- **Nom complet de l'image**, registre compris si nécessaire (ex. `registry.example.com/equipe/identity:1.2.3`) :
- **Tag immuable** exact à déployer (version ou digest `sha256:...`; ne pas utiliser `latest`) :
- L'image a-t-elle été construite et poussée au registre ? Fournir le tag/digest publié :
- Tous les nœuds Swarm qui pourront exécuter le service peuvent-ils joindre le registre et télécharger cette image ?
- Le registre est-il privé ? Si oui, comment l'authentification est-elle fournie au déploiement (par exemple mécanisme de secrets/credentials approuvé et transmission des identifiants à `docker stack deploy`) ?
- Confirmer qu'aucun identifiant, mot de passe ou jeton de registre ne sera écrit dans le YAML, dans l'image ou dans un dépôt :
- L'équipe fournit-elle un Dockerfile et une procédure de construction/versionnement ? Les communiquer séparément; le Dockerfile ne remplace pas l'image préconstruite requise par Swarm.

### B. Contrat réseau et application

- Port réellement écouté **dans le conteneur** :
- Le serveur écoute-t-il sur `0.0.0.0` / toutes les interfaces du conteneur, et non uniquement sur `127.0.0.1` ?
- Protocole attendu entre les services (HTTP, HTTPS, autre) :
- Routes exactes, méthodes HTTP, authentification et formats JSON (requête et réponse), en précisant les erreurs :
  - Login :
  - JWKS (si applicable) :
  - Comptes/utilisateurs :
  - Autres routes consommées par les autres services :
- Chemin(s) de santé, méthode et code/réponse attendus :
- Quelles conditions indiquent que le service est **réellement prêt** à recevoir du trafic (par exemple base joignable, migrations terminées, dépendance disponible) ? Une vérification de processus seul n'est pas suffisante :
- Variables d'environnement reconnues pour le port, le profil et la configuration (nom, valeur non secrète/exemple, valeur par défaut et caractère obligatoire) :
- Délai de démarrage habituel et délai maximal avant disponibilité :
- Ressources recommandées/minimales (CPU et mémoire) :
- Politique de redémarrage recommandée et comportement en cas d'échec :

### C. Dépendances et données

- Le service dépend-il d'une base de données, d'un broker, d'un stockage de fichiers ou d'autres services ? Ne pas supposer qu'il est autonome :
- Pour chaque dépendance, préciser :
  - nom du service et équipe responsable ;
  - image et version immuable, si elle est elle-même déployée dans Swarm ;
  - port interne et protocole ;
  - réseau Swarm à utiliser et nom DNS/service attendu ;
  - exigences de disponibilité et comportement si la dépendance est indisponible.
- Variables de connexion requises (noms uniquement dans cette fiche; fournir les valeurs sensibles via le gestionnaire de secrets approuvé) :
- Migrations de schéma : outil, moment d'exécution, prérequis, compatibilité avec plusieurs tâches et procédure de retour arrière :
- Sauvegardes et restauration : données à sauvegarder, fréquence/rétention, procédure testée et responsable :
- Volumes ou autres données devant survivre au redémarrage, au remplacement d'une tâche ou au déplacement sur un autre nœud :
- Secrets requis (mots de passe, clés privées, tokens, certificats) :
  - nom/finalité de chaque secret ;
  - méthode de lecture par l'application (fichier monté, variable ou autre) ;
  - méthode approuvée de création, rotation et révocation.
- Confirmer qu'aucun secret n'est inscrit en clair dans le fichier YAML, les variables d'environnement versionnées, les logs ou l'image :

## 2. Contrat JWT Identity / Gateway

À faire confirmer par l'équipe **Identity** et vérifier avec l'équipe **Gateway** avant mise en production :

- Les JWT sont-ils signés avec **RS256** ?
- Quelle valeur exacte est émise dans le claim `iss` ? Elle doit être `identity-service`, sauf si `JWT_ISSUER` est configuré à la valeur réelle convenue avec le Gateway :
- Le JWKS public est-il accessible par `GET /.well-known/jwks.json` sur le port interne du conteneur ?
- Les JWT contiennent-ils les claims `sub` et `role` ?
- Les valeurs de `role` correspondent-elles à celles attendues par le Gateway : `USER` et `ADMIN` ? Préciser le mapping exact, notamment si l'application utilise une représentation différente telle que `ROLE_USER` / `ROLE_ADMIN` :
- Le Gateway récupère-t-il uniquement la clé publique depuis le JWKS ? **Ne jamais distribuer ni monter la clé privée de signature dans le Gateway.**
- Comment les clés publiques et leur rotation sont-elles gérées pour permettre le chevauchement des clés durant un déploiement ?
- Réaliser un test de bout en bout : Identity émet un JWT, le Gateway valide la signature RS256, `iss`, `sub`, l'expiration et le rôle attendu :

### Informations Identity visibles dans ce dépôt — à confirmer par l'équipe

La documentation de développement Identity décrit les routes `POST /users/login`, `GET /.well-known/jwks.json`, `POST /users/register` et `GET /users/me`, et un endpoint de readiness `GET /actuator/health/readiness`. Elle indique également une signature RS256. Ces éléments décrivent le dépôt et ne constituent pas, à eux seuls, une validation de la configuration de l'image déployée, de son `iss`, de la forme du claim `role` ou de son accessibilité depuis le Gateway. Les faire confirmer par Identity avant de les inscrire comme contrat de production.

## 3. Fiche de réponse à compléter

Copier cette section pour chaque service et faire valider les réponses par l'équipe propriétaire.

### Service : Identity / Financial

| Élément | Réponse de l'équipe |
|---|---|
| Équipe/contact responsable | |
| Image complète et registre | |
| Tag immuable ou digest | |
| Image poussée et accessible à tous les nœuds Swarm | |
| Modalité d'authentification au registre privé | |
| Port interne, protocole, écoute sur toutes les interfaces | |
| Routes et contrat JSON | |
| Healthcheck et critères de disponibilité | |
| Variables d'environnement reconnues | |
| Délai de démarrage | |
| Ressources recommandées | |
| Politique de redémarrage | |
| Dépendances, images, ports et réseau | |
| Connexions et migrations de schéma | |
| Sauvegardes et restauration | |
| Volumes/données persistantes | |
| Secrets et mode de lecture | |
| Date et nom de la personne ayant confirmé | |

## 4. Conditions avant modification de la stack

Ne commencer la modification du YAML qu'après avoir :

1. Obtenu une fiche complète et validée pour Identity et une pour Financial.
2. Vérifié que chaque image immuable existe dans le registre et que tous les nœuds concernés peuvent la télécharger.
3. Confirmé les ports internes, les réseaux et les noms de service DNS nécessaires entre tâches Swarm.
4. Défini les healthchecks sur les critères réels de disponibilité, ainsi que les ressources et politiques de redémarrage recommandées.
5. Planifié le déploiement des bases, brokers et autres dépendances, les migrations et la sauvegarde/restauration des données.
6. Fourni les secrets par le mécanisme approuvé, sans les inscrire dans le fichier YAML.
7. Validé le contrat JWT Identity/Gateway de bout en bout, sans communiquer la clé privée au Gateway.

Si une base ou une autre dépendance n'est pas déployée ou joignable, le conteneur applicatif peut démarrer sans que le service soit fonctionnel. La réussite du démarrage seule ne vaut donc pas validation du déploiement.

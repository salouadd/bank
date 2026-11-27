# Prompt réutilisable — intégrer un microservice à l’API Gateway

Copiez le prompt ci-dessous dans votre assistant de code depuis le dépôt du **microservice à intégrer** ou celui du **Gateway**. Complétez les informations entre crochets avant de lancer le travail. L’assistant doit inspecter les dépôts et leur configuration réelle avant de modifier quoi que ce soit.

## Informations à fournir

- **Nom du service :** `[nom]`
- **Dépôt du microservice :** `[chemin ou URL]`
- **Dépôt du Gateway :** `[chemin ou URL]`
- **Stack / environnement :** `[Docker Compose, Swarm, Kubernetes, local...]`
- **Nom DNS interne et port du service :** `[ex. financial-coach:8082]`
- **Chemins publics à exposer :** `[ex. /api/v1/coach/**]`
- **Préfixe à retirer ou réécriture nécessaire :** `[aucun ou détail]`
- **Endpoints publics :** `[méthode + chemin + justification, ou aucun]`
- **Endpoints protégés et rôles nécessaires :** `[méthode + chemin + rôle]`
- **Contrat OpenAPI ou documentation :** `[chemin/URL]`

## Prompt à copier

```text
Je veux intégrer le microservice [NOM DU SERVICE] à notre API Gateway Spring Cloud Gateway.

Contexte fourni :
- Dépôt du microservice : [CHEMIN OU URL]
- Dépôt du Gateway : [CHEMIN OU URL]
- Environnement de déploiement : [COMPOSE / SWARM / KUBERNETES / LOCAL]
- Adresse DNS interne et port du microservice : [DNS:PORT]
- Chemins à exposer : [CHEMINS]
- Réécriture/prefixe : [DÉTAIL OU « aucune »]
- Endpoints publics : [MÉTHODES, CHEMINS ET JUSTIFICATION, OU « aucun »]
- Endpoints protégés / rôles : [MÉTHODES, CHEMINS, RÔLES]
- Contrat API : [OPENAPI, README OU AUTRE DOCUMENT]

Procède comme un ingénieur chargé d’implémenter et de vérifier cette intégration, pas seulement de la décrire.

1. Inspecte d’abord les deux dépôts : routes et configuration du Gateway, sécurité JWT, propriétés/variables d’environnement, Dockerfiles, fichiers Compose/Swarm/Kubernetes, réseaux, tests et documentation. Vérifie le contrat réel des endpoints du microservice. Ne suppose pas qu’un chemin, un port, un rôle ou un format de réponse existe sans preuve dans le code ou la documentation.

2. Fais un plan court des fichiers à modifier, puis réalise les changements minimaux et cohérents. Respecte les conventions déjà présentes. Ne modifie pas les règles métier du microservice et ne rends pas publics des endpoints protégés.

3. Configure le routage Gateway vers le nom DNS interne du service et son port interne. N’utilise jamais localhost comme adresse amont depuis un conteneur. Assure-toi que les services partagent le réseau de conteneurs requis. Si Compose, Swarm ou Kubernetes utilise un fichier de déploiement, mets à jour ce fichier de manière cohérente sans publier inutilement le port interne sur l’hôte.

4. Pour chaque route, préserve la méthode, les paramètres de requête, le corps et les en-têtes nécessaires. N’applique une réécriture ou un strip-prefix que si le contrat aval l’exige. Réutilise les filtres communs du Gateway (traçage, déduplication d’en-têtes, circuit breaker et retry) selon les conventions du projet. Ne configure pas de retry automatique sur les écritures.

5. Configure l’accès dans la sécurité du Gateway : uniquement les endpoints explicitement déclarés publics sont permis sans authentification. Les autres doivent conserver la validation JWT existante et, si nécessaire, les contrôles de rôles. N’ajoute pas de configuration de signature JWT, de clé privée ou de confiance différente sans justification. Si le service consomme les claims ou les en-têtes d’identité, vérifie précisément leurs noms et leur provenance; ne fais pas confiance à des en-têtes d’identité fournis par le client.

6. Ajoute ou adapte les propriétés de configuration, variables d’environnement et exemples de déploiement nécessaires. Les secrets ne doivent pas être ajoutés au dépôt. Documente l’URL interne, les chemins exposés, la réécriture éventuelle, les règles d’authentification et les prérequis réseau.

7. Ajoute des tests automatisés couvrant au minimum :
   - le routage vers le bon service et le chemin amont exact;
   - la transmission de méthode, query string, corps et en-têtes utiles;
   - le refus d’une route protégée sans JWT;
   - l’accès aux routes publiques sans JWT uniquement si elles sont prévues;
   - les règles de rôle lorsqu’elles existent;
   - la conservation des statuts d’erreur aval et la réponse en cas de service indisponible.
   Utilise des doubles de test ou des serveurs simulés; les tests ne doivent pas dépendre d’un service externe ni de données réelles.

8. Lance les tests ciblés, le build et les vérifications de configuration applicables. Corrige les échecs introduits par tes changements. Ne déclare pas les tests d’intégration en environnement conteneurisé réussis si tu ne les as pas réellement exécutés.

9. Termine par un résumé concis : fichiers changés, route et cible configurées, règles d’accès, tests exécutés et résultat. Signale clairement les valeurs ou décisions qui nécessitent confirmation. Ne déploie pas, ne pousse pas d’image et ne modifie pas un environnement partagé sans demande explicite.

Si une information est manquante ou contradictoire, inspecte le code et la documentation disponibles. Pose une question seulement si le choix affecte le contrat, l’accès, ou le déploiement et ne peut pas être déterminé sans risque; n’invente pas de valeur.
```

## Repères du Gateway de référence

Dans le Gateway actuellement documenté par l’équipe, les routes sont définies dans `RoutesConfig`, les règles d’authentification JWT et d’autorisation dans `SecurityConfig`, et les filtres communs appliquent le circuit breaker ainsi que le retry aux seules lectures `GET`. Les noms et emplacements peuvent différer dans d’autres branches : l’assistant doit vérifier le code présent dans le dépôt ciblé.

Après l’intégration, exécutez les tests automatisés. Pour un essai manuel, vérifiez d’abord `/healthz`, puis une route inoffensive du nouveau service à travers le Gateway. Testez les endpoints protégés avec un JWT de test et vérifiez le refus sans jeton. Ne mettez jamais de mot de passe, JWT ou clé privée dans un rapport ou un commit.

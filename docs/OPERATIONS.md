# Procédures opérationnelles — Identity Service

Ce document décrit les garde-fous applicatifs actuels. Il ne remplace pas les procédures de l'équipe d'exploitation, les exigences légales ou une architecture de production approuvée. Le stockage KYC durable et le provisionnement administrateur de production ne sont pas encore implémentés. Un outil ponctuel local existe uniquement pour les essais de développement.

## Migrations et restauration

1. Avant toute migration d'un environnement durable, confirmer la version de l'application, l'état Flyway et la compatibilité des migrations.
2. Prendre et vérifier une sauvegarde cohérente de la base avant déploiement; définir avec l'exploitation le point de restauration et l'impact de perte de données acceptable.
3. Déployer la migration avec le mécanisme de release contrôlé, surveiller readiness et erreurs Flyway, puis vérifier les parcours essentiels.
4. Les migrations Flyway ne sont pas automatiquement réversibles. En cas d'échec, arrêter le déploiement et préserver les journaux. Préférer une migration corrective en avant; ne restaurer une sauvegarde qu'après décision explicite, en tenant compte des écritures intervenues depuis.
5. Tester régulièrement la restauration sur un environnement isolé. Ce dépôt ne fournit pas de script validé de sauvegarde/restauration de production.

## Rotation des clés JWT

Les fichiers de clé doivent être persistants, protégés et distribués séparément de l'image. Le profil `prod` désactive les clés éphémères. Les noms de propriétés sont `IDENTITY_JWT_ACTIVE_KEY_ID`, `IDENTITY_JWT_PRIVATE_KEY_LOCATION`, `IDENTITY_JWT_PUBLIC_KEY_LOCATION`; les anciennes clés publiques de vérification sont configurées dans `identity.jwt.verification-keys`.

1. Générer et vérifier une nouvelle paire RSA d'au moins 2048 bits; ne jamais placer la clé privée dans Git, une image ou un rapport CI.
2. Distribuer la nouvelle clé publique aux consommateurs ou configurer sa publication comme clé de vérification avant d'émettre des jetons avec le nouveau `kid`.
3. Déployer le service avec le nouveau `kid` actif et l'ancienne clé publique conservée dans la liste de vérification. Vérifier le JWKS et la validation de jetons nouvellement et anciennement signés.
4. Attendre la durée maximale de vie des jetons précédents et toute fenêtre de cache JWKS des consommateurs, puis retirer l'ancienne clé publique.
5. En cas de compromission, considérer les jetons signés par la clé comme compromis, retirer la clé selon la procédure d'incident et coordonner le renouvellement des clés de vérification chez les consommateurs.

La stratégie de génération, de transfert sécurisé et d'approbation des clés doit être fixée par l'exploitation avant une utilisation réelle; ce dépôt ne fournit pas de coffre-fort de clés.

## Incident de sécurité

1. Utiliser le processus d'astreinte et de gestion d'incident de l'organisation; désigner un responsable et préserver les journaux pertinents.
2. Rechercher par `X-Correlation-ID` et fenêtre temporelle. Ne pas copier de JWT, mot de passe, contenu KYC ou numéro de document dans les tickets ou journaux.
3. Pour une suspicion de compromission de compte, appliquer les mesures de suspension prévues par le processus approuvé et évaluer les jetons encore valides. La révocation immédiate et le libre-service de fermeture de compte ne sont pas encore implémentés.
4. Pour une suspicion de compromission de clé, suivre la section Rotation et évaluer les consommateurs et jetons affectés.
5. Évaluer les données consultées/exportées, préserver les éléments utiles et suivre les obligations de notification et de conservation déterminées par l'organisation.
6. Après confinement, documenter la cause, les impacts, les corrections et les actions préventives; ajouter une régression automatisée lorsqu'elle est applicable.

## Accès administrateur

Les opérations `/kyc/admin/**` exigent `ROLE_ADMIN`; l'inscription publique ne permet pas de choisir ce rôle. Pour les essais locaux seulement, `AdminBootstrapRunner` peut promouvoir un compte existant via `--identity.admin.bootstrap-only=true` et `--identity.admin.bootstrap-email=...`; il exige le profil explicite `local`, utilise un contexte sans serveur web et refuse les bases non loopback. Ce n'est pas un mécanisme d'audit/gouvernance de production. Ne pas promouvoir un compte directement en production sans mécanisme contrôlé, double vérification, journalisation et procédure de retrait validés. Ce blocage doit être résolu avant toute exploitation KYC réelle.

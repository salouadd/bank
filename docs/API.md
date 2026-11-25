# Contrat de l'API Identity

## Documentation générée

Le contrat OpenAPI est produit à partir des contrôleurs et DTO Spring :

- `/v3/api-docs` : document OpenAPI JSON;
- `/swagger-ui/index.html` : interface Swagger UI.

Ces deux routes sont publiques pour permettre la consultation du contrat. Elles ne donnent pas accès aux opérations protégées. Ne publier l'interface que sur les environnements où sa visibilité est souhaitée.

L'API n'a pas encore de préfixe de version URI (`/v1`). Le champ `info.version` décrit actuellement la version du contrat et ne constitue pas un versionnement de route. Avant une première intégration externe stable, choisir une politique de versionnement; maintenir les changements compatibles dans une version publiée et traiter les changements incompatibles comme une nouvelle version.

## Authentification et rôles

Les routes publiques sont `POST /users/register`, `POST /users/login`, `GET /.well-known/jwks.json`, les routes de santé Actuator et les routes de documentation OpenAPI. Les autres routes requièrent `Authorization: Bearer <JWT>`. Les JWT sont signés avec RS256; leur signature, expiration et utilisateur sont vérifiés par le service. Les routes `/kyc/admin/**` requièrent en plus `ROLE_ADMIN`.

| Route | Accès | Comportement |
|---|---|---|
| `POST /users/register` | Public | Crée un compte `ROLE_USER`, statut initial `PENDING_KYC` |
| `POST /users/login` | Public | Retourne un JWT Bearer; identifiants invalides: `401` |
| `GET /users/me` | Utilisateur authentifié | Retourne son profil |
| `POST /kyc/upload` | Utilisateur authentifié | Dépose un document KYC |
| `GET /kyc/documents` | Utilisateur authentifié | Retourne ses documents, avec numéro masqué |
| `GET /kyc/admin/pending` | `ROLE_ADMIN` | Liste les dossiers en attente |
| `PUT /kyc/admin/documents/{id}/verify` | `ROLE_ADMIN` | Vérifie un document en attente |
| `PUT /kyc/admin/documents/{id}/reject` | `ROLE_ADMIN` | Rejette un document en attente avec motif |
| `GET /kyc/admin/documents/{id}/file` | `ROLE_ADMIN` | Télécharge le document en pièce jointe |
| `GET /.well-known/jwks.json` | Public | Publie les clés publiques JWT de vérification |

Exemple de connexion :

```http
POST /users/login
Content-Type: application/json

{"email":"client@example.com","password":"<mot-de-passe>"}
```

La réponse contient `accessToken`, `tokenType` (`Bearer`) et `expiresIn` en secondes. Ne journaliser ni cette réponse ni le jeton.

Exemple de dépôt : `multipart/form-data` avec `documentType`, `documentNumber` et `file`. Les types acceptés sont PDF, JPEG et PNG. La limite applicative par défaut est 10 MiB. Le contenu doit correspondre à la signature attendue, et l'extension du nom de fichier doit être autorisée. Ces contrôles ne remplacent pas un antivirus/CDR.

## Erreurs et limites

Les erreurs de validation utilisent le format `status`, `error`, `message`, `path`, `timestamp` et, si pertinent, `validationErrors`. Les principaux statuts sont :

| Statut | Sens |
|---|---|
| `400` | Corps, paramètre ou fichier invalide |
| `401` | Identifiants invalides ou authentification absente/invalide |
| `403` | Rôle insuffisant |
| `404` | Ressource absente |
| `409` | Courriel existant ou transition KYC interdite |
| `413` | Taille maximale de requête dépassée |
| `500` | Erreur interne générique; les détails techniques ne sont pas retournés |

Les réponses incluent `X-Correlation-ID`. Un UUID valide fourni par le client est conservé; sinon, le service en génère un. Ne pas y placer d'adresse, de jeton ou d'autre donnée personnelle.

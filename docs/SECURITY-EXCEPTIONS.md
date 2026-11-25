# Registre des exceptions de sécurité CI

Seuils de blocage en vigueur :

- Gitleaks : toute détection bloque le job `Secret scanning`.
- Semgrep : les résultats traités comme erreurs par `--error` bloquent `SAST (Semgrep)`.
- Trivy : toute vulnérabilité `HIGH` ou `CRITICAL` bloque les jobs de dépendances et d'image.
- OWASP ZAP Baseline : une alerte `FAIL` bloque `DAST`; `-I` ne bloque pas sur les alertes `WARN`, qui restent visibles dans le rapport à examiner.

Aucune exception permanente n'est créée par défaut. Ne pas ajouter de `nosemgrep`, d'ignore Trivy, de règle Gitleaks d'exclusion ou de suppression ZAP sans revue.

Toute exception temporaire doit être liée à une issue et à une PR approuvée, et documenter :

| Champ obligatoire | Valeur |
|---|---|
| Outil, règle et finding précis | À renseigner |
| Emplacement ou dépendance concernés | À renseigner |
| Justification du risque accepté et éléments de preuve | À renseigner |
| Mesures compensatoires | À renseigner |
| Responsable nominatif de la correction | À renseigner |
| Date d'expiration (obligatoire) | À renseigner |
| Issue de suivi et approbateur | À renseigner |

Une exception expirée redevient bloquante et doit être supprimée ou renouvelée après une nouvelle revue. Ne pas inclure de secret en clair dans ce registre. Les rapports GitHub Actions sont conservés sept jours; le rapport Gitleaks est expurgé.

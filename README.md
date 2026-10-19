# continuum-backend

API partagée de Continuum — **monolithe modulaire Spring Boot** (Spring Modulith, Java 21). Une seule
application sert les deux fronts (Plateforme Fonctionnelle et Admin Système) ; la séparation passe par
la **sécurité** (audiences `continuum-app` / `continuum-admin`), pas par la duplication du code (§4.3.3).

## Modules (Spring Modulith)

`iam` (autorisation `can()`, catalogue de fonctionnalités, SoD), `audittrail` (journal chaîné + signé
HMAC), `tenancy`, `ccm` (contrôle continu), `admin`, `audit`, `connectors` (CBS), `workflow`,
`signature`, `notification`, `reporting`, `aigateway` (post-MVP). Les frontières sont vérifiées par
`ModularityTests` (ArchUnit) : un module n'accède qu'à l'API publique (`*.api`) ou aux événements d'un
autre, jamais à ses entités ni repositories.

## Prérequis

Java 21, Maven 3.9. Base **PostgreSQL** ou **Oracle** (D4). Migrations appliquées par le
`continuum-migrator` (jamais par le backend, D33).

## Construire & tester

```bash
mvn -o verify                      # build + tests unitaires (sans base)
# Tests d'intégration sur base réelle (sinon neutralisés par @Assumptions / @EnabledIfSystemProperty) :
mvn -o surefire:test -Dtest=<Classe> \
  -Dit.db.url=jdbc:postgresql://localhost:5432/<db> -Dit.db.user=<u> -Dit.db.password=<p>
# Parité Oracle (tests dédiés) : -Dit.oracle.url=jdbc:oracle:thin:@host:1521/FREEPDB1 -Dit.oracle.user=… -Dit.oracle.password=…
```

> Sur poste à mémoire contrainte, lancer les tests Oracle **seuls** et séparément des tests de contexte
> Spring (`ApplicationBootTest`) — voir `Instructions/docs/suivi/ETAT.md` (Points d'attention).

## Lancer

Le migrateur doit avoir migré la base **avant** le backend (garde-fous de démarrage, ci-dessous).

```bash
CONTINUUM_DB_URL=jdbc:postgresql://localhost:5432/continuum \
CONTINUUM_DB_USER=… CONTINUUM_DB_PASSWORD=… \
CONTINUUM_OIDC_ISSUER_URI=http://localhost:8180/realms/banque-demo \
CONTINUUM_AUDITTRAIL_HMAC_KEY=… \
mvn -o spring-boot:run
```

Pour une pile locale complète (simulation + migrateur + backend + fronts), utiliser `../run.ps1 -Mode full`.

## Configuration (properties / env)

Paramètres **techniques** seulement (le métier se paramètre en base, par tenant — voir `Instructions/CLAUDE.md`) :
`CONTINUUM_DB_URL/USER/PASSWORD`, `CONTINUUM_DB_DIALECT` (`postgresql`|`oracle`), `CONTINUUM_OIDC_ISSUER_URI`,
`CONTINUUM_AUDITTRAIL_HMAC_KEY` (depuis Vault, obligatoire), `CONTINUUM_SURFACE`
(`all`|`app`|`admin`|`worker`). Détail : `src/main/resources/application.yml` et
`Instructions/docs/exploitation/DEPLOIEMENT.md`.

## Surface de déploiement (`CONTINUUM_SURFACE`)

Même jar, rôles différents (`DeploymentSurface`) : `app` (API Fonctionnelle), `admin` (API Admin),
`worker` (planification continue CCM uniquement), `all` (tout-en-un, défaut). Le gate `worker` évite
qu'une API répliquée duplique les travaux de fond.

## Garde-fous de démarrage (fail-closed)

Le backend **vérifie** la base au démarrage (`ApplicationReadyEvent`) et refuse de démarrer sinon :
`CTN-DB-0001` (schéma absent), `CTN-DB-0002` (catalogue de fonctionnalités en dérive),
`CTN-DB-0003` (schéma plus ancien que `REQUIRED_SCHEMA_VERSION`). ⇒ toujours exécuter le migrateur avant.

## Sécurité

Deux chaînes (`SecurityConfig`) : `/api/admin/**` (audience `continuum-admin` + `acr ≥ 2`) et
`/api/app/**` (audience `continuum-app`), plus une chaîne par défaut (actuator/public ouverts).
Sécurité de méthode `@PreAuthorize("@authz.can(...)")` par-dessus (§2.1.7).

## Références

Contrats OpenAPI : `../Instructions/contracts/`. Plan : `../Instructions/plan-plateforme-audit-interne-erp.md`
(§4.3). Suivi : `../Instructions/docs/suivi/`.

# Organisation du fork et mises à jour amont

Le dépôt officiel reste le point de départ. Chaque ajout est une branche indépendante, puis la branche `fork/integration` les réunit. L'ancien prototype reste disponible dans `archive/initial-prototype` et `feat/private-equity-and-goals`.

| Branche | Rôle | Points d'entrée principaux |
| --- | --- | --- |
| `upstream-baseline` | Code amont sans modifications, actuellement `e7fead8` | Dépôt `portfolio-performance/portfolio` |
| `feature/pe-operations` | Appels de fonds, distributions, valorisation et performance ; saisie et persistance | `model/PrivateEquityValuation.java`, types d'opérations, calculs `snapshot/`, `PrivateEquityValuationTest` |
| `feature/allocation-widgets` | Les deux widgets, configuration et traductions | `ui/views/dashboard/AllocationGoalWidget.java`, `WidgetFactory.java`, `allocationgoals*.properties`, `AllocationGoalWidgetTest` |
| `feature/vivid-branding` | Palette vive du logo, sans changement financier | `fork-branding/`, `ui/branding/ForkBranding.java`, six références dans `Images.java` |
| `fork/platform` | Fabrication Mac, Java embarqué, espace de travail distinct et protection contre les mises à jour officielles | `private-equity-product/`, profil Maven `private-equity`, initialisation des préférences et mises à jour |
| `fork/integration` | Réunit les quatre branches, le guide et le portefeuille de démonstration | Ce document, `PRIVATE_EQUITY.md`, exemples |

Les chemins `model/` et `snapshot/` sont dans le module `name.abuchen.portfolio/src/name/abuchen/portfolio/`. Les chemins `ui/` sont dans `name.abuchen.portfolio.ui/src/name/abuchen/portfolio/`.

Les trois branches fonctionnelles et la branche de fabrication partent du même commit amont ; aucune n'intègre une autre fonctionnalité. Elles n'ont pas de fichiers modifiés en commun. Les opérations nécessitent néanmoins des points d'intégration dans les calculs existants : les déplacer entièrement dans un module externe masquerait cette dépendance. Leur logique de valorisation dédiée reste dans `PrivateEquityValuation` et leurs régressions dans une classe de tests propre.

## Développer un ajout

Faire les corrections d'une fonctionnalité sur sa branche, y exécuter ses tests, puis fusionner cette branche dans `fork/integration`. Réserver les commits directs sur l'intégration aux guides et exemples communs. Ne pas mélanger une correction financière avec une modification de logo ou de widget dans le même commit.

Les ajouts peuvent être sélectionnés à la fabrication : une variante sans opérations PE peut, par exemple, partir de `upstream-baseline` puis fusionner seulement `fork/platform`, `feature/allocation-widgets` et `feature/vivid-branding`. Le fichier d'exemple combiné nécessite les opérations et les widgets ; ne pas le livrer avec une variante qui les omet.

## Intégrer une future version officielle

`upstream` pointe vers le projet officiel ; `origin` vers `ndeber/portfolio`. Choisir de préférence une version officielle publiée et tester sa compatibilité avec les fichiers du fork.

```sh
git fetch upstream --tags
git switch upstream-baseline
# Remplacer <version-officielle> par le tag/commit choisi, après inspection.
git merge --ff-only <version-officielle>

git switch feature/pe-operations
git merge upstream-baseline
# Résoudre les conflits, puis exécuter les tests PE.

git switch feature/allocation-widgets
git merge upstream-baseline
# Résoudre les conflits, puis exécuter les tests des widgets.

git switch feature/vivid-branding
git merge upstream-baseline
# Vérifier la palette si le logo officiel a changé.

git switch fork/platform
git merge upstream-baseline
# Vérifier les numéros de version Maven/produit et le JDK requis.

git switch fork/integration
git merge upstream-baseline
git merge feature/pe-operations
git merge feature/allocation-widgets
git merge feature/vivid-branding
git merge fork/platform
```

Ces fusions préservent les commits déjà publiés et ne nécessitent pas de publication forcée. Si la nouvelle base ne descend pas de la précédente, `--ff-only` s'arrête : examiner ce changement d'historique avant de continuer. La copie initiale est peu profonde ; pour une recherche dans des versions amont plus anciennes, utiliser d'abord `git fetch --unshallow upstream`.

Points sensibles lors d'une mise à jour : enum des opérations, persistance protobuf (identifiants 1001/1002), filtres de portefeuilles, calculs de performance et de coût, enregistrement des widgets, cibles Eclipse/Tycho et identité du produit Mac. Ne pas désactiver un test financier pour contourner un conflit.

## Vérification et fabrication

JDK 21 et Maven sont requis pour cette base amont. L'environnement de développement local utilise également JDK 21.

```sh
# Sur la branche PE (ou sur l'intégration)
mvn -f portfolio-app/pom.xml -Plocal-dev verify \
  -Dtest=PrivateEquityValuationTest,AccountListViewTest -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false

# Sur la branche widgets (ou sur l'intégration)
mvn -f portfolio-app/pom.xml -Plocal-dev verify \
  -Dtest=AllocationGoalWidgetTest -DfailIfNoTests=false \
  -Dsurefire.failIfNoSpecifiedTests=false

# Application combinée : tests et fabrication du produit
python3 private-equity-product/prepare-oauth.py \
  --from-app /Applications/PortfolioPerformance.app
mvn -f portfolio-app/pom.xml -Pprivate-equity clean verify \
  -Dtest=PrivateEquityValuationTest,AccountListViewTest,AllocationGoalWidgetTest \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false

# Archive Mac autonome, avec l'identité vive
python3 private-equity-product/package-macos.py /chemin/vers/nouvelle-livraison \
  --branding fork-branding
```

Pour vérifier l'indépendance, repartir d'une compilation propre (`clean verify`) sur chaque branche. Pour une nouvelle version amont, étendre ensuite les tests aux calculs existants ; sur l'intégration, `mvn -f portfolio-app/pom.xml -Plocal-dev clean verify` lance l'ensemble des tests. Les tests dédiés ne remplacent pas cette régression lors d'une mise à jour majeure.

Le paramètre `--branding` agit sur l'icône Finder/Dock et active les logos de l'accueil, À propos et des fenêtres. L'omettre conserve les couleurs officielles. Les fichiers amont du logo ne sont pas remplacés. Les images de la variante sont versionnées et régénérables depuis le SVG avec `fork-branding/generate.py`.

Le [guide de fabrication Mac](BUILD_MAC.md) explique la préparation de la configuration
publique du fournisseur de cours. Le contrôle de l'archive doit rester actif même
pour une compilation de développement sans signature officielle.

## Publication GitHub

La connexion passe par GitHub CLI sur ce Mac. Aucun mot de passe ni jeton ne doit être collé dans une conversation ou un fichier du dépôt.

```sh
gh auth login --hostname github.com --git-protocol https --web
gh auth setup-git --hostname github.com
gh auth status
```

Choisir le compte `ndeber` dans le navigateur et examiner les autorisations GitHub CLI affichées. L'outil utilise normalement le trousseau du système ; la documentation indique un stockage dans un fichier en repli si le trousseau n'est pas disponible. Vérifier le résultat de `gh auth status`. La connexion peut être révoquée dans GitHub, Settings > Applications > Authorized OAuth Apps.

Après connexion, publier les branches explicitement (pas les branches d'archive) :

```sh
git push -u origin upstream-baseline fork/platform feature/pe-operations \
  feature/allocation-widgets feature/vivid-branding fork/integration
```

Pour limiter strictement l'accès à ce seul dépôt, l'alternative est un jeton personnel à permissions fines limité à `ndeber/portfolio`, avec `Contents: Read and write` et, si des pull requests sont souhaitées, `Pull requests: Read and write`. Le configurer localement via une saisie masquée et le trousseau, jamais dans l'URL Git ou l'historique des commandes. Une connexion OAuth GitHub CLI peut donner accès à davantage de dépôts selon les autorisations du compte.

Références : [connexion GitHub CLI](https://cli.github.com/manual/gh_auth_login), [configuration de Git](https://cli.github.com/manual/gh_auth_setup-git), [jetons à permissions fines](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens).

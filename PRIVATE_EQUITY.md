# Portfolio Performance PE

Version personnelle de Portfolio Performance pour macOS Apple Silicon, avec appels de fonds, distributions et objectifs d'allocation. Le fork part du code amont `e7fead8` (0.87.1 en développement).

## Appels de fonds et distributions

Les deux opérations sont disponibles dans **Transaction**, dans le menu d'un titre et dans celui d'un compte espèces. Choisir le fonds, le compte espèces, la date et le montant. Aucun nombre de parts n'est à saisir.

| Opération | Compte espèces | Parts détenues | Valeur du fonds |
| --- | --- | --- | --- |
| Appel de fonds | Débit du montant | Inchangées | Augmente du montant |
| Distribution | Crédit du montant | Inchangées | Diminue du montant |

Exemple : 100 parts à 100 EUR valent 10 000 EUR. Un appel de 2 000 EUR porte la part à 120 EUR. Une distribution ultérieure de 500 EUR la ramène à 115 EUR. Le patrimoine total et la performance ne changent pas du seul fait de ces opérations, hors variation de change.

Pour un nouveau fonds, créer d'abord les parts avec un achat ou une livraison entrante. Une livraison de parts à valeur nulle permet de représenter une souscription avant le premier appel de fonds. Le programme refuse un appel sans parts et une distribution qui ferait passer la valorisation calculée sous zéro.

### Valorisation du fonds

Les cours historiques saisis restent les valeurs liquidatives communiquées par le fonds. Chaque cours est une **valeur de fin de journée qui inclut les opérations de cette même date**. Les appels et distributions postérieurs ajustent cette référence jusqu'au prochain cours communiqué. Saisir la nouvelle valeur liquidative à sa date de valorisation, et non à sa date de réception.

Les ajustements sont calculés à partir des opérations, sans écraser les cours historiques. Corriger une opération, son montant ou sa date recalcule donc la valorisation. La précision des cours de Portfolio Performance peut entraîner des écarts d'arrondi pour des fractions de parts.

Si le même titre est détenu dans plusieurs portefeuilles, l'ajustement par part s'applique à l'ensemble des parts détenues. Utiliser des titres distincts pour des engagements ou classes de parts ayant des appels et valeurs liquidatives indépendants.

Le montant peut être débité ou crédité dans une autre devise : renseigner alors le montant en devise du fonds et le taux de change dans le formulaire. Les frais et impôts éventuels sont des opérations séparées. Une distribution est traitée comme un flux de capital sans distinction fiscale entre capital remboursé et revenu ; les réévaluations du fonds portent la performance.

## Widgets

Dans un tableau de bord, ajouter les widgets depuis la rubrique du patrimoine :

- **Allocation : réelle et cible** : tableau des catégories et sous-catégories avec valeur actuelle, poids actuel, poids cible et écart en points de pourcentage. Les objectifs reprennent ceux de la taxonomie. Le menu permet de choisir la taxonomie, une catégorie de départ et un filtre de comptes/portefeuilles. Le dénominateur est le montant classé dans la catégorie choisie ; les actifs non classés sont exclus, comme dans le calcul des objectifs de la taxonomie.
- **Objectif fixe en EUR** : valeur de la catégorie choisie, objectif fixe, montant restant (ou dépassement) et pourcentage atteint. Le sous-total comprend les sous-catégories et respecte les pondérations d'affectation. La valeur est convertie en EUR à la date courante. Définir la cible dans le menu du widget ; elle doit être positive et n'est pas plafonnée lorsque l'objectif est dépassé.

Les choix et les objectifs sont sauvegardés avec le tableau de bord. Si une catégorie sélectionnée est supprimée, le widget demande une nouvelle sélection.

## Application et fichiers

L'application s'appelle **PortfolioPerformancePE.app** et utilise un espace de travail distinct de l'application officielle. Les mises à jour vers les binaires officiels sont désactivées dans cette variante pour préserver les extensions.

La livraison avec l'identité **Vivid PE** reprend le même dessin de logo dans une palette violet, rose vif et cyan, dans le Finder/Dock et dans les logos de l'application. Quitter une version PE déjà ouverte avant d'ouvrir la nouvelle livraison ; les deux versions du fork utilisent le même espace de travail.

Commencer avec une **copie** du fichier de portefeuille et conserver l'original pour Portfolio Performance officiel. Le fork lit les fichiers existants et sauvegarde les nouveaux types dans les formats XML et `.portfolio`. Dès qu'ils contiennent ces opérations, les fichiers nécessitent le fork pour être rouverts. Cette version de développement n'est pas signée ou notariée par Apple.

Pour essayer les fonctions, ouvrir **PE-Demo.portfolio** depuis le menu **Fichier > Ouvrir** du fork, puis choisir le tableau de bord **Allocation et objectifs**. Le fonds vaut 11 500 EUR pour 100 parts, les liquidités 13 500 EUR, l'allocation PE 46 % face à une cible de 60 %, et l'objectif PE de 20 000 EUR est atteint à 57,5 %. Le même exemple est fourni en XML pour inspection.

## Développement

Prérequis : JDK 21 et Maven. Les dépendances sont téléchargées lors de la première compilation.

```sh
# Tests des fonctionnalités (inclure les modules nécessaires au lancement OSGi)
mvn -f portfolio-app/pom.xml -Plocal-dev verify \
  -Dtest=PrivateEquityValuationTest,AccountListViewTest,AllocationGoalWidgetTest \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false

# Préparer la configuration publique du fournisseur intégré, sans session utilisateur
python3 private-equity-product/prepare-oauth.py \
  --from-app /Applications/PortfolioPerformance.app

# Construire l'application PE pour Mac Apple Silicon
mvn -f portfolio-app/pom.xml -Pprivate-equity -DskipTests clean verify

# Ajouter un environnement Java embarqué et créer l'archive autonome
python3 private-equity-product/package-macos.py /chemin/vers/nouvelle-livraison \
  --branding fork-branding
```

Le produit brut est créé dans `private-equity-product/target/products`. Le script de livraison utilise le JDK désigné par `JAVA_HOME`, ajoute un environnement Java embarqué, une signature locale ad hoc et le fichier d'exemple `PE-Demo.xml`. Il faut l'exécuter sur un Mac Apple Silicon. L'archive finale fonctionne sans installation de Java séparée.

La préparation de la connexion et les contrôles de configuration sont détaillés dans
[le guide de fabrication Mac](docs/fork/BUILD_MAC.md). L'archive contient cette
configuration ; elle ne copie aucune session. Dans l'application, se connecter à
son compte Portfolio Performance pour les instruments utilisant le fournisseur intégré.

Les identifiants protobuf 1001 et 1002 sont réservés aux opérations du fork. Aucun changement de nombre de parts n'est encodé pour un appel ou une distribution.

L'organisation des branches et la procédure de mise à jour sont décrites dans [le guide de maintenance](docs/fork/MAINTENANCE.md). Les opérations, widgets et logos peuvent être repris séparément. La fabrication avec la palette originale reste possible en omettant `--branding fork-branding`.

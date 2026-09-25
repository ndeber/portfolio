# Widgets d’allocation et d’objectif

Branche indépendante : `feature/allocation-widgets`. Ces widgets fonctionnent avec les opérations et les valorisations standard de Portfolio Performance ; ils ne nécessitent pas les opérations PE/VC.


Dans un tableau de bord, ajouter les widgets depuis la rubrique du patrimoine :

- **Allocation : réelle et cible** : tableau des catégories et sous-catégories avec valeur actuelle, poids actuel, poids cible et écart en points de pourcentage. Les objectifs reprennent ceux de la taxonomie. Le menu permet de choisir la taxonomie, une catégorie de départ et un filtre de comptes/portefeuilles. Le dénominateur est le montant classé dans la catégorie choisie ; les actifs non classés sont exclus, comme dans le calcul des objectifs de la taxonomie.
- **Objectif fixe en EUR** : valeur de la catégorie choisie, objectif fixe, montant restant (ou dépassement) et pourcentage atteint. Le sous-total comprend les sous-catégories et respecte les pondérations d'affectation. La valeur est convertie en EUR à la date courante. Définir la cible dans le menu du widget ; elle doit être positive et n'est pas plafonnée lorsque l'objectif est dépassé.

Les choix et les objectifs sont sauvegardés avec le tableau de bord. Si une catégorie sélectionnée est supprimée, le widget demande une nouvelle sélection.


## Code et validation

Les deux widgets sont regroupés dans `ui/views/dashboard/AllocationGoalWidget.java`, avec leurs libellés `allocationgoals.properties` et `allocationgoals_fr.properties`. Seul leur enregistrement est ajouté au registre existant `WidgetFactory`. Les identifiants persistés sont `ALLOCATION_TABLE` et `FIXED_EUR_GOAL`.

Tests dédiés : `AllocationGoalWidgetTest` (4 scénarios). Exécuter avec `mvn -f portfolio-app/pom.xml -Plocal-dev verify -Dtest=AllocationGoalWidgetTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`.

### Widgets « Taxonomie : répartition réelle » et « Taxonomie : répartition cible »

Deux widgets indépendants affichent chacun un seul camembert : les valeurs
actuelles ou les poids cibles. Chacun permet de choisir sa taxonomie, puis une
catégorie à n'importe quelle profondeur via **Sous-catégorie**. Seuls les enfants
directs de la catégorie choisie sont représentés ; leurs montants incluent leurs
descendants. Aucun tableau, sous-titre, titre de graphique ou nom de secteur
n'est affiché. Les pourcentages arrondis sans décimales sont affichés dans les
secteurs suffisamment larges ; les noms et pourcentages précis restent accessibles
au survol. Les deux widgets reprennent les couleurs des catégories de la taxonomie.

L'option **Masquer les sans classification**, activée par défaut, enlève ces
catégories du graphique. Les pourcentages sont ramenés aux seules catégories
visibles ; les montants et objectifs enregistrés ne sont pas modifiés.
La hauteur est réglable séparément pour chaque widget.

Les cibles utilisent les poids relatifs à la catégorie choisie, même sans valeur
actuelle. Les poids manquants figurent comme « Cible non répartie ». Les valeurs
négatives ou objectifs incohérents désactivent le graphique concerné ; le message
d'explication est accessible au survol du titre. Une catégorie supprimée nécessite
une nouvelle sélection.

Compatibilité : l'ancien widget comparatif `TAXONOMY_SUBLEVEL` devient le widget
réel et conserve ses réglages. Ajouter `TAXONOMY_SUBLEVEL_TARGET` pour la cible.

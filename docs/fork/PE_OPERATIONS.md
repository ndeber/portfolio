# Opérations Private Equity / Venture Capital

Branche indépendante : `feature/pe-operations`. Elle contient la saisie, la persistance et tous les ajustements de calcul liés aux appels de fonds et distributions, sans les nouveaux widgets ni l’identité visuelle.


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


## Code et validation

La valorisation dédiée est centralisée dans `name.abuchen.portfolio/model/PrivateEquityValuation.java` (sous `src/name/abuchen/portfolio`). Les adaptations de filtres, coûts et performances restent au plus près des calculs amont qu’elles complètent. Les menus et le formulaire utilisent `AccountTransactionModel`. La persistance protobuf utilise les identifiants 1001 et 1002.

Tests dédiés : `PrivateEquityValuationTest` (13 scénarios). Exécuter avec `mvn -f portfolio-app/pom.xml -Plocal-dev verify -Dtest=PrivateEquityValuationTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`.

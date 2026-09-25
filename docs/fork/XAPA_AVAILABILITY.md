# Disponibilités Xapa et appels de fonds

Branche `feature/xapa-availability`, fondée sur l'intégration des engagements PE.
Le calcul est isolé dans `commitments/Availability.java` et l'affichage dans
`ui/views/dashboard/AvailabilityWidget.java`.

## Taxonomie et périmètre

La taxonomie **Date de disponibilité** contient **Immédiate**, des catégories
annuelles (par exemple 2027 à 2034), et **À planifier**. Les affectations sont les
pourcentages de la valeur actuelle d'un actif. Répartir un fonds sur plusieurs
années pour modéliser une distribution progressive, avec 100 % au total.
Les distributions projetées en euros ne sont pas des valorisations futures.
La marge prudente d'un an est appliquée à la saisie des années, une seule fois.
Les montants déjà reçus ne sont pas inclus dans les proportions restantes.

Seuls les comptes et dépôts dont le nom commence exactement par **Xapa - **
sont valorisés par le widget. Un titre partagé avec un dépôt personnel ne compte
que pour les parts détenues dans les dépôts Xapa. Phacet est exclu des
Disponibilités comme des engagements. Les comptes négatifs réduisent le disponible.
La vue standard des taxonomies doit utiliser le filtre **Xapa** pour afficher le
même périmètre ; les pourcentages d'un titre sont partagés par ses différentes
positions. Le widget applique son périmètre automatiquement.

Les comptes espèces sont affectés à Immédiate. Les placements liquides y sont
également affectés explicitement ; la présence d'un cours ou d'un symbole Yahoo
ne suffit pas à rendre liquide un fonds privé. Les échéances inconnues restent
À planifier. Les répartitions peuvent être modifiées dans la taxonomie standard.
Aucun actualisateur de marché n'est lancé par ce widget.

## Widget

Ajouter **Xapa : disponibilités et appels de fonds** dans la rubrique patrimoine.
Il trouve la taxonomie par son nom, ou utilise l'identifiant sélectionné dans son
menu. Une sélection explicite survit au renommage. Une catégorie absente, inconnue
ou une fraction non affectée reste à planifier ; plus de 100 % produit une erreur
explicite. Les erreurs de change ne sont jamais remplacées par un taux implicite 1:1.

Les colonnes montrent les nouvelles disponibilités, le cumul, les appels, leur
cumul et le solde. Les appels suivent les engagements PE existants, limités aux
opérations Xapa. Les exclusions Cowboy, Phacet et Checkout des engagements restent
applicables. Les disponibilités excluent uniquement Phacet, selon la demande.

Les appels sont détaillés en 2026, 2027, 2028 puis regroupés en **2029+**. Aucun
solde annuel précis n'est donc affiché à partir de 2029. Une ligne récapitulative
2029+ compare tous les flux prévus sur cet horizon, sans supposer que des fonds
libérés en 2034 financeraient un appel en 2029. Elle ne garantit pas la couverture
à l'intérieur de cette période. À planifier est exclu des cumuls disponibles.
Des engagements incomplets ou un écart de ventilation désactivent les soldes.

La valeur actuelle reste une estimation de liquidité : les dates ne sont pas des
transactions. Après une distribution réelle, revoir la répartition du solde du
fonds pour ne pas compter le même montant avec le cash reçu. Les années fixes ne
se décalent pas toutes seules au changement d'année.

## Validation

Tests synthétiques : positions partagées Xapa/personnel, Phacet, répartition
partielle, poids excessifs, arrondis, relecture, revalorisation, appels Xapa,
absence réelle de taux, sélection de taxonomie et suppression sans repli global.
Le contrôle OSGi `check-packaged-pdf.py --commitments` vérifie également la
valorisation des disponibilités et le chargement du nouveau widget.
Les échéanciers personnels et les portefeuilles ne sont pas publiés dans Git.

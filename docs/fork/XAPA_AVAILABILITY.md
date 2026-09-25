# Disponibilités Xapa et appels de fonds

Branche `feature/xapa-availability`, fondée sur l'intégration des engagements PE.
Le calcul est isolé dans `commitments/Availability.java` et l'affichage dans
`ui/views/dashboard/AvailabilityWidget.java`.

## Tableau de référence

Ouvrir **Outils du portefeuille → Renseigner les disponibilités Xapa…**, ou le
bouton du widget. Ce tableau est la source de vérité : il conserve dans le
portefeuille les pourcentages de chaque fonds pour Immédiate et 2026–2034.
Les comptes espèces Xapa sont automatiquement à 100 % dans Immédiate ; ils sont
visibles dans le tableau mais leur règle n'est pas modifiable.

Les modifications restent en mémoire jusqu'à **Appliquer et recalculer la
taxonomie**. Annuler ne change rien. La somme ne doit pas dépasser 100 % ; le
complément est **À planifier**. Les totaux estimés en EUR s'appuient sur les
valeurs courantes à l'ouverture du tableau. Les champs acceptent deux décimales.
Les années saisies incluent déjà toute marge de prudence : aucun décalage n'est
appliqué automatiquement par le recalcul.

À la première ouverture seulement, les répartitions existantes de **Date de
disponibilité** sont proposées. L'import ne modifie pas le portefeuille avant
application. Ensuite, les saisies sont conservées séparément dans la propriété
`fork.xapa.availability.plan.v1` (version, identifiant de taxonomie, UUID des titres,
poids entiers au centième de pourcentage). Les titres dont la position a été soldée
restent dans le tableau lorsqu'une répartition existe déjà.

## Recalcul de la taxonomie

Appliquer enregistre la source et reconstruit sa taxonomie. Le menu du widget
contient aussi **Recalculer la taxonomie depuis le tableau**. Les identifiants de
la taxonomie, de sa racine et des années existantes sont conservés. Les répartitions
et sous-catégories de cette taxonomie dédiée sont remplacées par la projection du
tableau ; les autres taxonomies, les objectifs, les opérations et les cours restent
inchangés. Les modifications manuelles de la taxonomie dérivée ne changent pas la
source et seront remplacées au prochain recalcul.

Le widget lit directement les saisies dès qu'elles existent. Avant leur première
application il reste compatible avec la taxonomie v19. Un tableau illisible, un
poids invalide ou une taxonomie référencée supprimée produit une erreur explicite,
sans repli vers tout le portefeuille. Un fonds supprimé du portefeuille doit être
restauré pour que ses saisies ne soient pas effacées implicitement.

## Périmètre et comparaison

Seuls les comptes et dépôts **Xapa - ** sont valorisés. Un titre partagé avec un
dépôt personnel ne compte que pour les parts détenues dans Xapa. Phacet est exclu.
Les comptes négatifs réduisent le disponible. Dans la vue standard des taxonomies,
sélectionner le filtre Xapa ; le widget applique le périmètre automatiquement.

Le widget **Xapa : disponibilités et appels de fonds** montre les disponibilités
nouvelles et cumulées, les appels annuels et cumulés, et le solde jusqu'en 2033.
Les montants précédemment saisis dans 2029+ sont repris en 2029 conformément à la
demande. Les disponibilités 2034 restent affichées, mais les appels ne sont pas
supposés nuls au-delà de leur horizon 2033. Les engagements incomplets ou un écart
de ventilation désactivent les soldes. À planifier ne compte pas dans le disponible.

Les montants sont des pourcentages de la valeur actuelle, pas des rendements
futurs. Les distributions projetées ne créent aucune transaction. Après une
distribution réelle, revoir la répartition du solde du fonds pour éviter un double
comptage avec le cash reçu. Les années ne se décalent pas automatiquement.

## Validation

Tests synthétiques : positions partagées Xapa/personnel, Phacet, répartition
partielle, poids excessifs, arrondis, relecture, revalorisation, appels Xapa,
absence réelle de taux, sélection de taxonomie et suppression sans repli global.
Sont aussi testés la conservation des saisies, l’import initial en lecture seule,
la validation atomique, le recalcul stable et l’indépendance vis-à-vis d’une
modification manuelle de la taxonomie. Le contrôle OSGi `check-packaged-pdf.py --commitments` vérifie également la
valorisation des disponibilités et le chargement du nouveau widget.
Les échéanciers personnels et les portefeuilles ne sont pas publiés dans Git.

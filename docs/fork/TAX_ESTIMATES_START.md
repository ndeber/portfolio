# Essayer la fiscalité et la performance nette estimées — v3

**Document historique du prototype v3. Pour la livraison DBi V15, les ventes, les paiements rapprochés et les cinq indicateurs natifs, utiliser [le guide V15](TAX_PERFORMANCE.md) et `Fiscalite-Performance-Demo.xml`. Les limites décrites ci-dessous concernent le prototype.**

Cette version ajoute des colonnes natives dans **État des actifs** et conserve
**Rapports → Fiscalité estimée** pour le paramétrage et le détail par portefeuille. Elle calcule les estimations position par position, sans compensation
entre lignes. Les profils et taux sont des hypothèses de suivi à renseigner.

## Dans « État des actifs »

Ouvrir le nouveau fichier `Fiscalite-Demo.xml`, puis **Rapports → État des actifs**.
Les configurations en haut de la vue permettent de choisir :

- **Brut et net estimé** : valeur de marché, impôt latent, valeur nette et performances avant/après ;
- **Valeurs brutes** : valeur de marché et quantités ;
- **Décomposition fiscale** : détail des quatre colonnes de performance et de la fiscalité latente.

Les performances de la démo commencent le **19 septembre 2026** et se terminent
à la date choisie dans la vue. Les colonnes sont disponibles dans le menu des
colonnes, groupe **Fiscalité estimée** ; chaque colonne de performance peut recevoir
une période, comme les indicateurs habituels. Choisir la même période pour les comparer.
Les configurations existantes des autres fichiers sont conservées.

« État des actifs » rassemble les positions d'un même titre : le titre A affiche
750 EUR d'impôt latent (500 CTO + 250 holding), 23 250 EUR nets et 1 500 EUR de
performance nette sur la période. Le filtre des comptes/portefeuilles permet de
voir uniquement le CTO, la holding ou l'AV. Les calculs restent indépendants par
position avant leur addition ; les pondérations de propriété et de classification
sont respectées.

Le titre C non paramétré affiche **—** dans ses colonnes fiscales. Les totaux
fiscaux et nets d'« État des actifs » restent donc **—** tant que toutes les
positions affichées ne sont pas couvertes : aucune estimation partielle n'est
présentée comme un total complet. La vue fiscale détaillée conserve ses totaux
partiels explicitement accompagnés de leur couverture.

Aucun compte espèces virtuel n'est créé. **Valeur nette = valeur de marché des
actifs (espèces réelles comprises) − contribution latente estimée des titres**.
Un montant négatif est une économie potentielle, sans garantie de récupération.
Les performances fiscales portent sur les **titres détenus**, pas sur les espèces
ni sur l'ensemble des titres déjà vendus. Les rendements natifs restent inchangés.
Les colonnes se recalculent lors de l'actualisation habituelle des données/cours ;
elle n'ajoute aucun abonnement à un flux de cotations.

Un paiement réel reste une seule opération sur son compte espèces. Si des gains
ont déjà été taxés, leur base fiscale doit être ajustée après la clôture pour
ne pas les provisionner à nouveau. Cette version ne rapproche pas automatiquement
les paiements d'IS et les positions : ce rapprochement appartient au futur registre.

## Démonstration

1. Ouvrir l'application de cette livraison puis `Fiscalite-Demo.xml`.
2. Choisir **Fiscalité estimée** dans la barre latérale.
3. Le titre A du CTO vaut 12 000 EUR, avec une base de 10 000 EUR : sa
   contribution est de +500 EUR au taux fictif de 25 %.
4. Le titre B du CTO vaut 9 000 EUR, avec la même base : sa contribution est de
   −250 EUR, une économie potentielle.
5. Le même titre A dans la holding a sa propre base corrigée de 11 000 EUR : sa
   contribution est de +250 EUR. Le support AV est une convention indépendante.
6. Le titre C reste « À paramétrer » et n'entre pas dans les totaux calculés.

Pour vérifier le mouvement avec les cours, modifier la valorisation du titre A
dans les cours historiques de la démonstration, puis revenir à la vue fiscale.
À 130 EUR par unité, ses 100 unités donnent une contribution de +750 EUR dans le
CTO et +500 EUR dans la holding. Le bouton **Actualiser** recalcule les valeurs
déjà présentes ; il ne lance pas un téléchargement de cours.

Le taux de 25 % est purement illustratif pour toutes les lignes de la démonstration.
Le fichier contient un paiement fictif d'impôt de 500 EUR sur le compte espèces :
il règle une dette antérieure au début de période et n'est ni modifié, ni recopié
comme nouvelle charge fiscale des titres.

## Les quatre colonnes de performance

Elles se trouvent immédiatement après le titre et le compte. La période de la
démonstration va du 19 au 29 septembre 2026 ; les cours restent ensuite identiques
tant qu'ils ne sont pas modifiés. Le bouton **Début de période…** permet de changer
la date initiale. La fin de période est aujourd'hui.

| Position | Avant impôts EUR | Fiscalité acquise EUR | Variation latente EUR | Net estimé EUR |
| --- | ---: | ---: | ---: | ---: |
| Titre A — CTO | +1 000 | 0 | +250 | +750 |
| Titre B — CTO | −500 | 0 | −125 | −375 |
| Titre A — Holding | +1 000 | 0 | +250 | +750 |
| Titre B — AV (convention) | −500 | 0 | −125 | −375 |
| Total des quatre positions calculées | +1 000 | 0 | +250 | +750 |

Le titre A du CTO valait 11 000 EUR au début, avec 250 EUR de fiscalité latente,
contre 12 000 EUR et 500 EUR à la fin. On soustrait donc **250 EUR de variation**,
pas les 500 EUR de contribution finale. Le titre A de la holding a une base fiscale
différente mais la même progression économique sur la période.

La fiscalité acquise est nulle dans ce scénario sans événement fiscal. Les
économies négatives restent potentielles. Le titre C peut afficher sa performance
avant impôts, mais ses colonnes fiscales restent vides faute de paramètres.

Cette version calcule les périodes sans achat, vente, transfert, revenu, flux PE
ou clôture à traiter. Si un tel événement est détecté, les colonnes concernées
affichent **—** avec une explication au survol. De même, les paramètres fiscaux
doivent avoir été validés au plus tard au début de période ; une modification
validée aujourd'hui ne reconstruit pas les paramètres passés.

## Paramétrer une position

Double-cliquer la ligne ou sélectionner **Paramétrer la position…**. Renseigner :

- le profil ;
- la base fiscale totale en EUR des unités actuellement détenues ;
- le taux d'estimation, explicitement saisi ;
- la prise en compte ou non des économies potentielles en cas de perte ;
- la prochaine clôture/révision, obligatoire pour l'imposition annuelle ;
- une source ou justification facultative.

Les nombres acceptent la virgule ou le point décimal. La validation enregistre
les paramètres dans le portefeuille ouvert ; utiliser ensuite **Enregistrer**
pour les conserver sur disque. **Annuler** ne change rien.

Un mouvement sur le titre rend la base à revalider, même si la quantité revient à
son niveau précédent. Les ventes partielles, transferts et nouveaux versements
ne sont donc pas interprétés automatiquement dans cette version. La date de
clôture atteinte provoque également une demande de révision.

## Lire les résultats

- **Écart fiscal** : valeur actuelle moins base fiscale ; ce n'est pas le gain
  économique total lorsque la base a déjà été ajustée fiscalement.
- **Contribution** : charge latente positive ou économie potentielle négative.
- **Valeur nette estimée** : valeur actuelle moins cette contribution latente.
- **État** : prêt, à paramétrer, à revalider, clôture atteinte ou indisponible.

Survoler une ligne pour le détail de son état. Faire défiler horizontalement le
tableau si les dernières colonnes ne sont pas visibles. Les totaux indiquent le
nombre de positions calculées et séparent les charges des économies potentielles.

## Suite prévue

Le registre des ventes et clôtures, la ventilation des paiements d'IS et les
courbes historiques avant/après fiscalité restent à développer. Les quatre
colonnes en EUR sont disponibles pour les périodes simples décrites ci-dessus ;
les rendements natifs de Portfolio Performance restent inchangés. La vue ne
calcule pas la déclaration fiscale.

La livraison inclut des contrôles automatisés de calcul, de persistance et de
saisie, ainsi qu'une relecture du fichier de démonstration. Le contrôle visuel
manuel reste à effectuer ; l'outil de pilotage était indisponible lors du premier lot.

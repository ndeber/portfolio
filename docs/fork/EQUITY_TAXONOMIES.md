# Actualiser les taxonomies Actions

Branche indépendante `feature/taxonomy-refresh`, fondée sur `feature/update-actions`.
Le contrat de notification des affectations est le même que pour ELM ; aucun
traitement ELM, obligataire ou de cotations n'est appelé.

## Utilisation

Dans chacune des trois vues **Actions - Régions (MSCI)**, **Actions - Secteurs (MSCI)**
et **Actions - Transparence**, le bouton **Actualiser les 3 taxonomies Actions…**
lance les trois mises à jour ensemble. La même commande est accessible depuis
**Outils du portefeuille → Actualiser les taxonomies Actions…**.

Les compositions sont récupérées à nouveau à chaque demande. L'aperçu affiche les
affectations avant/après, puis les sources, leurs dates et les erreurs éventuelles.
**Appliquer les changements au portefeuille ouvert** modifie uniquement les
compositions réussies. Les lignes signalées « inchangé » gardent leurs affectations.
Enregistrer ensuite normalement avec ⌘S. Annuler ne modifie rien.

La progression indique le titre et la taxonomie en cours. Chaque récupération et
lecture est limitée à 45 secondes ; un dépassement conserve les affectations
existantes et apparaît dans les avertissements. L’annulation libère la fenêtre
sans attendre la fin d’un lecteur tiers qui ignorerait les interruptions.

La portée est celle du script : titres non retirés, détenus en quantité positive
aujourd'hui dans les portefeuilles actifs, affectés sous Actions dans Classes
 d'actifs. Vanguard All-World (IE00BK5BQT80) reste inclus même sans position positive.
Les transferts entre portefeuilles sont pris en compte et les opérations futures
sont exclues. Les trois taxonomies et la catégorie Actions doivent déjà exister,
avec des noms non ambigus. Aucune taxonomie n'est créée.

## Règles et sources

- Pays et secteurs : compositions Boursorama/Morningstar, avec les correspondances
  françaises et les reliquats par zone du script. Les pages de **composition** sont
  indépendantes du fournisseur de **cours** ; cette commande ne modifie aucun
  fournisseur Yahoo ni aucune cotation.
- Vanguard Emerging Markets : tableau pays Vanguard officiel.
- Amundi PEA Emerging : répartition pays/secteurs DivvyDiary. La date de composition
  n'y est pas publiée : cela est indiqué explicitement, séparément de la consultation.
- Transparence : principales actions publiées, regroupement des noms d'un même
  émetteur, reliquat dans Other. Les ETF/fonds monétaires/futures ne sont pas
  présentés comme des actions sous-jacentes. Les fonds de fonds ne font pas l'objet
  d'une récursion automatique. Il ne s'agit donc pas d'une transparence exhaustive.
- WPEA et Amundi PEA Emerging synthétiques : dix principales valeurs de l'indice
  MSCI officiel, pas le panier de substitution ni le swap. Un autre swap détecté
  sans source d'indice déclarée est laissé inchangé pour la transparence.
- Sonova, Diageo et DocuSign : profils fixes pays/secteur du script. FUSA : profil
  fixe de la fiche Fidelity du 31 août 2026. Ces profils sont explicitement signalés
  comme fixes ; les données de plus de 90 jours sont signalées comme anciennes.
  Leur présence ne signifie pas qu'une fiche récente a été téléchargée.

Les poids sont contrôlés, arrondis en points de base et complétés à 100 %. Les
sources nulles, négatives, supérieures à 100 %, futures ou non reconnues ne
remplacent pas les affectations existantes. Les erreurs sont isolées par titre
et par taxonomie, et ne se transforment pas en exposition nulle.

## Conservation du portefeuille

Les taxonomies, catégories existantes, objectifs, identifiants, couleurs et rangs
sont conservés. Les nouvelles sociétés de la transparence sont ajoutées avec un
objectif nul. Les catégories devenues vides ne sont pas supprimées : les widgets
restent liés. Contrairement au script historique, l'arbre de transparence n'est
pas détruit/recréé ni réordonné selon sa valeur ; le tri d'affichage reste disponible
 dans Portfolio Performance.

Aucune transaction, quantité, cotation, classification obligataire, répartition
ELM par classes d'actifs, ni affectation Pilotage Global n'est modifiée. Les données
et compositions d'autres titres sont conservées. Le portefeuille doit être resté
inchangé depuis la préparation ; sinon l'application refuse l'aperçu périmé.
La provenance et les avertissements sont mémorisés dans `fork.equity.lastUpdate`.

## Code et validation

- `updates/equity/EquitySources` : récupération et parseurs, cache limité à une demande.
- `profiles.json` : conventions publiques et profils fixes du script, sans chemins privés.
- `EquityComposition` : normalisation des noms et contrôle des poids.
- `EquityAdjustment` : périmètre, aperçu et application contrôlée.
- `ui/updateactions/equity` et `model/equity-refresh.e4xmi` : bouton/menu et aperçu.

Tests : conservation, idempotence, aperçu périmé, sources partielles, catégories
ambiguës, consolidation des émetteurs, arrondis, dates, périmètre des positions et
relecture du modèle. Les parseurs MSCI, Vanguard, Boursorama et DivvyDiary sont
également vérifiés en direct avant livraison. Les tests couvrent aussi les délais
et l’annulation, y compris lorsqu’un lecteur ignore les interruptions.

## Réparation des libellés MSCI de la v13

L'extraction reconnaît désormais les statistiques de la colonne voisine et vérifie
les dix noms, leur unicité et la cohérence des poids avec le total du tableau.
Une structure inconnue conserve les affectations et produit un avertissement.

Relancer l'actualisation prépare aussi la réparation des catégories erronées
(`Constituents NVIDIA`, `Average … BROADCOM`, etc.). Les positions rejoignent les
catégories correctes, en réutilisant les catégories existantes lorsqu'elles sont
reconnues. L'aperçu montre explicitement les catégories proposées à la suppression.
Seules les feuilles créées par cette fonction, identifiées par leur identifiant
déterministe, sans objectif ni enfant et devenant entièrement vides sont retirées.
Les catégories personnalisées et celles conservant une position non actualisée
restent présentes. Une annulation ne modifie rien.

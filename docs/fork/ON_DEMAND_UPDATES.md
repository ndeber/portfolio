# Étude : traitements à la demande dans l'application

État au 22 septembre 2026. Cette étude ne déclenche aucune mise à jour d'un
portefeuille et n'ajoute pas encore les commandes décrites ci-dessous.

**Mise en œuvre :** la première commande [Ajuster ELM](ELM_REFRESH.md) est désormais
disponible sur `feature/elm-refresh`, avec le socle
`feature/update-actions`. Depuis la v7, la validation applique ELM au portefeuille
ouvert, à la demande de l’utilisateur ; le parcours en copie décrit plus bas
correspond à la proposition initiale. Les deux autres traitements restent à développer.

## Résultat

Trois commandes indépendantes sont réalisables dans un menu « Outils du portefeuille » :

| Commande proposée | Périmètre |
| --- | --- |
| Actualiser les taxonomies… | Compositions géographiques, sectorielles et transparence Actions ; notation, échéance, région et type d'émetteur Obligations |
| Actualiser ELM… | Allocation monétaire/obligations/actions publiée par ELM et, sur sélection explicite, mise à jour de la taxonomie Pilotage |
| Actualiser les obligations individuelles… | Historique des cours des obligations configurées, coupons courus et indexation nécessaires à la convention de valorisation |

Il faut distinguer la mise à jour des compositions externes du recalcul des valeurs
des taxonomies : Portfolio Performance recalcule déjà les valeurs depuis les positions
et les cours. Le besoin est ici d'actualiser les affectations détaillées des fonds.

## Ce que fait le script actuel

L'étude porte sur le moteur Ruby `update_elm_xml.rb` (4 011 lignes), le pont
`run_update.rb` et le convertisseur Java du module `portfolio-update`.
Les chemins locaux et les règles propres au portefeuille restent hors de ce dépôt.

Le moteur n'expose qu'une exécution complète source/destination. Son point d'entrée
enchaîne ELM et Vanguard LifeStrategy, l'historique LifeStrategy, la préparation de
titres obligataires, leurs cours, Pilotage et les taxonomies détaillées. Il écrit une
copie et un cache externe. Trois boutons lançant ce point d'entrée n'auraient donc
pas trois périmètres indépendants.

### Taxonomies

Points d'entrée existants : `update_equity_regions`, `update_equity_sectors`,
`update_equity_transparency`, `update_bond_taxonomies`, `update_direct_bond_taxonomies`.
Les données proviennent d'un mélange de pages de fournisseurs, compositions diffusées
par Boursorama, documents MSCI, données Vanguard et profils de classification statiques.
Tous les profils ne sont donc pas des données de marché fraîchement téléchargées.

Le cache des taxonomies détaillées a une durée de sept jours et une signature du
périmètre. Le point d'entrée neutralise actuellement le contrôle du contenu des
affectations existantes (`*_content_matches_cache = true`). Une commande manuelle
doit proposer « actualiser maintenant », même si le cache est récent, et tenir compte
des modifications de classement faites dans l'application.

### ELM

`fetch_elm_allocation` lit le bloc `alloc-json` de la page officielle : les champs
`target` sont regroupés en trois classes, avec contrôle du total et arrondi en points
de base. Ce sont les allocations cibles publiées par ELM, pas une reconstruction
indépendante de sa stratégie ni une lecture du portefeuille réel du fonds.

`add_pilotage_taxonomy` reconstruit ensuite Pilotage en isolant ELM dans un moteur
dynamique et en l'excluant du moteur statique. Cette fonction contient des objectifs
fixes propres au portefeuille et régénère les identifiants. Dans l'application,
identifier le titre et les taxonomies par UUID, conserver les identifiants existants
et rendre le profil de Pilotage configurable. Cela évite d'invalider les widgets
qui mémorisent une taxonomie et une catégorie. Actualiser ELM ne doit pas imposer
la présence de Vanguard ni modifier ses cours.

### Obligations individuelles

`update_direct_bond_prices` traite sept ISIN configurés : trois obligations allemandes
et quatre françaises indexées. Les commentaires parlent encore de six ; la configuration
en contient sept. Le moteur n'est pas encore un fournisseur universel pour tout ISIN.

Il utilise les historiques Bundesbank pour les obligations allemandes et les cours
Borsa Italiana avec les coefficients AFT pour les OAT indexées. La convention actuelle
est un cours « dirty » : coupon couru inclus et, pour les obligations indexées,
coefficient d'indexation appliqué. Le prix clean, la date, la source et les composants
du calcul sont conservés dans les attributs et les notes du titre.

Le calcul inclut un calendrier TARGET et un décalage de règlement. Il faut porter et
tester ces conventions comme règles du script, sans supposer qu'elles conviennent à
toutes les obligations. La mise à jour actuelle reconstruit l'historique complet,
modifie le dernier cours et sélectionne le fournisseur manuel. Une intégration native
doit distinguer la migration initiale de convention et les actualisations suivantes,
pour ne pas mélanger cours clean/dirty ni supprimer silencieusement un historique.

## Pourquoi éviter un simple appel au script

- Le convertisseur du module utilise les bibliothèques de l'application officielle.
  Essai effectué sur `PE-Demo.portfolio` : arrêt avec `UnsupportedOperationException:
  UNRECOGNIZED`. Le fichier source n'a pas été modifié. Le fork doit assurer sa propre
  lecture/écriture, notamment pour `CAPITAL_CALL` et `DISTRIBUTION`.
- Les noms de titres et de catégories, certains profils et les chemins locaux sont
  fixés dans le script. Les rendre configurables et conserver les UUID du modèle Java.
- Le moteur transforme directement des fragments XML ; la conversion n'est pas un
  bon moyen de modifier un portefeuille déjà ouvert avec des changements non enregistrés.
- Dépendances externes actuelles : Ruby, Python/pypdf pour certaines factsheets,
  LibreOffice pour un fichier XLS AFT, outils ZIP et application officielle. Une version
  autonome doit remplacer ou embarquer explicitement ces dépendances.

## Architecture recommandée

Porter les traitements en Java dans des services isolés du modèle d'interface.
Séparer récupération/parsing des sources, calculs purs et application des changements.
Le script reste une référence pour les tests de parité sur des données figées.

Réutiliser les points d'intégration déjà présents dans le fork :

- handlers et contexte du portefeuille : `UpdateQuotesHandler`, `MenuHelper` ;
- exécution en arrière-plan et annulation : `AbstractClientJob` ;
- modification des affectations : `Classification.Assignment` ;
- cours : `QuoteFeed`, `QuoteFeedData`, `Security.addPrice` et politiques de mise à jour
  `MERGE`/`REPLACE_IF_SOURCE_CHANGED`, sous réserve d'une migration explicitement prévisualisée ;
- enregistrement avec les types PE : `ClientFactory` du fork.

Gson/Jsoup et les modules PDFBox sont déjà disponibles. La lecture XLS/XLSX nécessite
encore un choix de bibliothèque ou de format source, avec vérification du packaging.

Parcours proposé : choisir la fonction et son périmètre → récupérer les données en
arrière-plan → afficher les différences, dates et sources → créer une copie actualisée
et l'ouvrir. Le fichier original reste intact, comme dans le module actuel. La copie
doit partir de l'état courant en mémoire et être relue avant ouverture. Si l'utilisateur
modifie le portefeuille pendant la récupération, recalculer ou invalider l'aperçu.

Chaque commande doit signaler les titres non pris en charge, les données anciennes
et les échecs de source, sans transformer une erreur en allocation ou cours nul.
Aucun cours ou catégorie sans lien avec la commande choisie ne doit être modifié.

## Découpage et ordre de réalisation proposés

1. Un socle commun `feature/update-actions` : menu, progression, aperçu et copie.
2. `feature/elm-refresh` : premier traitement, périmètre le plus limité ; allocation
   publiée d'abord, mise à jour de Pilotage séparément sélectionnable.
3. `feature/bond-quotes` : fournisseurs et conventions de cours ; d'abord les titres
   déjà configurés, puis extension à d'autres obligations si demandée.
4. `feature/taxonomy-refresh` : familles Actions et Obligations sélectionnables,
   puis élargissement des profils et sources.

Les branches du socle et ELM ont été créées lors de la mise en œuvre v6. Les deux
autres noms restent une proposition.
Le socle commun est une dépendance explicite des branches fonctionnelles. L'identité
visuelle et les opérations PE restent séparées.

Tests nécessaires : sommes et arrondis des affectations, UUID conservés, widgets
toujours liés, idempotence, choix forcé malgré cache récent, conventions de coupons
et d'indexation aux dates limites, migration clean/dirty, données partielles,
annulation, modification concurrente et relecture d'un fichier contenant les opérations PE.

Références publiques consultées : [ELM](https://www.elmfunds.com/elm-market-navigator-etf),
[Bundesbank](https://www.bundesbank.de/en/service/federal-securities/prices-and-yields),
[coefficients OAT€i AFT](https://www.aft.gouv.fr/fr/oateuroi-principaux-chiffres).
La disponibilité générale de ces pages a été vérifiée ; tous les téléchargements et
parseurs du moteur n'ont pas été exécutés ni revalidés en ligne pendant cette étude.

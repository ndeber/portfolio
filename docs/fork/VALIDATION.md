# Validation de la séparation — 22 septembre 2026

Base amont : `e7fead8`. Les vérifications ci-dessous ont été réalisées sur macOS Apple Silicon avec JDK 21 et Maven/Tycho.

| Configuration | Vérification | Résultat |
| --- | --- | --- |
| Logo seul (`feature/vivid-branding`) | Compilation propre de tous les modules, sans opérations PE ni nouveaux widgets | Réussie |
| Opérations seules (`feature/pe-operations`) | Compilation propre + `PrivateEquityValuationTest` | 13 tests réussis |
| Widgets seuls (`feature/allocation-widgets`) | Compilation propre + `AllocationGoalWidgetTest` | 4 tests réussis |
| Fabrication seule (`fork/platform`) | Compilation propre + assemblage du produit Mac, sans fonctionnalités optionnelles | Réussie |
| Version combinée (`fork/integration`) | Compilation propre, 17 tests dédiés et assemblage du produit Mac | Réussis |

Lors de cette séparation initiale, les quatre branches d'ajouts ne modifiaient aucun fichier en commun par rapport à `upstream-baseline`. Les 39 fichiers d'implémentation et de tests PE/widgets étaient identiques à ceux du prototype validé (`a438b6f`) : cette réorganisation n'avait pas modifié les calculs.

Le logo a été inspecté après rendu. Le générateur ne change que les sept valeurs de couleur du SVG amont et conserve les tracés. Les variantes standard et Retina et l'icône macOS ont été générées avec succès. La sélection de l'identité passe par le manifeste de fabrication ; le logo officiel reste disponible.

Cette passe ne constitue pas une nouvelle validation interactive complète de tous les écrans de l'application. Les contrôles financiers élargis de la livraison précédente sont conservés ; une future mise à jour du code amont nécessitera une nouvelle régression.

## Correctifs après essai utilisateur — livraison v3

Deux parcours manquaient dans la validation initiale : la sélection d'un compte
contenant des flux PE et la connexion au fournisseur de cours dans le programme livré.

- `feature/pe-operations` : le calcul du solde de `AccountListView` utilise maintenant
  le caractère débit/crédit du type d'opération. Les 13 tests PE et 3 nouveaux tests
  du calcul utilisé par cet écran passent sur cette branche seule. Les nouveaux tests
  couvrent les 15 types, le tri chronologique, la modification, la suppression et
  les comptes vides/non sélectionnés. Le scénario de démonstration donne bien
  15 000 → 13 000 → 13 500 EUR après dépôt, appel et distribution.
- `fork/platform` : préparation de la configuration publique OAuth depuis le
  programme officiel installé, contrôlée par l'empreinte exigée par le code amont.
  Six tests de fabrication passent. Le contrôle refuse également le véritable
  ancien produit sans configuration avant de créer une livraison. Une compilation
  propre de cette branche seule embarque ensuite une configuration valide.
- `fork/integration` : compilation propre et fabrication réussies, 20 tests Java
  dédiés réussis (13 PE, 3 compte, 4 widgets).
- Connexion : appel réel à `OAuthClient.signIn` avec les bibliothèques du programme
  reconstruit ; démarrage du serveur de rappel local et génération de la requête
  PKCE réussis. Le fournisseur accepte cette requête et renvoie sa page `/sign-in`
  avec un statut HTTP 200. Aucun identifiant utilisateur n'a été saisi ni session
  copiée. La connexion au compte personnel et le téléchargement de ses cours restent
  à essayer par l'utilisateur ; ce contrôle ne les remplace pas.

Les correctifs sont conservés sur leurs branches respectives et fusionnés dans
l'intégration. La configuration importée reste ignorée par Git, conformément au
projet amont. Consulter [BUILD_MAC.md](BUILD_MAC.md) avant une nouvelle fabrication.

## Repère visuel permanent — livraison v4

La branche `feature/vivid-branding` ajoute un bandeau d'identité dans l'accueil et
au-dessus du portefeuille. Compilation propre de la branche seule réussie, puis
compilation de l'intégration et 20 tests dédiés réussis. Les calculs financiers et
les règles du script externe ne sont pas modifiés.

Contrôle interactif sur une copie de l'application avec espace de travail isolé :
accueil, ouverture de `PE-Demo.portfolio`, état des actifs, passage du thème clair
au thème sombre. Le bandeau est visible dans les deux thèmes et le portefeuille
affiche toujours 100 parts, un fonds de 11 500 EUR et 13 500 EUR de liquidités.
Contraste calculé du texte du bandeau : 7,09:1 en clair et 9,20:1 en sombre.
La copie de contrôle a été fermée après vérification.

L'étude des commandes à la demande est dans [ON_DEMAND_UPDATES.md](ON_DEMAND_UPDATES.md).
Ces commandes ne sont pas encore implémentées. Le script et les portefeuilles de
l'utilisateur n'ont pas été exécutés ou modifiés dans le cadre de cette étude.

## En-tête compact et écran de démarrage — livraison v5

Le bandeau v4 est supprimé. Le titre natif affiche « Portfolio Performance PE · Fork »
et les onglets d'accueil et de portefeuille portent le petit logo Vivid PE.
Le splash optionnel utilise un BMP RGB 24 bits généré depuis le SVG du fork.

- Compilation indépendante de `feature/vivid-branding` : succès.
- Compilation du produit intégré : succès ; 20 tests PE, soldes de compte et widgets réussis.
- Six tests de validation du packaging OAuth : succès.
- Contrôle visuel sur macOS, accueil puis copie de PE-Demo : titre et logos visibles,
  aucun bandeau, tableau de portefeuille chargé normalement.
- Le résolveur du lanceur Equinox livré a retrouvé et décodé le nouveau splash dans
  une copie déplacée de l'application. Le BMP et son aperçu ont été vérifiés ;
  l'affichage transitoire du splash n'a pas été capturé à l'écran.
- Signature locale de l'application vérifiée pendant le packaging.

Aucun portefeuille utilisateur ni espace de travail habituel n'a servi aux essais.

## Ajuster ELM — livraison v6

- 34 tests ciblés réussis : 13 pour lecture ELM, ajustement et copies ; un test
  croisé ELM/PE ; 20 régressions PE, soldes de compte et widgets.
- Copies XML, ZIP, `.portfolio` binaire et `.portfolio` chiffré : écriture et
  relecture, refus d'écrasement. Le test croisé conserve appels de fonds,
  distributions, valorisation PE et identifiants du tableau de bord après ELM.
- Source publique réellement appelée depuis les classes Java du produit le
  22 septembre 2026 : allocation du 18 septembre, 28,83 % monétaire, 12,35 %
  obligations et 58,82 % actions, cohérente avec la fixture de régression.
- Fragment de menu chargé par EMF et handler Java résolu depuis les bibliothèques
  effectivement livrées. Compilation du produit, packaging et signature locale
  réussis ; six tests de contrôle de configuration OAuth réussis.
- L'ouverture de la copie vérifiée ne lance pas les mises à jour automatiques de
  cours, dividendes ou plans d'investissement. L'éditeur source est conservé.
- **Limite :** l'essai interactif des nouvelles fenêtres n'a pas pu être effectué,
  le Mac étant verrouillé. Ce contrôle reste à faire avec `ELM-Demo.xml`.

Aucun portefeuille utilisateur n'a été lu ou modifié pour ces essais. L'exemple
ELM fourni est synthétique et conserve les opérations de la démonstration PE.

## Ajustement ELM dans le portefeuille ouvert — livraison v7

À la demande de l'utilisateur, le bouton final applique maintenant les changements
au portefeuille courant. Il ne crée plus de fichier ni d'onglet. L'enregistrement
reste celui de l'éditeur : manuel ou automatique selon les préférences existantes.

- Compilation indépendante de la branche ELM : succès, huit tests d'ajustement réussis.
- Produit intégré : compilation réussie, 13 tests ciblés réussis (ajustement,
  compatibilité PE et widgets).
- Nouveaux contrôles : aperçu préparé sur un instantané puis appliqué au même
  modèle courant, objets et modifications non enregistrées conservés, notification
  des taxonomies après toutes les modifications puis recalcul des vues ; aucun
  changement ni notification quand l'aperçu n'est plus valable.
- Les vues de taxonomie ouvertes reconstruisent leurs nœuds d'affectation lors de
  cette notification pour ne pas garder les anciennes catégories en cache.
- Essai interactif non renouvelé : le Mac est toujours verrouillé. Le parcours v6
  avait été confirmé fonctionnel par l'utilisateur ; la validation v7 ci-dessus
  est automatisée.

## Obligations à la demande — livraison v8 (24 septembre 2026)

- Branche obligations indépendante : 17 tests ciblés réussis, dont lecture XLS
  binaire dans le runtime OSGi (dépendances incluses).
- Reconstruction propre du produit intégré : 52 tests réussis, aucun échec.
  La régression croisée vérifie les cours et attributs dans les formats XML et
  `.portfolio`, avec conservation de Pilotage, des widgets et des opérations PE.
- Contrôle en ligne des sept ISIN réussi le 24 septembre : historiques allemands
  jusqu'au 24 septembre et français jusqu'au 23 septembre. Ce sont les dates
  publiées, pas une garantie de disponibilité future des serveurs.
- Historique obtenu : DE000BU2Z072 (55 points), DE000BU27014 (274), DE0001102622
  (1002), FR0013327491 (1712), FR0000188799 (1900), FR0013410552 (1769),
  FR0000186413 (1898).
- Deux variantes CSV Bundesbank, sélection de la publication AFT récente malgré
  les anciens liens masqués et lecture XLS/XLSX selon la signature du fichier.
- Packaging Mac Apple Silicon avec JDK embarqué et signature locale vérifiée.
  Lancement d'une copie isolée du produit, ouverture du fichier de démonstration,
  présence des commandes ELM et obligations dans un seul menu, sélection des sept
  obligations vérifiés dans l'interface. L'aperçu a affiché les sept historiques
  réels avec les mêmes dates et nombres de points que le contrôle en ligne.
- Limite du contrôle interactif : après le clic de validation, l'outil de contrôle
  macOS a renvoyé des délais dépassés, y compris après reconnexion. L'enregistrement
  final du fichier de démonstration n'a donc pas été confirmé dans l'interface.
  Le journal ne contient pas d'exception et la pile Java montre la boucle
  événementielle au repos. L'application au modèle et la persistance restent
  couvertes par les tests automatisés ci-dessus.

Pilotage Global et l'option Pilotage d'ELM sont conservées. Aucun titre ni aucune
taxonomie du portefeuille personnel n'a été modifié par les tests de la v8.

## Flux PE en devises — livraison v9 (24 septembre 2026)

Une copie migrée a révélé une `MonetaryException` dans le détail FIFO des
plus-values d'un fonds USD présenté en EUR. `TrailRecord.fraction` renvoie la
trace originale lorsqu'elle porte sur un lot entier ; le montant EUR passé en
argument ne convertissait donc pas la trace USD. Le correctif convertit d'abord
la trace, en devise du rapport et en devise du titre, puis répartit le flux.

Quatre tests synthétiques couvrent appels et distributions, compte USD ou EUR
avec montant USD enregistré, un ou plusieurs lots, rapports EUR et USD, FIFO
et coût moyen, avec et sans frais. Avant correction : deux erreurs reproduites
sur les lots uniques. Après correction : les quatre cas et les 13 tests PE
existants passent. Aucune donnée personnelle n'est incluse dans ces tests.

Reconstruction intégrée propre : 56 tests ciblés réussis, aucun échec. Sur une
copie locale du portefeuille ayant déclenché l'erreur, les accesseurs des
rapports de performance 2021–2026 (dont les plus-values FIFO) ne lèvent plus
l'erreur monétaire après correction. Ce contrôle utilise un taux fixe pour
isoler la cohérence des devises ; il ne valide pas les taux de marché. Un
avertissement de performance sans titres, déjà présent sur le portefeuille
avant migration, reste indépendant de ce correctif.

## Blocage PDF des taxonomies Actions — livraison v13 (24 septembre 2026)

Le relevé des threads de la v12 bloquée sur Amundi PEA Emerging montre une attente
indéfinie dans l'initialisation du fournisseur Log4j, appelée par PDFBox après le
téléchargement MSCI. Le test précédent hors OSGi ne reproduisait pas cette attente.
Le pont officiel Log4j vers SLF4J est désormais inclus et démarré au niveau 3,
avant les consommateurs au niveau 4, dans les deux produits.

Contrôles effectués :

- Construction intégrée réussie ; 17 tests Actions réussis, dont 4 tests des délais
  et de l'annulation (y compris un lecteur ignorant les interruptions).
- Contrôle dans le runtime OSGi assemblé : fournisseur actif dès le démarrage,
  lecture PDF réussie. Même contrôle réussi dans la livraison Mac.
- Récupération réelle dans ce runtime des trois compositions Amundi PEA Emerging :
  régions, secteurs et transparence réussies, dix lignes par composition.

Le contrôle reproductible est `private-equity-product/check-packaged-pdf.py`.
Ces tests n'ouvrent ni ne modifient le portefeuille personnel. Ils ne constituent
pas une vérification visuelle de l'ensemble du parcours d'interface.

## Noms des constituants MSCI — livraison v14 (24 septembre 2026)

PDFBox restitue certaines lignes en réunissant les statistiques d'indice situées
à gauche et les constituants à droite. Le parseur v13 capturait les statistiques
dans le nom, malgré des poids corrects. Les libellés de statistiques connus sont
maintenant retirés ; noms ambigus, doublons, tableau incomplet ou total incohérent
font échouer cette composition sans remplacer ses affectations.

Les deux extraits reproduisant les colonnes mélangées sont conservés comme
fixtures. Les tests comparent les vingt noms et les vingt poids attendus. Les
réparations sont présentées dans l'aperçu et limitées aux feuilles générées par
notre fonction, sans objectif ni enfant, entièrement vidées par la réaffectation.
Les tests couvrent notamment catégories existantes, personnalisation, source en
échec, autre position, aperçu périmé, idempotence et relecture du modèle.

Validation : 21 tests ciblés réussis. La construction complète avec ce filtre de
tests rencontre ensuite « No tests found » dans le module UI, puisque les tests
sélectionnés appartiennent au cœur ; l'assemblage séparé sans relancer les tests
réussit. Le contrôle du produit OSGi avec `--live-msci` récupère les six compositions
Amundi/WPEA et affiche les vingt noms nettoyés avec leurs poids. Aucune écriture
n'est effectuée dans le portefeuille personnel.

## Tri des taxonomies Actions — livraison v15 (24 septembre 2026)

Après application de l'aperçu, catégories et sous-catégories des trois taxonomies
Actions sont classées par valeur actuelle décroissante, avec Other / Others /
Autre / Autres en dernier. Les valeurs sont celles du portefeuille complet en
devise de base, pondérées par les affectations et additionnées récursivement comme
dans la vue des taxonomies. Le tri reste disponible lorsque les compositions sont
déjà à jour. Les identifiants, objectifs, couleurs et poids sont conservés.

23 tests ciblés réussis, dont deux tests de tri couvrant les trois taxonomies,
les sous-catégories, les affectations partielles, les ex æquo, les valeurs nulles
et négatives, la persistance après relecture, et la conservation de Pilotage Global.

## Répartitions Obligations — livraison v16 (24 septembre 2026)

Branche indépendante `feature/bond-taxonomy-refresh` : commande/menu et bouton dans
les quatre vues Obligations, préparation en arrière-plan, aperçu, application au
modèle ouvert et tri récursif. Les cotations et les autres taxonomies sont préservées.

35 tests ciblés passent, dont 12 nouveaux cas obligataires : limites calendaires,
profils fixes, sommes et arrondis, reliquats, pagination complète/incomplète/bloquée,
dates des tables, distinction BBB / inférieur à BBB, catégories absentes ou ambiguës,
aperçu périmé, identité des objets, objectifs, autres positions, périmètre, tri,
idempotence et relecture. Les tests Actions existants restent valides.

Essais des sources publiques pour VAGF et les neuf fonds du script : aucune erreur.
Vanguard fournit 13 660 lignes pour les échéances. Contrôle dans le runtime OSGi
assemblé, sans portefeuille personnel : douze résultats VAGF/M&G/Bund validés à
100 %, et chargement du handler confirmé. Construction intégrée réussie.
Ce contrôle ne remplace pas un test visuel complet du parcours de l'interface.

## Engagements PE — livraison v17 (25 septembre 2026)

Saisie des engagements totaux et des échéances en EUR, calcul du réalisé et du
restant, colonnes non modifiables pour les valeurs dérivées, deux widgets de
sommes et de couverture par la catégorie Réserves appels de fonds. Données
incomplètes et écarts d'échéancier sont visibles. Années fixes, sans décalage
implicite au changement d'année. Aucun changement des opérations ou cotations.

Validation : 8 tests métier, 3 tests de widgets/colonnes et 4 tests de non-régression
allocation/objectifs réussis ; assemblage Mac réussi. Scénarios synthétiques,
sans lecture ni écriture du portefeuille personnel. Ce contrôle ne constitue
pas une vérification visuelle complète de toutes les interactions de saisie.

Contrôle supplémentaire de la v17 dans le produit assemblé : calcul synthétique
réalisé/restant réussi, chargement du handler et des widgets et présence du
fragment de menu confirmés (`check-packaged-pdf.py --commitments`), hors réseau.

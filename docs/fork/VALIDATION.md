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

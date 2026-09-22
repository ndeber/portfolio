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

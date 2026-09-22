# Validation de la séparation — 22 septembre 2026

Base amont : `e7fead8`. Les vérifications ci-dessous ont été réalisées sur macOS Apple Silicon avec JDK 21 et Maven/Tycho.

| Configuration | Vérification | Résultat |
| --- | --- | --- |
| Logo seul (`feature/vivid-branding`) | Compilation propre de tous les modules, sans opérations PE ni nouveaux widgets | Réussie |
| Opérations seules (`feature/pe-operations`) | Compilation propre + `PrivateEquityValuationTest` | 13 tests réussis |
| Widgets seuls (`feature/allocation-widgets`) | Compilation propre + `AllocationGoalWidgetTest` | 4 tests réussis |
| Fabrication seule (`fork/platform`) | Compilation propre + assemblage du produit Mac, sans fonctionnalités optionnelles | Réussie |
| Version combinée (`fork/integration`) | Compilation propre, 17 tests dédiés et assemblage du produit Mac | Réussis |

Les quatre branches d'ajouts ne modifient aucun fichier en commun par rapport à `upstream-baseline`. Les 39 fichiers d'implémentation et de tests PE/widgets sont identiques à ceux du prototype validé (`a438b6f`) : cette réorganisation n'a pas modifié les calculs.

Le logo a été inspecté après rendu. Le générateur ne change que les sept valeurs de couleur du SVG amont et conserve les tracés. Les variantes standard et Retina et l'icône macOS ont été générées avec succès. La sélection de l'identité passe par le manifeste de fabrication ; le logo officiel reste disponible.

Cette passe ne constitue pas une nouvelle validation interactive complète de tous les écrans de l'application. Les contrôles financiers élargis de la livraison précédente sont conservés ; une future mise à jour du code amont nécessitera une nouvelle régression.

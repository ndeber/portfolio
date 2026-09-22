# Identité visuelle Vivid PE

Cette extension conserve les tracés, les contours blancs et l'ombre du logo amont. Elle remplace uniquement sa palette par du violet, du rose vif et du cyan. Le SVG amont reste intact.

Le titre de fenêtre « Portfolio Performance PE · Fork » et le petit logo dans
les onglets d'accueil et de portefeuille identifient le fork sans ajouter de
bandeau. L'écran de démarrage reprend le même logo. Ces changements sont
optionnels ; le packaging sélectionne le splash et active les repères de fenêtre.

- `logo.svg` : variante vectorielle, dérivée de `portfolio-product/icons/logo.svg`.
- `generate.py` et `RenderLogo.java` : génération des PNG standard/Retina et de l'icône macOS.
- `splash.svg`, `splash.bmp` et `splash-preview.png` : composition vectorielle et écran de démarrage 24 bits.
- `manifest.json` : identité choisie au moment de fabriquer la livraison Mac.
- `ui/branding/ForkBranding.java` : sélection des logos dans l'accueil, À propos et les fenêtres, activée par `-Dportfolio.vividBranding=true`.

Les actifs sont déjà générés et versionnés : aucun outil graphique supplémentaire n'est nécessaire pour compiler. Pour les régénérer sur Mac, utiliser JDK 21 et le JAR JSVG 2.1.0 téléchargé par Maven :

```sh
python3 fork-branding/generate.py --jsvg /chemin/vers/jsvg-2.1.0.jar
```

Avec la branche de fabrication `fork/platform`, sélectionner cette identité par `--branding fork-branding` dans `package-macos.py`. Sans cette option, la palette amont reste utilisée. Cette branche ne contient aucune opération PE ni aucun widget.

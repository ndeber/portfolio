# Identité visuelle Vivid PE

Cette extension conserve les tracés, les contours blancs et l'ombre du logo amont. Elle remplace uniquement sa palette par du violet, du rose vif et du cyan. Le SVG amont reste intact.

Un bandeau permanent « Portfolio Performance PE · Fork » accompagne l'accueil et
chaque portefeuille ouvert. Il reste au-dessus du contenu lorsque la navigation
latérale est masquée. Son fond lavande clair ou violet grisé suit le thème clair/sombre,
sans recolorer les tableaux ni les indicateurs de performance. Il utilise la même
option d'activation que le logo ; sans cette option, aucun bandeau n'est créé.

- `logo.svg` : variante vectorielle, dérivée de `portfolio-product/icons/logo.svg`.
- `generate.py` et `RenderLogo.java` : génération des PNG standard/Retina et de l'icône macOS.
- `manifest.json` : identité choisie au moment de fabriquer la livraison Mac.
- `ui/branding/ForkBranding.java` : sélection des logos dans l'accueil, À propos et les fenêtres, activée par `-Dportfolio.vividBranding=true`.

Les actifs sont déjà générés et versionnés : aucun outil graphique supplémentaire n'est nécessaire pour compiler. Pour les régénérer sur Mac, utiliser JDK 21 et le JAR JSVG 2.1.0 téléchargé par Maven :

```sh
python3 fork-branding/generate.py --jsvg /chemin/vers/jsvg-2.1.0.jar
```

Avec la branche de fabrication `fork/platform`, sélectionner cette identité par `--branding fork-branding` dans `package-macos.py`. Sans cette option, la palette amont reste utilisée. Cette branche ne contient aucune opération PE ni aucun widget.

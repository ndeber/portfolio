ELM Market Navigator ETF est désormais inclus systématiquement dans « Actualiser les taxonomies Obligations… ». Le calcul lit ses positions réelles, puis les compositions de SCHP, BND et AGG pour qualifier notation, échéance, région/pays et type d’émetteur. Les actions, VBIL, fonds monétaires et liquidités internes restent exclus : seule la poche obligataire d’ELM est affectée, sans remonter les poids à 100 %.

Sur les positions publiées du 16 octobre 2026, les quatre taxonomies totalisent chacune 14,22 % du titre ELM. « U.S. Government » et les TIPS utilisent la note souveraine américaine Aa1, regroupée dans la catégorie AA. Les dates de livraison TBA restent à échéance non déterminée.

Validation : 225 tests sans échec, six tests de packaging, contrôle des quatre sources dans l’application assemblée, sauvegarde/réouverture XML, ZIP, binaire et chiffrée, vérification du runtime et de la signature macOS. Les preuves de reconstruction depuis GitHub sont jointes séparément.

Limites visibles dans l’aperçu : la copie officielle SCHP du 15 octobre 2026 est utilisée si son site refuse la lecture automatique ; ce recours expire après 45 jours. Le profil souverain US est daté et n’est pas relu automatiquement. Un sous-jacent inconnu, une source périmée ou une catégorie absente conserve les affectations concernées.

Les sources et le manifeste complet sont dans ce tag. L’environnement Java 21/Maven et son cache de dépendances proviennent de la release fork-2026-10-15 et sont vérifiés par SHA-256. Aucun outil ni portefeuille privé n’est nécessaire.

Reconstruction sur Mac Apple Silicon :

```
python3 tools/fork/rebuild-macos.py \
  --release-file tools/fork/build-elm-bonds-2026-10-16.json \
  --output ../PortfolioPerformance-ELM
```

Étude détaillée et méthode : docs/fork/ELM_BOND_TAXONOMIES.md.
Environnement de compilation : https://github.com/ndeber/portfolio/releases/tag/fork-2026-10-15

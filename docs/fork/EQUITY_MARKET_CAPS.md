# Capitalisations dans les taxonomies Actions

Le bouton d’actualisation des taxonomies Actions conserve ses trois traitements
Régions, Secteurs et Transparence et ajoute un aperçu de capitalisation dans
**Pilotage Global** et **Objectifs et Classes d'actifs**.

Dans chaque branche Actions possédant Large Caps et Small Caps :

- **Large Caps** regroupe les tailles Vanguard Large et Medium/Large ;
- **Mid Cap** reprend Medium ;
- **Small Caps** regroupe Medium/Small et Small.

La catégorie Mid Cap est créée si nécessaire avec une cible initiale de 0 % à
ajuster manuellement. Les objectifs existants restent inchangés. Seul le total
déjà affecté aux capitalisations est redistribué : Infrastructure, les autres
branches et les autres titres sont préservés. Le reliquat de source reste affecté
directement à Actions, sans être assimilé à une taille connue.

Les données viennent du service public GraphQL du site Vanguard, rubrique
Market Capitalization, codes FRCCAPTLTP261/263/262/264/265, uniquement le fonds
(et jamais son indice de référence). Sont pris en charge les ETF All-World,
North America, Developed Europe, Developed Asia Pacific ex Japan, Japan,
Emerging Markets, Developed World et le Global Small-Cap Index Fund présents
par leurs ISIN dans EquityCapSources. Une source manquante, invalide ou vieille
de plus de 90 jours laisse les affectations du titre inchangées et apparaît
explicitement dans l’aperçu. Aucun poids n’est déduit d’une simple étiquette de
style ou des dix principales participations.

Pour le [FTSE All-World](https://www.vanguard.co.uk/professional/product/etf/equity/9679/ftse-all-world-ucits-etf-usd-accumulating),
la publication du 31 août 2026 donne après arrondi PP : 78,98 % Large Caps,
14,30 % Mid Cap, 6,65 % Small Caps, et 0,07 % non ventilé. Une couverture inférieure
à 99 % pour ce fonds est rejetée. L’arrondi conserve exactement l’exposition
initiale dans chaque branche. Les fonds non pris en charge conservent leur
répartition ; ils ne sont pas automatiquement classés par leur nom.

Les téléchargements restent annulables, limités à 45 secondes par fonds.
L’application ne modifie le portefeuille ouvert qu’après validation de l’aperçu,
et refuse un aperçu devenu obsolète. Aucune écriture directe dans le fichier
Xapa Ventures n’est nécessaire.

Validation du 29 septembre 2026 : 29 tests equity réussis, compilation du produit
Mac réussie, aperçu SWT et application idempotente sur les deux taxonomies
vérifiés sur des données fictives avec la source All-World en direct. Les sept
ETF Vanguard renvoient les cinq tailles attendues. Global Small-Cap renvoie
actuellement une valeur Large absente : son ancienne répartition est conservée,
une absence n’étant pas interprétée comme zéro.

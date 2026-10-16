# Actualiser les taxonomies Obligations

Branche `feature/bond-taxonomy-refresh`, fondée sur `feature/taxonomy-refresh`.
Le calcul obligataire reste isolé dans `updates/bondallocation` ; le téléchargement
pays, l'annulation et le tri réutilisent les services de la fonctionnalité Actions.

## Utilisation

Dans une des vues Obligations, **Actualiser les 4 taxonomies Obligations…** prépare
Notation, Échéance, Région/Pays et Type d'émetteur ensemble. La même commande figure
dans **Outils du portefeuille → Actualiser les taxonomies Obligations…**.
Les variantes sans accents des noms sont reconnues.

L'aperçu montre les affectations avant/après et les sources, dates, profils fixes
et erreurs. Appliquer actualise le portefeuille ouvert, puis trie catégories et
sous-catégories par montant décroissant, avec Other / Others / Autres en dernier,
y compris les catégories régionales « Europe - Autres ». Enregistrer avec ⌘S.
Annuler ne change rien. Même sans changement de composition, le tri peut être appliqué.

Les quatre taxonomies et leurs catégories doivent déjà exister. Les catégories,
UUID, objectifs et couleurs sont conservés. Aucune suppression ni reconstruction
d'arbre n'est faite. Une catégorie absente ou ambiguë conserve toute la répartition
concernée. Les autres taxonomies, les opérations et les cotations ne sont pas modifiées.
Cette commande est indépendante de celle de mise à jour des cours obligataires.

## Périmètre et sources

### Extension ELM du 16 octobre 2026

ELM Market Navigator ETF est désormais inclus systématiquement s'il existe et
n'est pas retiré. Seule sa poche obligataire réelle est affectée, après lecture
des fonds SCHP, BND et AGG et exclusion du cash interne. Les poids d'ELM ne sont
pas remontés à 100 %. U.S. Government utilise la note souveraine américaine
ramenée à AA, selon la règle demandée. La [description détaillée](ELM_BOND_TAXONOMIES.md)
précise les sources, calculs, limites et le recours Schwab daté à validité limitée.

### Autres titres

- Titres non retirés détenus en quantité positive sous Classes d'actifs / Obligations,
  en tenant compte des transferts et en excluant les opérations futures.
- VAGF, Goldman Sachs Global Credit Portfolio I Acc EUR-Hedged (LU0234688595)
  et les obligations directes configurées sont inclus
  même sans position positive, s'ils existent et ne sont pas retirés. Aucun titre
  n'est créé. Les titres sans profil sont signalés et restent inchangés.
- VAGF (IE00BG47KH54) : tables officielles Vanguard pour pays, notation et émetteurs ;
  détail paginé des lignes pour les échéances. Toutes les pages doivent être présentes.
  L'API des lignes ne donne pas de date de composition : cela est indiqué, sans lui
  attribuer artificiellement la date de consultation. Les tranches sont recalculées
  à la date du jour.
- Fonds du script : pays relus sur les pages de composition Boursorama, avec contrôle
  de l'ISIN. Cela n'utilise ni ne réactive leur ancien fournisseur de cours.
  Les notations, horizons et types d'émetteurs restent les profils fixes du script,
  explicitement signalés comme tels, avec date de publication inconnue. Les fonds
  datés changent de tranche d'échéance au fil du temps.
- Goldman Sachs LU0234688595 : découverte du dernier reporting mensuel officiel
  de la part exacte, à la date de référence. Seuls les reportings mensuels sont
  recherchés pour éviter que les documents de frais masquent le reporting.
  Notations et géographie sont relues dans les colonnes du portefeuille ; les
  colonnes de l'indice ne sont pas utilisées. L'ISIN, la date du PDF et les totaux
  sont contrôlés. Les dérivés sont classés « Non noté » ; la zone euro est regroupée
  sous Europe et les expositions géographiques positives sont normalisées à 100 %,
  en excluant les positions négatives. Ces conventions sont indiquées dans l'aperçu.
  Les échéances et les types d'émetteurs restent inchangés avec un avertissement :
  le reporting ne publie ni tranches d'échéance, ni ventilation complète entre
  entreprises financières/non financières et émetteurs de la dette émergente.
  La duration ne sert jamais à fabriquer une répartition d'échéances.
- Obligations directes : pays, émetteur, échéance et notation issus des métadonnées
  du script. La notation souveraine n'est pas vérifiée en ligne ; elle est affichée
  comme profil fixe, jamais comme notation nouvellement téléchargée.

Les poids totalisent exactement 10 000 points de base après arrondi. Le reliquat
pays est affecté à Autres ; celui des notations à Non noté. Pour les émetteurs et
les échéances Vanguard, les expositions positives sont normalisées à 100 % comme
dans le script ; les couvertures négatives ne deviennent pas des poids négatifs.
Cette convention est visible dans l'aperçu.

Correction d'une ambiguïté du script : une ligne Vanguard « Less than BBB » positive
ne peut pas être assimilée à CCC. Si cette ligne devient positive, la notation est
conservée avec un avertissement, faute de détail BB/B/CCC. Les autres tables restent
actualisables indépendamment.

Chaque lecture est limitée à 45 secondes, sauf la pagination des échéances VAGF
(120 secondes). L'annulation reste réactive. Une panne conserve les affectations
de la combinaison titre/taxonomie concernée. Les sources de plus de 90 jours sont
signalées. Tout changement du portefeuille pendant la préparation invalide l'aperçu.
Le compte rendu est mémorisé dans `fork.bondAllocation.lastUpdate`.

## Version Goldman Sachs du 15 octobre 2026

Le tag `fork-goldman-taxonomies-2026-10-15` contient les sources de cet ajout et un
manifeste complet des entrées de compilation. L'environnement de dépendances est
celui de la release `fork-2026-10-15`, contrôlé par son empreinte, sans nouvel outil
ni dépendance privée. Pour reconstruire depuis les sources de ce tag :

```sh
python3 tools/fork/rebuild-macos.py \
  --release-file tools/fork/build-goldman-2026-10-15.json \
  --output ../PortfolioPerformance-Goldman
```

La sélection de validation comprend les tests de taxonomies obligataires, ceux du
parseur Goldman Sachs et les tests de régression du fork.

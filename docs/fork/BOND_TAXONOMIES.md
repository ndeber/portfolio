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

- Titres non retirés détenus en quantité positive sous Classes d'actifs / Obligations,
  en tenant compte des transferts et en excluant les opérations futures.
- Comme dans le script, VAGF et les sept obligations directes configurées sont inclus
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

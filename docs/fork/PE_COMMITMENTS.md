# Engagements PE et réserves

Branche dédiée `feature/pe-commitments`, fondée sur l'intégration contenant les
opérations PE et le socle des widgets. Le calcul métier est isolé dans
`commitments/Commitments.java`, la saisie dans `ui/commitments`, et les widgets
dans `ui/views/dashboard/CommitmentWidget.java`.

## Saisie

Ouvrir **Outils du portefeuille → Renseigner les engagements PE…**, ou utiliser
**Renseigner les engagements PE…** dans le menu d'un des widgets d'engagements.
Choisir un fonds, saisir son engagement total et ses appels encore prévus en EUR
pour 2026, 2027, 2028 et 2029+. La sélection permet aussi d'ajouter tout autre titre.
Les modifications sont préparées en mémoire ; **Appliquer au portefeuille** les
valide, **Annuler** les abandonne. Enregistrer le portefeuille avec ⌘S.

Le total et les quatre prévisions sont de vrais attributs numériques en EUR,
également accessibles dans les attributs supplémentaires et comme colonnes des
tableaux. Les colonnes **Engagement réalisé** et **Engagement restant** sont
calculées et non modifiables : aucune valeur calculée périmée n'est enregistrée.
Leur définition est conservée avec les autres attributs, mais leur valeur est
fournie à l'affichage par le fork. Une version officielle qui ne connaît pas ce
calcul pourra lire les saisies, mais n'affichera pas ces deux valeurs calculées.
Après création des attributs, rouvrir une vue déjà ouverte si nécessaire, puis
ajouter les colonnes depuis la roue dentée → Attributs.

## Calcul

- Réalisé : premier achat chronologique, hors frais et taxes, plus les appels de
  fonds jusqu'à aujourd'hui. Les historiques des comptes retirés restent inclus.
- Les distributions, ventes, transferts et opérations futures ne réduisent ni
  n'augmentent ce calcul. Plusieurs achats déclenchent un avertissement : seul
  le premier est retenu, conformément à la convention choisie.
- Une entrée de titres sans achat initial est signalée comme historique incomplet.
- Une contre-valeur EUR enregistrée dans l'opération est prioritaire ; à défaut,
  conversion au taux historique du jour de l'opération. Un taux manquant produit
  un avertissement et non une conversion implicite à 1 pour 1.
- Restant = total saisi − réalisé. Un dépassement n'est pas masqué par un plancher
  à zéro : il est signalé et désactive l'indicateur de couverture globale.
- Non ventilé = restant − somme des quatre prévisions. Les écarts sont signalés,
  sans empêcher une saisie progressive. Mettre l'échéancier à jour après un appel :
  l'application ne peut pas deviner à quelle prévision l'imputer.

Les années sont fixes : 2026 ne devient pas automatiquement 2027, et 2029+ ne peut
pas être réparti arbitrairement au passage de l'année. Les prévisions d'années
écoulées sont signalées comme à replanifier.

## Dashboard

Ajouter dans la rubrique patrimoine :

- **Engagements PE : détail par fonds** : total, réalisé, restant, quatre échéances,
  montant non ventilé, avertissements et ligne de sommes.
- **Engagements PE : réserves et échéancier** : sommes, réserves actuelles,
  surplus/manque de financement, couverture du restant et réserves après les
  appels cumulés de chaque horizon. Aucun rendement ou revenu futur n'est supposé.

Le périmètre détecte les catégories Private Equity / Venture Capital (ainsi que
Capital-investissement / Capital-risque), les titres ayant des appels de fonds,
et tout titre ayant un engagement saisi. Chaque titre compte une fois, même s'il
figure dans plusieurs taxonomies. Un titre retiré reste suivi s'il est configuré.
Les titres détectés sans total sont indiqués et exclus des sommes renseignées ;
la couverture globale reste désactivée tant que ce périmètre est incomplet.
Pour un investissement entièrement libéré, saisir un total égal à son réalisé.

Les réserves utilisent **Réserves appels de fonds**, toutes sous-catégories et
pondérations incluses, valorisées aujourd'hui en EUR. Si ce nom existe dans
plusieurs taxonomies, choisir la bonne dans le menu du widget. Une sélection
explicite est enregistrée par identifiants et survit à un changement de nom.
Une catégorie absente ou supprimée n'est jamais remplacée par le portefeuille entier.

Les identifiants d'attributs commencent par `fork.pe.commitment.`. Les identifiants
de widgets sont `PE_COMMITMENTS_DETAIL` et `PE_COMMITMENTS_RESERVES`.

## Validation

8 tests métier et 3 tests de widgets/colonnes, plus les 4 tests des widgets
allocation/objectifs existants : opérations, devises et contre-valeurs, frais,
absence de taux, distributions, doublons, réserves imbriquées, ambiguïtés,
persistance, lecture seule et recalcul après modification d'une opération.
Les tests utilisent des données synthétiques ; aucun portefeuille personnel
n'est lu ou modifié. La construction Mac est vérifiée séparément.

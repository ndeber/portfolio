# DBi V15 — performances après impôts

**Depuis V16 :** le [profil commun PEA Saxo](TAX_ENVELOPES.md) remplit automatiquement les paramètres de ses positions, y compris celles déjà fermées. Le guide ci-dessous décrit le socle V15 ; les particularités du PEA sont dans le guide V16.

Cette version repart de la livraison `livraison-dbi-v14-2026-10-05`. Elle conserve les fonctionnalités DBi, obligations et Private Equity de cette livraison et ajoute un même calcul fiscal aux lignes de l’« État des actifs », à leurs regroupements et aux widgets du tableau de bord.

## Ouvrir la démonstration

Lancer **Ouvrir DBi V15.command** dans le dossier de livraison. Le lanceur démarre une session distincte. Sur l’écran d’accueil, choisir **Ouvrir un fichier**, puis sélectionner `Fiscalite-Performance-Demo.xml` dans le même dossier. Les données sont fictives ; aucun portefeuille personnel n’a été modifié ou intégré à cette démonstration.

Dans **État des actifs**, choisir le préréglage **Après impôts — depuis le 06/10/2025**. Les colonnes affichent le gain en EUR, le gain en %, le %TW, le TRI et le rendement total annualisé. « Net » signifie ici « après fiscalité estimée ». La date suivie de → indique le début de la période, jusqu’à la date de la vue. Le préréglage **Fiscalité — détail** présente les provisions et la valeur nette. **Valeurs brutes** retrouve la vue sans ces colonnes.

Dans le **Tableau de bord**, ouvrir **Performance après impôts — investissable**. Ses cinq widgets portent tous sur la classification **Portefeuille investissable**, espèces comprises, depuis le 6 octobre 2025. Les périodes et périmètres se modifient par le menu habituel de chaque widget.

Résultats au **6 octobre 2026**, pour un taux illustratif de 25 % sur les trois positions :

| Indicateur | Portefeuille investissable |
| --- | ---: |
| Gain avant impôts | 1 750,00 EUR |
| Gain après impôts estimés | 1 312,50 EUR |
| Performance nette simple | 3,65 % |
| %TW après impôts | 3,65 % |
| Rendement total annualisé après impôts | 3,65 % |
| TRI après impôts | 3,70 % |
| Valeur de marché, espèces comprises | 37 437,50 EUR |
| Valeur nette estimée | 37 312,50 EUR |
| Provision fiscale restante, signée | 125,00 EUR |

La démonstration contient une vente partielle en CTO, une clôture annuelle de holding, leurs paiements fiscaux rapprochés, une ligne AV en perte et un apport en cours d’année. Les impôts déjà payés, 312,50 EUR, ne sont pas déduits une deuxième fois. Les 25 % sont une hypothèse de démonstration, pas un taux légal proposé.

## Paramétrer un portefeuille

1. Ouvrir une copie de travail du portefeuille avec V15. Dans **Fiscalité estimée**, sélectionner chaque couple compte-titres / titre puis **Paramétrer…**.
2. Choisir le profil, un taux explicite, le traitement des pertes, la date de départ et le capital fiscal restant à cette date. Les quantités sont vérifiées contre l’historique. Pour repartir avant le premier achat, choisir une date antérieure et une base nulle. Une base à la clôture d’une date inclut les opérations de ce jour ; le moteur rejoue les opérations des jours suivants.
3. Pour une holding imposée annuellement sur la variation de valeur, indiquer la première clôture. Le moteur estime ensuite une clôture à la même date chaque année et réévalue la base pour éviter de retaxer le même gain. Ce profil convient uniquement aux titres effectivement soumis à cette convention ; le profil holding à la cession conserve sa base jusqu’à la vente.
4. Dans **Rapprocher un paiement…**, renseigner, si nécessaire, la dette fiscale initiale positive ou la créance initiale négative à la date de départ. Rattacher une part de chaque opération réelle d’impôt ou remboursement au titre concerné. Un paiement d’IS global peut être réparti entre plusieurs titres. Le montant total affecté doit correspondre au paiement réel dans le périmètre complet.
5. Pour une distribution PE, utiliser **Ventiler une distribution…** : saisir la part imposable ; le reste rembourse le capital fiscal. Un appel de fonds augmente le capital fiscal.
6. Dans **État des actifs**, ouvrir le choix des colonnes et le groupe **Fiscalité estimée**. Ajouter les indicateurs et sélectionner la même période pour les comparer. Dans le tableau de bord, ajouter les cinq widgets du groupe **Fiscalité estimée**, puis choisir le périmètre souhaité.

Les informations fiscales sont enregistrées dans le fichier du portefeuille. Enregistrer le fichier après paramétrage. La base est propre au couple compte-titres / titre : un même titre peut avoir des hypothèses différentes en CTO, en holding ou en AV.

## Comment sont calculés les montants

**Pas de compte espèces virtuel à créer.** Le registre fiscal conserve séparément les provisions latentes, les charges déclenchées par une vente ou une clôture, et le rapprochement avec les paiements réels. Les colonnes et les widgets choisissent la vue brute ou nette sans créer de liquidités fictives.

La provision d’une position évolue avec sa valeur : **(valeur de marché − capital fiscal restant) × taux**. Une perte peut produire une économie potentielle signée si cette option est activée ; sinon la provision est plafonnée à zéro. Cela représente une hypothèse d’utilisation des pertes, sans calcul global de capacité de compensation, report de déficits ou plafond par enveloppe.

À chaque vente, le moteur libère la quote-part du capital fiscal au coût moyen pondéré, estime l’impôt sur le gain réalisé et garde la provision correspondant aux titres restants. Les retenues déjà inscrites dans l’opération sont prises en compte. Les frais restent des coûts de performance. Une taxe d’acquisition est traitée comme une dépense effective.

La valeur nette d’un périmètre complet est sa valeur réelle, espèces comprises, diminuée des provisions et des dettes fiscales encore présentes. Un paiement rapproché baisse simultanément les espèces et la dette : il ne provoque donc pas une seconde perte de performance. Une dette antérieure à la période doit être renseignée à l’ouverture pour que son paiement ne soit pas imputé au rendement courant.

Le profil **AV** utilise une convention de rendement net par support. Une vente interne garde sa réserve fiscale virtuelle dans le périmètre contenant les espèces ; elle n’est pas présentée comme un impôt immédiatement dû. Ce n’est pas un calcul fiscal de rachat du contrat : ancienneté, primes, abattements, prélèvements déjà acquittés et règles propres au contrat ne sont pas reconstruits.

## Les cinq indicateurs

- **Gain EUR** : valeur nette finale − valeur nette initiale − apports + retraits, sur la période.
- **Performance %** : gain / (valeur nette initiale + apports cumulés). Ce pourcentage ne pondère pas la durée des apports.
- **%TW** : chaînage des rendements quotidiens, apports en début de journée et retraits en fin de journée.
- **Rendement annuel** : `(1 + %TW)^(365 / nombre de jours) − 1`. C’est uniquement le rendement total annualisé, revenus et variation de valeur compris. Aucun widget de revenus encaissés n’est ajouté.
- **TRI** : rendement annualisé des flux datés et de la valeur nette finale. Il reste vide si aucune solution numérique stable ou plusieurs solutions sont détectées.

Les pourcentages d’un regroupement sont recalculés sur son capital et ses flux ; ils ne sont pas la moyenne des pourcentages des titres. Pour mesurer le portefeuille investissable, inclure les comptes espèces et les comptes payeurs associés. Une ligne titre seule traite l’achat comme un apport, les ventes et revenus comme des sorties ; une charge fiscale payée hors de cette ligne constitue du capital supplémentaire mobilisé. Cela peut expliquer un écart entre son %TW et celui du périmètre comprenant les espèces.

L’annualisation n’est pas calculée sur une période de zéro jour. Une base de rendement non positive ne produit pas un pourcentage artificiel. Le rendement annualisé décrit la performance observée ; il ne représente pas une somme distribuable garantie.

Conventions de performance : [documentation Portfolio Performance sur le TWR](https://help.portfolio-performance.info/en/concepts/performance/time-weighted/) et [sur le rendement pondéré par les flux](https://help.portfolio-performance.info/en/concepts/performance/money-weighted/).

## Actualisation et données à compléter

Les valeurs fiscales et les performances sont recalculées lorsque les cours, opérations ou paramètres du portefeuille sont actualisés. Il n’y a pas de flux boursier supplémentaire : la fraîcheur du calcul dépend de celle des cours disponibles dans l’application.

Un **—** signifie que le calcul ne dispose pas des informations nécessaires. Le survol du widget et la vue **Fiscalité estimée** donnent le motif. Un périmètre incomplet ne publie pas un rendement net partiel présenté comme complet.

Cette première version requiert notamment :

- Une base historique couvrant la période pour chaque titre concerné, y compris un titre vendu pendant celle-ci. La modification de l’historique antérieur impose une revalidation de la base.
- Des cours et conversions en EUR disponibles aux dates utiles. Les taux fiscaux sont explicites et constants depuis la base ; changer l’hypothèse recalcule cet historique. Une chronologie de changements de taux n’est pas encore gérée.
- Le rapprochement des paiements fiscaux, avec les mêmes pondérations pour le titre et son compte payeur dans un périmètre fractionné. Une ligne espèces seule contenant un paiement associé à un titre extérieur à son périmètre affiche donc —.
- La reprise explicite des bases après transfert de titres ou livraison sortante. Le moteur s’arrête sur ces opérations ; il n’invente pas leur continuité fiscale.
- Une attribution sans ambiguïté des revenus au compte-titres. Les revenus d’un même titre détenu dans plusieurs comptes-titres partageant les mêmes espèces peuvent nécessiter une séparation des comptes ou un point de départ ultérieur.
- La part imposable explicite des distributions PE. Les flux PE contenant directement des retenues ou frais doivent être ventilés séparément.
- Une imposition renseignée sur les intérêts d’espèces non nuls. Un intérêt sans retenue renseignée est actuellement bloquant, même s’il est exonéré ; aucun paramétrage fiscal par compte espèces n’est encore fourni.

Les profils sont des conventions de simulation par position, pas un moteur de déclaration fiscale. Le paramétrage doit refléter les règles applicables à chaque titre et l’utilisation envisagée des pertes.

## Validation de la livraison

Les tests couvrent les flux datés, les ventes partielles et totales, les impôts déjà retenus, les clôtures annuelles, les pertes, les remboursements, les bases nulles avant achat, la convention AV et les distributions PE. Les widgets et le total natif ont aussi été comparés dans l’interface macOS sur la démonstration. Le fichier `Validation.txt` de la livraison précise les résultats et les empreintes des composants livrés.

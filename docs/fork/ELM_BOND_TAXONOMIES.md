# ELM dans les quatre taxonomies Obligations

Étude et réalisation du 16 octobre 2026, sur la branche publiée `fork/integration`.

## Résultat attendu

La commande **Outils du portefeuille → Actualiser les taxonomies Obligations…**
inclut désormais ELM Market Navigator ETF à chaque préparation, s'il existe dans
le portefeuille et n'est pas retiré. Aucun titre, achat ou vente n'est créé.
L'ISIN de référence est **US81752T4296**. Sans ISIN renseigné, le ticker exact ELM
ou le nom exact du fonds est accepté ; un autre ISIN renseigné reste exclu.

Le calcul ne traite que les obligations : il relit les positions réelles d'ELM,
puis les compositions des fonds obligataires détenus. Les actions, fonds
monétaires, bons du Trésor classés monétaires et liquidités sont exclus.
Les quatre ventilations concernent la notation, l'échéance, la région/pays et
le type d'émetteur.

**Les pourcentages appliqués sont des pourcentages du titre ELM entier.** Leur
somme est la fraction obligataire d'ELM, et non 100 %. La partie restante demeure
non affectée dans ces quatre taxonomies. Les catégories conservent leurs UUID,
couleurs, objectifs et références des widgets.

## Étude du fonctionnement existant

Le service `BondAllocationSources` préparait les quatre ventilations. Le
périmètre retenait les obligations détenues en quantité positive dans Classes
d'actifs ainsi que quelques ISIN explicitement forcés. ELM pouvait donc être
absent du périmètre, et aucun fournisseur obligataire ne savait le décomposer.

Le contrat `BondComposition.Slice` imposait auparavant 10 000 points de base
pour tout titre. Même une bonne ventilation des fonds sous-jacents aurait donc
affecté à tort tout ELM aux obligations. Le contrat accepte maintenant une
couverture explicite, dont la somme des catégories doit être exactement égale
au poids annoncé. L'ancien constructeur conserve son exigence de 100 %.
Le plan d'application refuse une couverture partielle pour un titre autre
qu'ELM. Les autres fournisseurs gardent leur comportement.

Le parcours existant reste : préparer sur une copie en mémoire, afficher les
changements et leurs sources, appliquer au portefeuille ouvert, notifier les
vues, puis enregistrer normalement. La sauvegarde ne change ni de fichier, ni
de format, ni de chiffrement. Le compte rendu reste dans
`fork.bondAllocation.lastUpdate`.

## Sources et sélection des positions

La [page officielle ELM](https://www.elmfunds.com/elm-market-navigator-etf) publie
deux informations distinctes : une allocation cible `alloc-json`, utilisée par
**Ajuster ELM…**, et un lien **Download FULL Holdings CSV**, utilisé ici.
Les dix premières positions affichées sur la page ne suffisent pas : AGG n'y
figure pas actuellement, alors qu'il détient bien une petite position.

Le fichier complet consulté est daté du **16 octobre 2026**. Les seules lignes
obligataires sont :

| Sous-jacent | Identifiant CUSIP | Poids publié arrondi dans ELM | Traitement |
| --- | --- | ---: | --- |
| SCHP — Schwab U.S. TIPS ETF | 808524870 | 9,54 % | Obligations indexées sur l'inflation américaine |
| BND — Vanguard Total Bond Market ETF | 921937835 | 4,72 % | Obligations américaines diversifiées |
| AGG — iShares Core U.S. Aggregate Bond ETF | 464287226 | 0,03 % | Obligations américaines diversifiées |
| VBIL — Vanguard 0–3 Month Treasury Bill ETF | 922040845 | 33,98 % | Exclu : poche monétaire, comme dans Ajuster ELM |
| FGXXX — First American Government Obligations Fund | 31846V336 | 0,46 % | Exclu : fonds monétaire |
| Actions, immobilier coté, Cash & Other | Identités contrôlées | Solde | Exclus |

Les calculs utilisent les valeurs de marché divisées par l'actif net publié,
et non les poids arrondis à deux décimales. La somme des trois fonds obligataires
représente ainsi **14,29003649 %** d'ELM avant exclusion de leur cash interne.
La ligne FGXXX comporte une date dans son libellé ; cette date ne devient jamais
une échéance obligataire, puisque le support est monétaire.

La liste des identités reconnues couvre les 19 fonds de l'export consulté.
Une nouvelle position non reconnue ou un changement de CUSIP bloque le calcul
ELM et conserve les affectations existantes. Il faut alors ajouter et vérifier
le nouveau sous-jacent, plutôt que deviner sa classe d'actifs.

## Analyse des trois fonds sous-jacents

### SCHP

L'[export officiel Schwab du 15 octobre 2026](https://www.schwabassetmanagement.com/sites/g/files/eyrktu361/files/product_files/SCHP/SCHP_FundHoldings_2026-10-15.CSV)
contient 49 lignes : 48 TIPS américains et un fonds monétaire GVMXX. La poche
obligataire représente **99,7316403315 %** de SCHP. GVMXX est exclu, y compris de
la ventilation d'échéances malgré sa date technique dans l'export.

Les échéances individuelles des TIPS alimentent les tranches existantes.
Pays : États-Unis. Type d'émetteur : Gouvernement. Selon la règle demandée,
la notation est la note souveraine des États-Unis ramenée à la catégorie AA.

Le site Schwab oppose actuellement un refus HTTP 403 aux lectures automatisées
classiques depuis cet environnement. L'export a été récupéré par le navigateur
sur son lien officiel. Le programme tente d'abord le téléchargement en ligne ;
en cas d'erreur réseau, il utilise cette copie officielle embarquée, avec un
avertissement explicite et sa date réelle. **Ce recours expire après 45 jours**,
soit après le 29 novembre 2026 pour cette copie. Après expiration, si Schwab
reste inaccessible, les ventilations ELM sont conservées avec une erreur.
Une erreur de format d'un export téléchargé n'est pas masquée par la copie.

### BND

Les données proviennent des [services publics utilisés par le site Vanguard](https://advisors.vanguard.com/investments/products/bnd/vanguard-total-bond-market-etf),
portefeuille 0928 : positions complètes, qualité de crédit, émetteurs et régions.
La composition consultée est datée du **30 septembre 2026**.

- Échéances : dates finales des lignes obligataires. Les livraisons TBA
  « Fannie Mae or Freddie Mac » ne désignent pas la maturité des crédits
  hypothécaires ; elles sont affectées à **Non déterminée**. Une date absente
  ou passée est également non déterminée. Aucune duration n'est utilisée.
- Notation : AAA, AA, A, BBB, BB, B, CCC et inférieur ; les petites positions
  sous investment grade sont conservées avant l'arrondi final. La rubrique
  **U.S. Government** reçoit la note souveraine américaine, selon la demande.
- Émetteurs : Finance devient Banques & institutions financières ; Industrial
  et Utilities deviennent Entreprises ; CMBS, Government Mortgage-Backed et
  Asset Backed deviennent Crédit titrisé. Dans Treasury / Agency, le Trésor est
  isolé grâce aux positions du même mois, puis le solde est affecté aux agences.
  Foreign et Other restent Autres, faute de détail sur leur type d'émetteur.
- Géographie : Vanguard fournit ici des régions. North America reste
  **Amérique du Nord**, sans être transformé artificiellement en États-Unis.
  Pacific devient Asie-Pacifique. Le Moyen-Orient non ventilé reste Autres.

Les poids nets publiés des obligations, **99,09560 %**, déterminent la fraction
obligataire de BND. Sa ventilation interne normalise les expositions positives ;
les couvertures négatives ne créent pas de catégories à poids négatif. Cette
convention et les dates sont indiquées dans l'aperçu.

### AGG

L'[export complet iShares](https://www.ishares.com/us/products/239458/ishares-core-u-s-aggregate-bond-etf/latest-holdings.csv)
du **15 octobre 2026** permet de lire les valeurs de marché, classes d'actifs,
pays, secteurs et échéances individuelles. Seule la classe Fixed Income est
retenue. Cash, Money Market, Cash Collateral and Margins restent exclus.
La fraction nette obligataire de l'export consulté est **99,15303608 %**.

Les secteurs sont regroupés dans les catégories existantes : Treasury,
Sovereign et Local Authority en Gouvernement ; Agency et Supranational en
Agences publiques & supranationaux ; Financial Institutions en Banques ;
Industrial et Utility en Entreprises ; MBS Pass-Through, CMBS et ABS en Crédit
titrisé. Les pays sont convertis par les correspondances déjà utilisées par
la commande Obligations.

Les notations viennent de la table **Credit Quality du portefeuille** sur la
[page officielle AGG](https://www.ishares.com/us/products/239458/ishares-core-u-s-aggregate-bond-etf),
datée du 14 octobre 2026 lors de l'étude. Cash and/or Derivatives est exclu avant
normalisation. La différence de date avec les positions reste visible.

## Règle de notation souveraine américaine

La catégorie américaine reçoit **AA**, à partir de la notation **Aa1** publiée
par [Moody's](https://www.moodys.com/web/en/us/about-us/usrating.html), action du
16 mai 2025 et vérification documentaire du 16 octobre 2026. Les sous-crans sont
regroupés dans les grandes catégories de la taxonomie existante.

Cette règle est appliquée aux TIPS de SCHP et à la rubrique U.S. Government de
BND. Il s'agit d'un **profil souverain daté**, explicitement mentionné comme
non relu automatiquement à chaque clic. Il n'est pas présenté comme une nouvelle
notation téléchargée. Une évolution future de la note souveraine impose de
mettre à jour ce profil.

## Formule et arrondis

Pour un sous-jacent i et une catégorie k :

`exposition(k) = somme [ poids réel d'i dans ELM × part nette obligataire d'i × part de k dans les obligations positives d'i ]`

Les facteurs sont exprimés en fractions dans cette formule. Les liquidités
internes n'entrent donc dans aucune catégorie obligataire. Les différentes
dates de composition restent attachées aux sources ; il ne s'agit pas d'un
inventaire intégral à une date unique.

L'arrondi n'intervient qu'après l'agrégation. Les plus grands restes attribuent
les derniers points de base ; les égalités sont départagées par nom de catégorie.
La somme est égale à la poche obligataire nette arrondie. À partir des positions
consultées, cela représente environ **14,22 % du titre ELM**.

## Résultat du contrôle intégré du 16 octobre 2026

Le téléchargement et l'analyse depuis l'application assemblée ont réussi pour
les quatre familles. Le recours à la copie officielle SCHP est signalé.
Chaque tableau ci-dessous totalise **14,22 % d'ELM entier** ; pour lire les poids
au sein de la seule poche obligataire, diviser chaque valeur par 14,22 %.
Les catégories arrondies à zéro ne sont pas affichées.

| Notation | Part d'ELM |
| --- | ---: |
| AAA | 0,15 % |
| AA | 12,93 % |
| A | 0,57 % |
| BBB | 0,57 % |

| Échéance résiduelle | Part d'ELM |
| --- | ---: |
| < 6 mois | 0,01 % |
| < 1 an | 0,31 % |
| < 2 ans | 1,91 % |
| < 3 ans | 1,76 % |
| < 4 ans | 1,56 % |
| < 5 ans | 1,27 % |
| 5–7 ans | 1,76 % |
| 7–10 ans | 2,52 % |
| 10–15 ans | 0,33 % |
| 15–20 ans | 0,77 % |
| 20–25 ans | 0,69 % |
| Plus de 25 ans | 1,31 % |
| Non déterminée | 0,02 % |

Les libellés « < n ans » désignent les tranches successives déjà utilisées par
le portefeuille, pas des valeurs cumulées : « < 2 ans » commence après un an,
« < 3 ans » après deux ans, etc. Les bornes calendaires existantes sont conservées.

| Région ou pays publié | Part d'ELM |
| --- | ---: |
| États-Unis | 9,55 % |
| Amérique du Nord, pays non ventilés | 4,41 % |
| Europe | 0,10 % |
| Marchés émergents | 0,05 % |
| Asie-Pacifique | 0,04 % |
| Autres | 0,07 % |

Les deux premières lignes se trouvent au même endroit dans l'arbre régional :
une affectation directe à Amérique du Nord ne constitue pas un doublon de
l'affectation à sa sous-catégorie États-Unis.

| Type d'émetteur | Part d'ELM |
| --- | ---: |
| Gouvernement | 11,80 % |
| Crédit titrisé | 1,00 % |
| Entreprises | 0,81 % |
| Banques & institutions financières | 0,39 % |
| Agences publiques & supranationaux | 0,04 % |
| Autres | 0,18 % |

## Contrôles et limites

- Identité ELM sur la page officielle, ticker/CUSIP de chaque position,
  absence de doublon, dates cohérentes, actif net positif et unique.
- Cohérence poids/valeurs ; rapprochement des valeurs avec l'actif net à
  0,005 % maximum. L'export du 16 octobre présente un écart de 8 767,98 USD,
  soit 0,00146872 % de l'actif net. Le calcul ne renormalise pas ELM pour le cacher.
  Une omission plus petite que cette tolérance ne peut pas être détectée par
  ce seul rapprochement.
- Positions ELM de moins de 46 jours ; données sous-jacentes de 90 jours
  maximum ; copie Schwab de 45 jours maximum ; refus des dates futures.
- Contrôle des en-têtes et des totaux des tables. Les libellés inconnus,
  tables incohérentes et catégories absentes ou ambiguës conservent toute
  l'affectation du couple ELM/taxonomie concerné.
- Annulation sans application, délai de préparation borné à 150 secondes
  par famille ELM, aperçu invalidé si le portefeuille change.
- Aucune création ou reconstruction de taxonomie. Les catégories nécessaires,
  dont **Non déterminée**, doivent déjà exister.
- Si un export ELM valide ne contient plus aucun fonds obligataire, les
  anciennes affectations ELM des quatre taxonomies sont supprimées dans
  l'aperçu et seulement appliquées après validation de cet aperçu.
- **Ajuster ELM…** conserve son rôle sur les objectifs cibles ; Classes d'actifs,
  Pilotage, autres titres, opérations, cours et paramètres de sauvegarde restent
  inchangés par cette extension.

## Validation et reconstruction

La sélection finale comprend **225 tests, sans échec**, et six tests de packaging.
Les tests couvrent l'identification, les exports officiels et cas d'erreur,
les échéances individuelles, la règle souveraine, la géographie régionale,
l'exclusion du cash, les arrondis partiels, une poche nulle, l'idempotence,
les aperçus périmés et l'enregistrement/réouverture XML, ZIP, binaire et chiffré.
Le contrôle de l'application assemblée vérifie également sa ressource officielle
embarquée et le chargement du gestionnaire et de l'aperçu.

Le manifeste de la release authentifie les sources nécessaires et réutilise
l'environnement Java 21/Maven publié avec `fork-2026-10-15`, sans dépendance
nouvelle ni portefeuille personnel. Reconstruction sur Mac Apple Silicon :

```sh
python3 tools/fork/rebuild-macos.py \
  --release-file tools/fork/build-elm-bonds-2026-10-16.json \
  --output ../PortfolioPerformance-ELM
```

Le test facultatif des sources en ligne est :

```sh
python3 private-equity-product/check-packaged-pdf.py \
  ../PortfolioPerformance-ELM/PortfolioPerformancePE.app/Contents/Eclipse \
  --java-home /chemin/vers/un/JDK-21 \
  --elm-bonds --live-elm
```

Les résultats de validation et empreintes de l'application finale sont joints
à la release `fork-elm-bonds-2026-10-16`.

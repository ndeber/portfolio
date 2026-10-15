# Positions & impact DBi

Cette vue native se trouve dans **Rapports → Positions & impact DBi**. Elle lit les positions du fichier ouvert avec le moteur de valorisation PP. Elle ne crée aucune opération et ne modifie pas les cours. La taxonomie devises reste accessible dans la navigation PP. Les actions de mise à jour DBi et le choix du proxy US figurent dans **Positions DBi & sources** ; la barre de boutons du haut est vide.

## Vue initiale : expositions notionnelles

L’onglet **Expositions** présente uniquement les **notionnels**, sans sélecteur de mesure. Il présente la baseline connue, les futures DBi et leur somme documentée. La partie scénarios a été entièrement retirée : aucun choc, calcul de gain/perte ni export de scénarios. Le sélecteur, les mentions et les liens VA ont été retirés de cette vue en V11.

## Périmètre et unités

Le sélecteur propose le portefeuille complet et, lorsqu'elle existe, la taxonomie **Pilotage Global**. Ce dernier périmètre est sélectionné par défaut. Les affectations réelles des supports définissent ce périmètre ; les poids cibles ne participent pas au calcul. Comptes et positions sont regroupés par instrument, comme dans PP. Une valorisation ou un change manquant rend les poids indisponibles. Le taux de change de repli implicite 1:1 de PP n'est pas utilisé.

Le dénominateur inclut DBi. La baseline l'exclut, sans renormaliser le reste. Pour chaque facteur :

`baseline = somme(valeur du support × exposition identifiée) / valeur du périmètre`

`apport DBi = poids réel DBi × exposition du fonds`

`total = baseline + apport DBi`, seulement si la baseline est intégralement couverte et les mesures sont comparables dans le mode choisi.

La colonne **Après DBi connu** affiche également le sous-total identifié lorsque la couverture est partielle. Il porte alors un astérisque, comme la baseline, et ne représente pas l'exposition totale. La couverture reste affichée dans la colonne voisine.

Les axes sont indépendants et ne s'additionnent pas à 100 %. Les actions sont ventilées économiquement par géographie : il ne s'agit pas d'une estimation de bêta au S&P 500, EAFE ou MSCI EM. Le Canada reste hors de ces trois axes. La Corée du Sud est traitée comme émergente selon la convention MSCI, même si le fichier la range sous Pacifique. Les régions larges ou « Other » restent inconnues ; la fraction géographique connue n'est pas renormalisée.

La couverture est la fraction de la valeur absolue de la baseline pour laquelle le facteur est identifié. Les montants manquants et les fractions inconnues ne deviennent pas des zéros. Les correspondances manuelles ont priorité et doivent être datées et sourcées.

## Devises et taux

La carte notionnelle et les positions utilisent **USD/EUR** et **JPY/EUR** : un signe positif indique une exposition longue au dollar ou au yen, un signe négatif une exposition vendeuse. Un support entièrement USD non couvert vaut +100 % USD/EUR et 0 % JPY/EUR ; un support entièrement JPY non couvert vaut 0 % USD/EUR et +100 % JPY/EUR. Une devise de cotation de fonds ou une géographie actions n'est pas assimilée à son exposition de change.

Les futures DBMF restent publiés en EUR/USD et JPY/USD dans les sources. Pour leurs notionnels signés rapportés à la NAV, `USD/EUR = −EUR/USD −JPY/USD` et `JPY/EUR = JPY/USD`. Cette décomposition préserve les deux jambes et la sensibilité de change au premier ordre. Si une jambe nécessaire manque, le résultat reste inconnu. L'overlay n'inclut pas le cash / collatéral DBMF ni la couverture de la part UCITS.

Les paramètres explicites anciens sont convertis à la lecture sans modifier le fichier : les deux anciennes jambes sont requises pour déterminer USD/EUR. Une case vide ne devient pas zéro ; les anciens coefficients restent consignés dans la source du paramétrage. Les nouvelles validations enregistrent la version 2, empêchant une double conversion. Les taxonomies devises, exprimées directement en USD et JPY, n'ont pas besoin de migration.

La taxonomie **Expositions – Devises** décrit les sous-jacents après couverture de change. Elle est indépendante des taxonomies actions. À sa création, les comptes sont affectés selon leur devise réelle, les supports DBi dans « DBi – traité séparément », et les autres supports dans « À renseigner ». La création répétée préserve les affectations existantes. Les clés des catégories permettent de renommer les libellés sans perdre leur correspondance. Les répartitions EUR, USD et JPY alimentent les deux axes FX de la carte ; GBP, CHF, CAD, AUD, CNY, HKD, autres devises et fractions non renseignées restent hors de cette projection et réduisent sa couverture. Elles sont visibles dans la taxonomie. La somme des affectations doit rester comprise entre 0 et 100 % ; une somme supérieure à 100 % ou un poids négatif invalide la projection FX de ce support. Les expositions signées ou avec levier passent par le paramétrage explicite du support. Les modifications de taxonomie sont conservées avec l'enregistrement PP habituel ; les nouvelles lignes ajoutées ultérieurement restent non affectées jusqu'à leur classement.

### Proposition pour les fonds détenus et ELM

**Outils du portefeuille → Proposer les expositions devises…** ouvre un aperçu avant/après, également accessible depuis la taxonomie devises. Le périmètre reprend les fonds et ETF non retirés détenus en quantité positive dans les portefeuilles actifs, y compris PE/VC/dette privée et fonds euros, plus ELM même sans détention. Les titres vifs, cryptomonnaies et ETC ne sont pas assimilés à des fonds. ELM est reconnu par son ISIN ou son paramétrage existant. Les transferts et dates des opérations sont pris en compte.

La proposition ne modifie rien. Chaque ligne affiche la ventilation actuelle, la proposition, la méthode et la date ; l'onglet des sources explique les hypothèses et les manques. Toutes les propositions sont décochées par défaut et doivent être sélectionnées explicitement. Les fonds sans proposition exploitable restent inchangés et ne peuvent pas être cochés. Annuler conserve les affectations. Appliquer modifie uniquement les fonds sélectionnés dans la taxonomie devises ; l'enregistrement PP reste nécessaire.

La géographie, le mandat d’investissement et la devise de cotation ne produisent plus de proposition chiffrée. Les profils EUR 100 % fondés sur une politique de couverture sont retirés. Deux sources nettes sont intégrées :

- **IVO Emerging Markets Corporate Debt R EUR** : découverte du reporting mensuel depuis la page H24 Finance, contrôle de l’ISIN, de la part R EUR, de la date et des deux lignes « Exposition … après couverture ». Au 31 août 2026 : EUR 99,4 %, USD 0,6 %. En cas d’échec, le relevé archivé à cette date est affiché avec le motif d’échec ; il n’est pas présenté comme une actualisation réussie.
- **Cinq ETF Vanguard détenus** : relevés vérifiés du rapport annuel au 30 juin 2025, tableaux Currency Risk, colonne Net après contrats de change, rapportés à l’actif net du même compartiment à la même date. La devise de base est calculée par différence avec les expositions étrangères. Parts non couvertes capitalisantes/distribuantes du même portefeuille. Ces relevés historiques ne s’actualisent pas automatiquement ; la date ancienne reste visible. CNH est conservé séparément de CNY.

**ELM** : le CSV officiel de positions réelles est toujours relu et contrôlé, mais ne suffit pas à déterminer les devises après couverture des ETF sous-jacents. Aucune affectation USD/CAD n’est déduite de leurs mandats. **iShares Infrastructure** : les devises de titres sont disponibles, mais les contrats FX empêchent d’assimiler leur somme à un tableau net. **Pluvalca 2031** : le tableau par devise du portefeuille titres couvre EUR 89,93 % de la NAV au 30 juin 2025, pas le portefeuille complet. Ces sources restent dans les diagnostics sans proposition chiffrée. Les autres fonds conservent leur affectation actuelle tant qu’un tableau adapté n’est pas validé.

Les tableaux mesurent le risque de change financier publié ; ils ne donnent pas les devises des chiffres d’affaires des entreprises. Une exposition signée ou avec levier n’est pas normalisée artificiellement pour entrer dans une taxonomie positive à 100 %.

Les PE et autres fonds sans détail de change exploitable sont explicitement listés à documenter. Aucune répartition n'est inventée depuis leur devise de part ou leur nom. Les fonds DBi gardent leur traitement séparé. Pour obtenir une ventilation nette exhaustive, des reportings supplémentaires sont nécessaires.

Les nouvelles devises sont ajoutées sans supprimer ni renommer les catégories existantes. Couleurs, objectifs et métadonnées des affectations réutilisées sont conservés. La provenance est stockée dans `fork.factor-map.currencies.audit.v1` et sur les affectations. La Factor Map signale les estimations validées ; sa couverture peut alors inclure des hypothèses. Les paramètres explicites par support restent prioritaires. Les modifications concurrentes du portefeuille rendent l'aperçu caduc et empêchent son application.

Les obligations ne sont pas réparties automatiquement entre les Treasury 2 ans, 10 ans et long : la maturité et la duration ne sont pas interchangeables. Le paramétrage attend un notionnel équivalent à DV01 comparable, après identification de la courbe et de la devise ; inflation et spread de crédit ne sont pas assimilés à la courbe Treasury.

L'or physique est reconnu par les ISIN de la liste explicite dans `FactorMap` (iShares Physical Gold, WisdomTree Physical Gold / Swiss Gold, Invesco Physical Gold, Xetra-Gold), ainsi que le support spot « Gold 1 oz » coté en USD. Les mines d'or et les autres métaux ne sont pas classés comme or. La source par défaut reste une correspondance instrument, pas une analyse du risque du fonds.

## Invesco Bloomberg Commodity

L'ISIN **IE00BD6FTQ80** est reconnu directement, sans taxonomie matières premières. L'onglet **Invesco · matières premières** présente toutes les lignes de l'indice, leur poids et leur montant économique estimé à partir de la valeur actuelle du support dans le périmètre choisi. La source est le flux public `holdings/index` utilisé par la page officielle Invesco, distinct du panier de substitution de cet ETF synthétique. La composition de l'indice constitue une approximation de l'exposition de l'ETF, pas un relevé quotidien de ses positions.

Seuls l'or et le WTI rejoignent les dix facteurs DBi. Le Brent, les produits pétroliers, les autres métaux et les produits agricoles restent affichés séparément. La source embarquée contient 25 lignes au **30 avril 2026**, totalisant 100 % sans renormalisation. Les dates anciennes restent visibles. Une composition future n'est pas appliquée au calcul. Les taux et le change ne sont pas déduits de cette composition.

**Mettre à jour depuis Invesco** consulte la source en arrière-plan et recalcule la baseline. Une réponse tronquée, régressive, future, dupliquée, contenant un instrument inconnu ou ne totalisant pas 100 % à l'arrondi près est rejetée ; la dernière composition reste disponible. La composition validée est conservée dans `fork.factor-map.invesco.v1` et devient persistante à l'enregistrement PP. Une modification concurrente n'est jamais écrasée. Un paramétrage explicite du support reste prioritaire sur les taxonomies et sur Invesco ; revenir à l'automatique dans le formulaire pour reprendre les sources.

## DBi UCITS et DBMF US

Le fonds détenu LU3359622902 est une part EUR couverte du fonds UCITS. Sa composition officielle du 30 juin 2026 publie des quantités de futures, deux produits structurés, des Treasury bills, des comptes de marge et du cash. Elle ne donne pas les notionnels complets, les sous-jacents des notes ni la couverture EUR. Ces données ne permettent pas de reconstruire un look-through économique complet. Le texte publié est consultable avec un lien vers son original.

Le [reporting mensuel de la part EUR couverte](https://www.imgp.com/uploads/factsheets/LU3359622902_FACTSHEETS_EN.pdf), vérifié au 31 août 2026, publie des expositions actions, devises et matières premières en % NAV, mais les taux en duration. Il ne fournit pas le détail de la couverture de la part EUR. Il est accessible depuis « Positions DBi & sources » ; ses durations ne sont pas converties en notionnels. Les chiffres UCITS et US ne sont pas mélangés dans la carte.

Le mode **Simulation UCITS avec DBMF US**, activé par défaut (choix mémorisé dans les préférences locales), applique les futures DBMF au poids réel des parts UCITS prises en charge. Il ignore les différences de fonds et la couverture de change de la part. Ce mode est explicitement indiqué dans les titres. Pour une détention directe US53700T8273, les futures du fonds sont utilisables sans ce mode. Les autres produits DBi ne sont pas automatiquement remplacés par DBMF.

Le tableau public DBMF fournit une colonne « Market Value » représentant, pour les futures retenus, leur valeur notionnelle signée. L'exposition est calculée à partir de cette valeur divisée par **TOTAL NET ASSETS**, sans utiliser les poids arrondis publiés. Le cash et les Treasury bills sont exclus de l'overlay futures ; il ne s'agit donc pas du bilan économique intégral du fonds. Plusieurs échéances d'un même facteur s'additionnent. Une famille absente reste inconnue plutôt que d'être supposée nulle. Un instrument inattendu, une ligne dupliquée, une date future ou des dates mélangées font échouer la mise à jour.

## Actualisation et stockage

À la première ouverture du jour, puis sur **Actualiser iMGP**, les publications DBMF et UCITS sont consultées en arrière-plan. Le lien du PDF UCITS est découvert dans la page officielle ; sa date interne est contrôlée. Aucun contenu du portefeuille n'est transmis. En cas d'erreur ou de publication régressive, la dernière référence est conservée avec sa date. Une publication âgée de plus de sept jours est signalée comme ancienne, y compris la publication trimestrielle.

Les références publiques sont conservées dans les préférences locales du fichier PP. Les hypothèses par support sont enregistrées dans la propriété `fork.factor-map.v1` du portefeuille uniquement lors de la validation du formulaire et de l'enregistrement PP habituel. Le formulaire offre un retour à l'automatique et refuse l'écrasement d'une modification concurrente.


## Simplification V11

L’onglet de compensation des taux a été retiré. Ses sensibilités en EUR/point de base ne peuvent pas être fusionnées avec les expositions notionnelles. Les anciens paramètres de duration stockés dans le fichier sont préservés mais ne participent pas à la carte notionnelle. Aucune nouvelle allocation obligataire n’est appliquée.

**Positions DBi & sources** contient le bouton **Mettre à jour les positions DBi**, le choix du proxy US et l’état de la dernière tentative. Le bouton est désactivé pendant la lecture des sources, puis réactivé. Les dates des compositions UCITS et DBMF US sont affichées au-dessus de leurs tableaux respectifs et sont conservées avec les données en cas d’échec. La date d’une vérification réussie ne remplace pas celle des positions.

## Lecture du graphique

La baseline commence à zéro si elle ne comporte aucune exposition négative. Les colonnes portefeuille avant/après utilisent le même nombre de pixels par point, avec une origine adaptée à leurs valeurs. Les expositions négatives, lorsqu’elles existent, restent visibles à gauche de zéro. L’apport DBi dispose d’un zoom propre, indiqué au-dessus du panneau, pour montrer les petites positions longues et courtes. Les valeurs restent des points du portefeuille ; les longueurs DBi ne se comparent pas directement aux autres colonnes. Les bornes de chaque échelle sont affichées.

## Tri des positions

Chacun des quinze en-têtes de « Positions du portefeuille » permet un tri décroissant puis croissant. Montants, dates et expositions sont comparés sur leur valeur brute ; le signe et les valeurs partielles sont respectés. Les valeurs inconnues restent en fin de liste dans les deux sens. Le tri actif reste appliqué lors des recalculs.

## Vérification

Les tests `FactorMapTest` couvrent le dénominateur, les signes, la séparation des unités, la distinction UCITS/DBMF, les données manquantes/futures, le périmètre par affectations, les géographies partielles, le change cash, la persistance des hypothèses et le parseur de la publication officielle. `CurrencyCommodityTest` couvre la taxonomie devises, la projection partielle, les affectations invalides, la priorité des hypothèses, les compositions Invesco et leur persistance. `CurrencyProposalsTest` couvre le périmètre, l'aperçu sans mutation, la sélection, les conflits, la persistance, le refus des déductions géographiques et la source ELM. `CurrencyReportsTest` vérifie les parts/dates IVO, les couvertures obligatoires, les répartitions nettes Vanguard, la réconciliation à la NAV et le rejet des expositions négatives ou incomplètes. `private-equity-product/check-factor-map.py` vérifie dans le produit construit la navigation, les quatre vues, les notionnels seuls, l’absence d’actions en en-tête et la date DBMF US, le formulaire et, avec `--live`, les trois sources officielles. L’option `--refresh-dbi` exerce le bouton de mise à jour depuis l’onglet sources et vérifie ses états ainsi que la date du résultat. Il copie tout fichier fourni avant de le lire. L'option `--prepare-portfolio` crée une nouvelle copie avec la taxonomie et la composition Invesco, refuse tout écrasement et vérifie sa relecture et ses valorisations. L'option `--currency-proposals` vérifie l'aperçu et son annulation sur le fichier copié, puis l'application/relecture sur une copie en mémoire seulement.

Sources :

- [Positions DBMF](https://www.imgp.com/us/fund/US53700T8273-imgp-dbi-managed-futures-strategy-etf/)
- [Part UCITS EUR couverte](https://www.imgp.com/fund/LU3359622902-imgp-dbi-managed-futures-fund/)
- [Composition UCITS au 30 juin 2026](https://extranet.imgp.com/api/attachments/preview/Portfolio_Composition-2026-06-30-EN-00-LU3359622902.pdf)
- [Reporting VA d'août 2026](https://www.imgp.com/wp-content/uploads/2026/09/DBMF-August-2026-Video-Update-Deck.pdf)
- [Invesco Bloomberg Commodity UCITS ETF](https://www.invesco.com/uk/en/financial-products/etfs/invesco-bloomberg-commodity-ucits-etf.html)

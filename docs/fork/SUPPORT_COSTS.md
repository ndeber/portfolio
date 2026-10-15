# Frais intégrés aux valeurs liquidatives

La livraison du 13 octobre 2026 ajoute le widget **Frais supports** au PP personnalisé et une copie de la sauvegarde de **Xapa Ventures.portfolio** du 13 octobre à 09 h 12. Le périmètre initial est la classification **Portefeuille Investissable** de la taxonomie **Objectifs et Classes d'actifs**. Ses affectations servent au calcul ; le poids cible de 65 % du nœud n'est pas appliqué aux encours.

Le tableau de bord **Frais des supports** comporte trois widgets : YTD, douze mois glissants et année 2025. Le clic droit sur le titre permet de changer la période ou le périmètre avec les réglages natifs de PP, notamment une année civile ou des dates libres. Le bouton **Détail par support** affiche les montants, taux, jours de détention, sources et limites. Le widget affiche seulement le taux annuel pondéré et les frais avec le même composant et le même style KPI que les indicateurs natifs de PP. Le bouton **Infos** regroupe la période, le périmètre, la couverture et les limites ; **Infos *** signale un résultat partiel ou indisponible. Le menu natif **Périmètre** permet de sélectionner le client, un portefeuille, un compte, un titre, une classification ou un filtre personnalisé. Les périodes en cours (trimestre, mois, semaine) sont arrêtées à aujourd’hui ; une période entièrement future est refusée.

## Données par titre

- **TER / Frais courants** : frais de gestion et autres frais de fonctionnement, avec le champ standard PP `ter`.
- **Frais VL — complément annuel** : coûts de transaction internes ou frais de swap explicitement documentés, en plus du TER. La nature et la date de ces coûts sont détaillées dans la note du support.
- **Frais VL — source**, **vérifié le**, **périmètre / limites** : traçabilité et exclusions.

Les champs sont modifiables dans les attributs supplémentaires du titre. La configuration de colonnes **Frais des supports** est ajoutée à la Situation du patrimoine ; les configurations existantes sont conservées.

Les 27 supports actuellement détenus dans le périmètre sont renseignés : 23 supports avec documents officiels identifiés par ISIN et quatre détentions directes (Bitcoin, Ethereum, Solana et or) avec un taux de frais de fonds de zéro. Les coûts de plateforme, réseau, stockage ou courtage ne sont pas des frais intégrés à un fonds et ne sont pas ajoutés ici. Les comptes de liquidités ont également un coût intégré de zéro.

Les positions anciennes et clôturées restent présentes pour les périodes où elles ont été détenues. Une absence de taux reste une donnée manquante, jamais un zéro implicite. Leur contribution est exclue de l'estimation partielle et la couverture est affichée.

## Calcul

Pour chaque jour de la période PP `(début, fin]`, le moteur reconstitue les positions avec les instantanés natifs, les opérations datées et les pondérations de classification. Il prend la valorisation disponible dans PP et le change historique vers EUR. Un cours futur n'est pas utilisé. Un change manquant rend le calcul indisponible. Les journées où une position est négative sont exclues et signalées ; elles ne créent ni frais négatifs ni correction des opérations.

Le coût estimé est la somme des encours journaliers en EUR multipliés par le taux annuel renseigné, puis divisés par 365 ou 366 suivant l'année. Les week-ends sont inclus, avec le dernier cours disponible. L'arrondi au centime intervient après la somme journalière.

Le **taux annuel pondéré des supports couverts** est le coût estimé divisé par la somme des encours-années couverts. Les actifs directs à taux zéro y participent. Le **coût sur la période** est rapporté à l'encours moyen couvert sur cette période. Un troisième taux inclut les liquidités positives du périmètre ; il n'est affiché que lorsque tous les encours des supports ont un taux et une valorisation. Les soldes débiteurs ne sont pas des supports et ne diluent pas ce taux.

La couverture est mesurée en **encours-jours des supports**, et non en nombre de titres. Une valorisation manquante est signalée séparément. Les encours demeurent ceux du fichier et peuvent comporter des cours anciens : ce traitement ne met pas les cotations à jour.

## Limites de l'estimation

Les taux renseignés sont supposés constants sur la période choisie. Les historiques de changement de tarif ne sont pas reconstitués. Une estimation sur 2025 avec les taux renseignés aujourd'hui n'est donc pas une facture historique de 2025. Les documents utilisés peuvent être plus anciens que la date de vérification ; leur date est indiquée dans les notes. Le taux d'Amundi Emerging Markets Equity Growth est issu du DIC accessible de décembre 2024, sous son ancien nom New Silk Road, et demande une revalidation.

Les commissions de surperformance ne sont pas transformées en taux sur l'encours à partir de leur barème contractuel. Elles sont signalées et exclues tant qu'un coût annuel applicable n'est pas documenté. Les coûts internes de transaction et de swap ne sont inclus que lorsqu'un complément explicite a été renseigné. La couverture de 100 % des taux ne signifie pas que tous les coûts variables ont été documentés.

Il n'y a **aucune nouvelle déduction de performance**. Ces coûts sont déjà reflétés dans les VL. Le moteur ne crée ni frais de compte, ni transaction, ni ajustement de cours. Les frais d'enveloppe, droits d'entrée et de sortie, fiscalité et frais de courtage de l'investisseur sont exclus.

## Vérifications

Douze tests ciblés vérifient une année complète, les frais inconnus, l'arrêt des frais à une vente, les variations de cours, l'année bissextile, les compléments, les cours futurs, les pondérations partielles, les supports/comptes en devises les positions historiques négatives et les périodes calendaires en cours. La copie est relue après export natif ; ses opérations, comptes, portefeuilles, titres et cours ont la même empreinte financière que la sauvegarde source. Le fichier source n'est jamais écrasé.

Le contrôle de l'interface utilise une copie du portefeuille, une copie du cache de changes existant et un espace de travail temporaire.

# DBi V16 — profil commun PEA Saxo

Le profil **PEA Saxo** est enregistré dans la copie `Xapa Ventures - Profil PEA Saxo.portfolio`, créée à partir du portefeuille de la livraison V14 du 5 octobre 2026. Les opérations, les cours et les comptes de ce fichier source sont conservés. Le fichier source reste intact ; cette copie ne reprend pas les modifications que vous auriez effectuées depuis dans un autre fichier.

## Ouvrir le portefeuille préparé

1. Lancer **Ouvrir DBi V16.command** dans le dossier de livraison.
2. Sur l'accueil, choisir **Ouvrir un fichier**, puis `Xapa Ventures - Profil PEA Saxo.portfolio` dans ce même dossier.
3. Dans **État des actifs**, utiliser le filtre **Perso - Saxo Bank - PEA** et la configuration **PEA — net depuis l'ouverture**. Le compte espèces réel du PEA est inclus.
4. Dans le **Tableau de bord**, choisir **PEA Saxo — après impôts**. Les cinq widgets reprennent le gain EUR, le gain %, le %TW, le TRI et le rendement total annualisé après fiscalité estimée.

La période commence le 23 janvier 2024, juste avant le premier versement du 24 janvier, pour inclure cet apport dans les calculs. Les colonnes et les widgets doivent avoir la même période et le même périmètre pour être comparables. Le rendement annuel est le rendement **total annualisé**, revenus et variation de valeur compris ; ce n'est pas un revenu annuel encaissé.

## Informations déjà renseignées

| Champ | Valeur |
| --- | --- |
| Compte-titres | Perso - Saxo Bank - PEA |
| Compte espèces associé | Perso - Saxo Bank - PEA EUR |
| Ouverture / premier versement | 24 janvier 2024 |
| Historique | Complet depuis l'ouverture, confirmé |
| Résidence fiscale | France, confirmée |
| Hypothèse | Retrait après cinq ans, à partir du 24 janvier 2029 |
| Taux estimatif commun | 18,60 %, modifiable |
| Titres couverts | 10, dont 8 positions fermées et 2 encore ouvertes |

Le taux de 18,60 % correspond aux prélèvements sociaux actuellement indiqués pour un retrait après cinq ans par [impots.gouv.fr](https://www.impots.gouv.fr/particulier/questions/jai-un-plan-depargne-en-actions-pea-les-retraits-sont-ils-imposables), source consultée le 6 octobre 2026. Il sert d'hypothèse constante sur toute la période comparée. Ce n'est ni une reconstitution des prélèvements historiquement payés, ni une garantie du taux applicable en 2029.

## Modifier une seule fois pour toute l'enveloppe

Dans **Fiscalité estimée**, sélectionner une ligne du PEA puis **Profil d'enveloppe…**, ou double-cliquer sur une ligne indiquée **PEA Saxo · hérité**. Modifier le profil commun puis cliquer sur **Enregistrer pour cette enveloppe**. Enregistrer ensuite le fichier du portefeuille.

Les bases et les quantités sont reconstruites à partir de l'historique des achats et des ventes. Aucune base individuelle, date de clôture annuelle ou quantité fiscale n'est à saisir pour les positions héritées. Les achats ajoutés plus tard à ce compte héritent aussi du profil. Un changement de cours recalcule les estimations lors de l'actualisation habituelle ; cette fonction n'ajoute pas de flux de cotations en continu.

Les réglages individuels éventuellement présents restent prioritaires et sont comptés comme exceptions dans le formulaire. Cette copie ne contient aucune exception sur le PEA Saxo. Le PEA-PME Saxo et les autres enveloppes ne sont pas modifiés.

## Gains, pertes et positions fermées

Chaque titre conserve sa contribution : une hausse augmente sa réserve estimée ; une baisse la réduit. Lors d'une vente interne au PEA, le gain réalisé et sa réserve restent suivis, même après la vente de toute la position. La vente interne ne crée pas un impôt immédiatement dû. La colonne **Réserve / dette EUR** de la vue fiscale rend cette réserve visible ; pour le PEA elle est virtuelle.

Pour le PEA complet, titres et espèces compris, les pertes compensent les gains dans l'estimation. La provision totale ne descend pas sous zéro : un PEA globalement en perte ne crée pas de créance fiscale fictive. Les frais du compte espèces diminuent également le gain du plan. Le total peut donc différer de l'addition des contributions individuelles affichées, notamment si la compensation serait négative ou si certaines positions ont été fermées et ne figurent plus parmi les actifs détenus.

Le plafonnement global s'applique à un PEA complet, avec la même pondération pour les titres et les espèces, et sans exception fiscale individuelle remplaçant son profil. Un sous-ensemble de titres présente leurs contributions, pas la fiscalité d'un retrait de tout le plan.

**Aucun compte espèces virtuel n'est créé.** Les espèces réelles restent inchangées. La fiscalité estimée est une réserve utilisée par les colonnes et les widgets.

## Résultats de contrôle

Au 6 octobre 2026, avec les cours déjà présents dans le fichier V14 :

| Indicateur — PEA complet depuis l'ouverture | Résultat |
| --- | ---: |
| Gain avant impôts | 23 014,55 EUR |
| Réserve fiscale estimée | 4 280,71 EUR |
| Gain après impôts estimés | 18 733,84 EUR |
| Performance nette simple | 12,49 % |
| %TW après impôts | 23,67 % |
| TRI après impôts | 7,35 % |
| Rendement total annualisé après impôts | 8,17 % |

Les cinq résultats nets ont été comparés entre les widgets et le total natif d'« État des actifs ». Une modification du taux commun a également été vérifiée dans les deux vues.

## Périmètre de cette étape

Le profil couvre ce PEA ouvert après 2018, avec un historique complet, un compte espèces EUR dédié et aucun retrait enregistré. Un futur retrait réel, transfert de titres ou paiement fiscal demandera un traitement complémentaire : les calculs concernés afficheront une explication plutôt qu'une estimation inadaptée.

Les autres enveloppes conservent le fonctionnement de la V15. Un widget portant sur tout le **portefeuille investissable** doit attendre le paramétrage des autres positions de ce périmètre pour afficher une performance nette complète. Le guide [Performances après impôts](TAX_PERFORMANCE.md) décrit les colonnes, les widgets et les paiements rapprochés de la V15.

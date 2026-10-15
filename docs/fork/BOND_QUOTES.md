# Actualisation des obligations individuelles

Branche : `feature/bond-quotes`, fondée sur `feature/update-actions`.
Elle ne dépend pas des opérations PE, des widgets ni de l'ajustement ELM.

## Utilisation

Dans le portefeuille ouvert, choisir **Outils du portefeuille → Actualiser les
obligations individuelles…**. Cocher les titres, récupérer les données, examiner
les dates, cours, sources et calculs, puis **Appliquer au portefeuille ouvert**.
Les changements non enregistrés sont inclus dans la préparation. Enregistrer
ensuite normalement (ou laisser l'enregistrement automatique agir).

La commande ne modifie aucune taxonomie, notamment Pilotage Global, ni les
transactions, les quantités ou les autres titres. Elle ne crée pas de nouveau titre.

## Périmètre pris en charge

Les conventions sont explicites pour les dix ISIN suivants :

| ISIN | Source et convention |
| --- | --- |
| FR0013154044 | OAT nominale 1,25 % 25 mai 2036, Borsa Italiana officiel + coupon annuel ACT/ACT, EUR |
| US91282CRF04 | Treasury nominal 4,625 % 15 août 2036, CUSIP 91282CRF0, référence FedInvest END OF DAY, USD |
| DE000BU2Z072 | Bundesbank, archives mensuelles depuis le 10 juillet 2026 |
| DE000BU27014 | Bundesbank, séries KCP/KDP depuis le 27 août 2025 |
| DE0001102622 | Bundesbank, séries KCP/KDP depuis le 18 octobre 2022 |
| FR0014001N38 | Borsa Italiana + AFT OAT€i, échéance 25 juillet 2031, coupon 0,10 % |
| FR0013327491 | Borsa Italiana + AFT OAT€i, échéance 25 juillet 2036, coupon 0,10 % |
| FR0000188799 | Borsa Italiana + AFT OAT€i, échéance 25 juillet 2032, coupon 3,15 % |
| FR0013410552 | Borsa Italiana + AFT OAT€i, échéance 1er mars 2029, coupon 0,10 % |
| FR0000186413 | Borsa Italiana + AFT OATi, échéance 25 juillet 2029, coupon 3,40 % |

Les titres doivent avoir la devise attendue : USD pour le Treasury, EUR pour les autres. Ce fournisseur n'est
pas un moteur universel de valorisation de toutes les obligations.

## Cours et conservation des historiques

Les prix sont par 100 de nominal, **coupon couru inclus** (convention « dirty » du
script). Pour les obligations allemandes, clean et dirty sont publiés par la
Bundesbank. Pour les OAT indexées : `(clean + coupon couru ACT/ACT) × coefficient AFT`,
au règlement T+2 selon le calendrier TARGET. La date stockée reste la date de cotation.
Les données intrajournalières ne remplacent pas le prix officiel Borsa.

Les cours déjà identifiés par le bloc d'audit du script et le fournisseur manuel
sont conservés et complétés, y compris avant le début de la nouvelle fenêtre source.
Pour une convention inconnue, une case décochée par défaut autorise explicitement
le **remplacement intégral**. L'aperçu indique la période obtenue et le nombre de
dates supprimées. Cela évite de mélanger cours clean et dirty.

Les deux fournisseurs (historique et dernier cours) des titres appliqués passent
à Manuel, leurs anciennes propriétés de téléchargement sont retirées. Les champs
`xapa.bond.*` et le bloc d'audit dans la note sont actualisés, en préservant le reste
de la note. Les anciens réglages d'actualisation automatique ne peuvent ainsi
réintroduire des cours d'une autre convention.

Une source vide, contradictoire, future, un coefficient manquant ou un dernier cours
plus ancien que celui du portefeuille bloque l'opération. Une erreur sur un titre
ne modifie aucun titre : relancer éventuellement en décochant le titre concerné.
Les données de plus de sept jours sont signalées. Annuler ou modifier le portefeuille
pendant la préparation invalide l'application de l'aperçu.

## Organisation du code

- `updates.bonds.BondSpec` : instruments et conventions pris en charge ;
- `BondSources` : téléchargements, CSV Bundesbank, Borsa, sélection des publications AFT ;
- `BondWorkbook` : lecture XLS/XLSX sans évaluation de formules ;
- `BondMath`, `BondQuote` : règlement, coupon et conversion vers la précision PP ;
- `BondUpdate` : préparation et application au modèle vivant, validation préalable ;
- `ui.updateactions.bonds` : choix, progression annulable, aperçu et commande ;
- `model/bond-quotes.e4xmi` : contribution au menu commun.

Apache POI 5.5.1 lit les anciens XLS ; les XLSX sont lus comme ZIP/XML bornés en taille.
Les fichiers sont détectés par leur contenu, car l'AFT peut diffuser un XLSX portant
l'extension `.xls`. Les dépendances POI sont déclarées dans les deux plateformes
cibles, le bundle et la feature pour que le produit livré les embarque.

Les pages AFT peuvent refuser les connexions Java. Sur macOS, le transport HTTPS
fourni par `/usr/bin/curl` est utilisé pour ce domaine public uniquement, sans shell,
sans identifiants et sans installation supplémentaire. Les autres plateformes
utilisent le client HTTP Java de PP si ce composant système est absent. Les requêtes
sont bornées en temps et en taille ; les fichiers temporaires sont supprimés. Une
erreur serveur ou un délai dépassé est réessayé une fois. Les indisponibilités AFT
restent possibles et ne produisent aucune modification partielle.

La publication AFT la plus récente est choisie par la date de son nom de fichier ;
la page contient parfois d'anciens liens masqués. Seule la dernière plage continue
de coefficients est utilisée. Tous les téléchargements partagés sont mis en cache
pour une seule préparation, sans cache persistant ni dépendance au script externe.

## Validation

Tests ciblés : calendrier TARGET et années bissextiles, coupon remis à zéro,
arrondi PP, deux séparateurs CSV, deux layouts Bundesbank, prix officiel Borsa,
XLS binaire et XLSX à valeurs calculées en cache, publications AFT anciennes masquées,
coefficients manquants, migration explicite, historique dirty conservé, concurrence,
validation atomique des métadonnées et relecture du modèle après application.

Sources publiques : [Bundesbank](https://www.bundesbank.de/en/service/federal-securities/prices-and-yields),
[AFT OAT€i](https://www.aft.gouv.fr/fr/oateuroi-principaux-chiffres),
[AFT OATi](https://www.aft.gouv.fr/fr/oati-principaux-chiffres),
[Borsa Italiana](https://www.borsaitaliana.it/borsa/obbligazioni/mot/euro-obbligazioni/scheda/FR0013327491-MOTX.html?lang=it).

Fiche officielle OAT€i 2031 : https://www.aft.gouv.fr/fr/titre/fr0014001n38.

## Références nominales 2036

L’OAT du 25 mai 2036 est à 82 jours du Bund 15 août 2036 ; le Treasury échoit
exactement le 15 août 2036. Les deux actifs sont créés sans transaction.
L’OAT a un coupon annuel et un règlement TARGET T+2. Le Treasury a un coupon
semestriel ACT/ACT, valorisé à date du cours FedInvest (T+0, pas règlement marché T+1).

FedInvest diffuse des prix de titres miroir GAS destinés aux organismes publics :
références indicatives, pas cotations exécutables. Seule la colonne END OF DAY
est utilisée ; BUY/SELL ne remplacent jamais une clôture nulle. Le formulaire public
est consulté sans compte. Date, CUSIP, coupon, maturité et absence de call sont
vérifiés. La dernière clôture des sept jours précédents complète l’historique
existant, sans reconstitution antérieure automatique. Prix US par 100 USD de nominal.

L’outil YTM convertit le rendement US semestriel en taux **effectif annuel** :
`(1 + y/2)^2 - 1`, comparable aux taux annuels OAT/Bund. Il diffère du yield US
couramment affiché en convention semestrielle. Les spreads futurs devront aligner
les dates et expliciter l’écart de maturité et les sources de prix.
Le script externe Portfolio Update est également étendu : création idempotente
par ISIN, devise USD explicite, conservation des historiques dirty de ces références.

Caractéristiques : [AFT](https://www.aft.gouv.fr/fr/titre/fr0013154044),
[TreasuryDirect](https://www.treasurydirect.gov/TA_WS/securities/search?cusip=91282CRF0&format=json).
Prix : [FedInvest](https://www.treasurydirect.gov/GA-FI/FedInvest/selectSecurityPriceDate).

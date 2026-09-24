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

## Périmètre initial

Les conventions sont explicites pour les sept ISIN repris du script :

| ISIN | Source et convention |
| --- | --- |
| DE000BU2Z072 | Bundesbank, archives mensuelles depuis le 10 juillet 2026 |
| DE000BU27014 | Bundesbank, séries KCP/KDP depuis le 27 août 2025 |
| DE0001102622 | Bundesbank, séries KCP/KDP depuis le 18 octobre 2022 |
| FR0013327491 | Borsa Italiana + AFT OAT€i, échéance 25 juillet 2036, coupon 0,10 % |
| FR0000188799 | Borsa Italiana + AFT OAT€i, échéance 25 juillet 2032, coupon 3,15 % |
| FR0013410552 | Borsa Italiana + AFT OAT€i, échéance 1er mars 2029, coupon 0,10 % |
| FR0000186413 | Borsa Italiana + AFT OATi, échéance 25 juillet 2029, coupon 3,40 % |

Seuls les titres en EUR avec l'un de ces ISIN sont proposés. Ce fournisseur n'est
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

# Historique des cotations PE calculées

L’onglet des cotations historiques affiche désormais une ligne calculée à chaque
date d’appel de fonds ou de distribution qui n’a pas déjà de cotation enregistrée.
La colonne **Origine** distingue « Calculée — appels / distributions » de
« Cotation enregistrée ».

Ces lignes utilisent le même calcul que la liste des actifs : NAV précédente
plus l’appel par part, ou moins la distribution par part. Plusieurs opérations
le même jour donnent une seule ligne de fin de journée. Elles se recalculent
après modification ou suppression des opérations. Une cotation enregistrée le
même jour reste prioritaire : elle est supposée inclure les flux de la journée.

Les lignes calculées sont en lecture seule et ne sont pas enregistrées comme
des cours indépendants. Cela évite un double comptage et les cours périmés après
correction d’une opération. Pour les modifier, corriger l’opération correspondante ;
pour utiliser une NAV publiée, saisir une cotation habituelle. Le fichier du
portefeuille n’est pas réécrit pour afficher cet historique.

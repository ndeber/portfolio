# Inflation EU (IPCH), références quotidiennes AFT

Branche `feature/aft-inflation`, dépendance `feature/bond-quotes` pour le transport
AFT et le lecteur de classeur, et du menu commun `feature/update-actions`.

**Outils du portefeuille → Actualiser l’inflation EU (IPCH)…** cherche exactement
un instrument nommé **EU (IPCH)**, configuré sur le fournisseur Manuel. L'autre
instrument « EU IPCH - Indice pour OATi€ » n'est pas modifié.

La page officielle est https://www.aft.gouv.fr/fr/oateuroi-principaux-chiffres.
La commande découvre le lien actuel du classeur des coefficients OAT€i puis lit
les colonnes A/B : date et **référence quotidienne d'inflation**. Elle ne lit ni
les coefficients par obligation ni la série mensuelle IPCH. Les valeurs sont
celles publiées pour les OAT€i (zone euro hors tabac), base 2025 = 100, arrondies
aux cinq décimales officielles. Le changement de base est expliqué sur la page AFT.

L'aperçu indique les dates couvertes, le nombre d'ajouts/corrections, les anciennes
et nouvelles valeurs et le lien source. Les références futures déjà publiées par
l'AFT sont conservées et explicitement signalées : il ne s'agit pas de projections.
La validation met à jour le modèle ouvert ; l'enregistrement du fichier reste à
la main de l'utilisateur. Annuler ne modifie rien. Aucun accès Google nécessaire.

Les dates existantes hors source sont conservées. Des doublons, trous quotidiens,
valeurs manquantes/non positives, une source trop ancienne, un changement de base
annoncé, un écart supérieur à 1 % avec une valeur déjà présente, ou une modification
du portefeuille pendant l'aperçu bloquent l'application. Une base incompatible
nécessite une vérification et une migration explicite ; elle n'est jamais raccordée
automatiquement. Une note de provenance est ajoutée à l'instrument uniquement.

Tests : `AftInflationTest` couvre lecture de la bonne colonne, dates futures,
trous/doublons, résolution du lien et base, sélection non ambiguë, aperçus périmés,
corrections, préservation de l'autre indice et idempotence.

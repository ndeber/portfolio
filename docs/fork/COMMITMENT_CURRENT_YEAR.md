# Engagements PE : FYe, YTD et restant annuel

Chaque colonne annuelle 2026–2033 du tableau de saisie est une prévision totale
sur l’année, libellée **FYe**. Les colonnes calculées de l’année courante sont :

- **YTD** : achat initial s’il tombe dans l’année, hors frais, plus appels de fonds
  du 1er janvier à la date du jour, en EUR. Distributions, ventes, autres achats
  et opérations futures sont exclus, conformément au calcul de l’engagement réalisé.
- **Restant** : FYe moins YTD, sans masquer les dépassements négatifs.

L’année courante suit le calendrier (2026 aujourd’hui, 2027 l’année suivante).
Les colonnes de prévisions gardent leurs années fixes. Les widgets et le tableau
de disponibilité ne déduisent des réserves que les appels encore attendus ; un
restant négatif ne crée pas une entrée de trésorerie prévisionnelle.

À l’ouverture d’un ancien échéancier, l’aperçu ajoute le réalisé de chaque année
aux anciennes prévisions de restant. La conversion est enregistrée une seule
fois en cliquant **Appliquer au portefeuille**, pour tous les fonds concernés.
Annuler ne modifie rien. Une fois converties, les valeurs FYe restent fixes lors
de nouveaux appels ; YTD augmente et le restant diminue automatiquement.

Les totaux respectent les mêmes exclusions et conversions EUR que les engagements
existants. Une donnée ou un taux manquant est signalé et ne vaut pas zéro.

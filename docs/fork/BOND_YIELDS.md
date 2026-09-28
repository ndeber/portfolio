# Rendements de la poche obligataire

Branche : `feature/bond-yields`.

Dans **Outils du portefeuille → Actualiser les YTM et YTM-Frais…**, choisir la date du portefeuille puis **Actualiser les sources**. Vérifier les lignes dans l’aperçu et appliquer au portefeuille ouvert. L’enregistrement du fichier reste celui de Portfolio Performance. Fermer sans appliquer abandonne les observations préparées.

Le widget **Obligations : YTM et YTM-Frais** affiche les moyennes et la couverture à la date du jour. Les attributs de titres `YTM` et `YTM-Frais` sont alimentés lors de l’application. Un historique daté distinct permet de recalculer les moyennes à une date passée sans employer une observation postérieure à cette date. Ce n’est pas un historique de publication en temps réel : la date d’arrêté du reporting est utilisée.

## Périmètre et calcul

- Positions classées sous **Obligations** dans **Classes d’actifs**, au prorata des affectations à cette poche.
- Obligations individuelles reconnues par le module de cours, et CCT italien IT0005554982.
- Vanguard IE00BG47KH54 et Goldman Sachs LU0234688595, même avant leur achat : leurs rendements sont consultables mais une position nulle ne pèse pas dans les moyennes.
- Pondération par les valeurs en EUR à la date choisie. Les conversions de devises manquantes interrompent le calcul plutôt que d’utiliser un taux fictif.
- Frais annuels déduits une fois du rendement brut ; zéro pour les obligations individuelles. Une donnée déjà nette ne subit aucune nouvelle déduction et n’est pas transformée artificiellement en rendement brut.
- Pour les obligations indexées : rendement réel calculé sur prix hors coefficient d’indexation, puis `(1 + rendement réel) × (IPCH du jour / IPCH un an avant) − 1`. La série est **EU (IPCH)**. Pas de valeur future, anniversaire exact requis et dernier point de moins de huit jours. Sans indice utilisable, ces rendements sont exclus de la moyenne et signalés.
- Un YTW peut remplacer un YTM absent. Il est identifié dans chaque ligne et son inclusion est signalée dans la synthèse et le widget.
- Données manquantes : pas de zéro inventé. Les dénominateurs brut et net sont séparés ; leur couverture en valeur est affichée.

## Sources et limites

Connecteurs : Vanguard, Goldman Sachs (découverte du reporting mensuel de la bonne part), JPMorgan, Carmignac, Eiffel, Schelcher, Pluvalca, Sextant Regatta 2031, IVO, Sycomore, Tikehau et Borsa Italiana pour le CCT. Les obligations françaises et allemandes utilisent les cours officiels du module obligataire existant et un calcul actuariel annuel ACT/ACT (règlement à deux jours ouvrés ; coupon couru allemand reconstruit à partir du cours hors coupon). Pour le CCT à taux variable, le rendement publié par Borsa Italiana est utilisé, sans figer arbitrairement les coupons futurs. Le rendement net fiscal italien n’est pas utilisé.

Les frais de l’attribut **Frais** sont prioritaires et éditables dans l’aperçu. Les frais retenus sont sauvegardés dans cet attribut lors de l’application, afin de conserver une correction manuelle lors des actualisations suivantes. En leur absence, les frais courants publiés sont récupérés pour Vanguard et Goldman Sachs. Les autres fonds restent sans rendement net tant que leurs frais ne sont pas renseignés.

Un document doit contenir l’ISIN de la part, un rendement identifiable et une date récente (62 jours maximum). Les échecs sont affichés ligne par ligne et conservent les observations précédentes. Les observations anciennes conservées restent utilisables mais sont signalées dans la synthèse. L’annulation abandonne l’ensemble de la récupération ; chaque requête réseau expire après 25 secondes.

Le reporting IVO fourni concerne la part USD, mais le commentaire de gestion publie explicitement le YTW du fonds en EUR et cite l’ISIN de la part EUR : seule cette valeur EUR est extraite, jamais le rendement USD. Pour Sextant, le YTM publié porte sur la partie investie ; cette convention est affichée. Un reporting Tikehau trop ancien est refusé. Une donnée peut être renseignée avec sa source et sa date via **Renseigner la ligne sélectionnée…**, en identifiant le YTW le cas échéant. Les fonds sans YTM pertinent, même classés dans la poche obligataire, restent dans la couverture manquante.

Le rendement publié d’un fonds garde les conventions du fournisseur, notamment concernant la couverture de change. La conversion des valeurs en EUR ne constitue pas une reconstitution du coût de couverture. La moyenne est une moyenne pondérée de rendements individuels disponibles, pas le TRI global du portefeuille ni une prévision de performance.

## Organisation et validation

- Calculs et historique : `name.abuchen.portfolio.updates.yields`.
- Sources réseau : `YieldSources`.
- Commande et aperçu : `name.abuchen.portfolio.ui.updateactions.yields`.
- Widget : `BondYieldWidget`.
- Tests : `YieldMathTest`, `BondYieldsTest`, `YieldSourcesTest`.
- Vérification des connecteurs publics et du chargement OSGi : `private-equity-product/check-packaged-pdf.py --yields` sur le paquet construit. Aucun portefeuille personnel n’est envoyé aux fournisseurs.

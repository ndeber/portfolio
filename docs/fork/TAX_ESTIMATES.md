# Fiscalité estimée par position

## Décision et périmètre

Le calcul est indépendant pour chaque couple **compte-titres × titre**, identifié
par les UUID existants. L'enveloppe choisit un profil ; elle ne constitue pas une
boucle de compensation. La contribution fiscale peut être négative. Une économie
potentielle n'est pas une créance certaine sur le fisc.

Le taux est une hypothèse explicite et datée, jamais une valeur légale implicite.
L'assurance-vie est une convention d'attribution par support, pas un calcul de
rachat du contrat. Les titres à imposition annuelle utilisent une base fiscale
corrigée des écarts déjà fiscalisés, distincte du coût économique historique.

## Lots

1. **Vue dynamique et paramètres persistants** : une ligne par position, profil,
   taux, traitement des pertes, base fiscale totale en EUR, date de validation,
   quantité et empreinte des opérations. Valeur de marché, écart fiscal latent,
   contribution signée et valeur nette estimée. Recalcul sur les événements de
   mise à jour du modèle ; bouton d'actualisation. Aucun mouvement de trésorerie.
2. **Événements fiscaux** : ventes partielles, sorties, clôtures annuelles et
   régularisations. Historique des bases et paramètres ; rapprochement manuel
   avec les documents fiscaux. Ventilation des paiements existants par références
   d'opérations, sans création de débits supplémentaires.
3. **Performance avant/après** : séries datées de charge acquise et de fiscalité
   latente, variation des soldes entre début et fin, puis rendements. Ne pas
   présenter un écart à la base fiscale corrigée comme une performance totale.

La démonstration v2 amorce le lot 3 avec quatre colonnes en EUR pour les périodes
sans mouvement, revenu ni clôture à traiter : performance avant impôts, fiscalité
acquise (nulle dans ce cas), variation de fiscalité latente et performance nette
estimée. Le bouton « Début de période… » choisit la date initiale ; la date finale
est aujourd'hui. Par défaut, la vue part de la plus ancienne validation fiscale
enregistrée. Les cours historiques et changes des deux dates sont utilisés.

La performance avant impôts est la variation de valeur de marché sur ces périodes
sans flux, jamais l'écart à la base fiscale. Le calcul net soustrait la différence
entre contribution finale et initiale. Un changement des paramètres validé après
le début de période laisse le net indisponible, faute d'historique fiscal. Une
vente suivie d'un rachat, même à quantité identique, ou un revenu rattaché au titre
laisse la performance indisponible jusqu'au raccordement du registre des événements.
Les paiements globaux sans titre ne sont pas distribués arbitrairement aux lignes.

Ces colonnes sont dans la vue « Fiscalité estimée » ; les rendements natifs et les
courbes historiques ne sont pas modifiés. Les totaux de performance ne portent
que sur les positions ayant les quatre colonnes calculables, avec leur couverture.

Le premier lot n'effectue aucune migration automatique d'un portefeuille réel.
Une position non renseignée reste « À paramétrer », jamais « exonérée » par défaut.
Une variation du prix ne change pas la base ; une modification des mouvements
(même une vente suivie d'un rachat de même quantité) impose sa revalidation.
Les appels et distributions PE sont également surveillés. Une clôture annuelle
échue impose la validation d'une nouvelle base avant de reprendre l'estimation.

## Calcul du premier lot

Tous les montants de la vue sont en EUR ; la valorisation étrangère est convertie
au taux disponible à la date du calcul. Un change manquant bloque le résultat.

```
écart fiscal latent = valeur de marché en EUR − base fiscale totale en EUR
contribution = arrondi au centime(écart × taux)
```

Avec le choix « Pas d'économie sur les pertes », l'écart pris pour le calcul de la
contribution est plafonné à zéro. Avec le choix symétrique, il reste signé.
Un profil explicitement exonéré a un taux nul.

```
valeur nette estimée = valeur de marché − contribution
```

La valeur nette peut dépasser la valeur de marché si une économie potentielle est
retenue. Le tableau affiche séparément charges positives et économies potentielles.
Les totaux ne portent que sur les positions calculables et indiquent leur couverture.
Ils ne représentent ni le patrimoine net complet, ni un impôt déclarable.

## Persistance et absence de double compte

Les paramètres versionnés sont enregistrés dans les propriétés du fichier client,
compatibles avec les formats XML et binaire, sans modifier les transactions,
les cours, les taxonomies ou les fiches titres. Le premier lot conserve la dernière
validation uniquement ; ce n'est pas encore un registre fiscal historique.

Le moteur ne soustrait aucun paiement d'impôt à la valorisation d'un titre : les
impôts payés affectent déjà la trésorerie et la performance natives. Le raccordement
des dettes, acomptes et paiements appartient au lot 2. Les montants déjà nets
(notamment les cas signalés dans le projet) doivent être identifiés au paramétrage.

## Validation attendue

- hausse, baisse, passage en perte, taux nul et plafonnement des pertes ;
- arrondis symétriques et dépassements numériques ;
- même titre dans deux comptes avec des paramètres différents ;
- recalcul après changement de cours et conversion de devise ;
- refus des cours futurs et absence de données sans résultat inventé ;
- base périmée après mouvement, même à quantité constante, et clôture annuelle ;
- lecture/écriture XML et binaire, propriété invalide non écrasée ;
- aucune modification des paiements d'IS, des opérations ou des cours.

## Lot 2b — colonnes natives d’État des actifs (v3)

`StatementOfAssetsTaxValues` relie les paramètres des portefeuilles originaux
aux positions agrégées de la vue. Les filtres de portefeuille et leurs pondérations
sont appliqués après le calcul par position ; les UUID des copies filtrées ne
servent jamais de clés fiscales. Les classifications reçoivent les montants par
quantité, avec un arrondi cumulatif pour préserver les centimes.

Six colonnes en EUR, configurables nativement : contribution latente, valeur nette,
performance des titres avant impôts, fiscalité acquise, variation latente, performance
après impôts. Les quatre dernières acceptent les périodes natives. Les calculs
sont mis en cache pour le modèle de la vue et renouvelés à chaque actualisation.
Les filtres autres que la sélection/pondération des portefeuilles restent indisponibles
plutôt que de risquer un mauvais rattachement fiscal.

La valeur nette inclut les soldes réels des espèces, sans les modifier. Les
performances sont celles des titres actuellement détenus, sans attribuer les
paiements globaux aux positions. Un total fiscal incomplet reste indisponible.
La démo contient trois configurations natives persistantes ; aucun compte virtuel
ni aucune transaction synthétique n'est créé. Les ventes, clôtures et le
rapprochement des paiements restent hors de ce lot.

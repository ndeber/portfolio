# Ajuster ELM

Branche `feature/elm-refresh`, basée sur le socle `feature/update-actions`.
Les opérations PE, les widgets et l'identité visuelle sont indépendants.

## Utilisation

1. Ouvrir un portefeuille et choisir **Outils du portefeuille → Ajuster ELM…**.
2. Sélectionner le titre correspondant à **ELM Market Navigator ETF**.
3. Cocher les taxonomies à actualiser et choisir, pour chacune, les catégories
   Monétaire, Obligations et Actions. Les correspondances sans ambiguïté sont
   proposées ; les sélections enregistrées sont retrouvées par identifiant.
4. Facultatif : cocher **Ajuster aussi Pilotage**, choisir la taxonomie et sa
   catégorie **Moteur Dynamique**. ELM y sera affecté à 100 % et retiré des autres
   catégories de cette seule taxonomie, notamment du moteur statique.
5. Récupérer les données puis examiner les poids avant/après, la source et la date.
6. Créer une nouvelle copie, avec la même extension. L'application la relit puis
   l'ouvre dans un autre onglet. Le fichier initial et ses modifications non
   enregistrées restent intacts. Une destination existante est refusée.

Pour un fichier chiffré, définir le mot de passe de la nouvelle copie ; elle reste
chiffrée. Une annulation, une erreur réseau ou une allocation incohérente ne crée
pas de copie. Si le portefeuille change pendant la préparation, relancer l'action.

La configuration et la date/source de la dernière actualisation sont mémorisées
**dans la copie**. Si les affectations sont déjà à jour, un message l'indique et
aucun fichier supplémentaire n'est créé. Aucun cache ne retarde une demande manuelle.

## Périmètre précis

- Source : [page officielle ELM](https://www.elmfunds.com/elm-market-navigator-etf),
  bloc `alloc-json`, données `target`, date `datestamp`.
- Règles identiques au script : classe 17 = monétaire ; 13–16 = obligations ;
  1–12 = actions. Les classes omises comptent pour zéro, sauf la classe 17 requise.
- Total contrôlé avec la tolérance du script ; arrondi par plus grands restes en
  points de base, ordre monétaire/obligations/actions en cas d'égalité.
- Il s'agit de la **répartition cible publiée par ELM**, pas des positions réelles
  mesurées du fonds. Une publication vieille de plus de 45 jours est signalée dans
  l'aperçu ; une date future ou des dates incohérentes provoquent un refus.
- Les objectifs des catégories, leurs identifiants, leurs couleurs, les autres
  titres, les cours et les opérations ne sont pas modifiés. Les widgets gardent
  leurs références. Pilotage n'est jamais reconstruit et aucun objectif fixe
  personnel du script n'est importé.
- Les taxonomies et catégories doivent déjà exister. Le programme ne crée pas de
  titre ELM et n'effectue aucun achat/vente.

## Organisation

- `updates/elm/ElmAllocation` : téléchargement, lecture et arrondi.
- `updates/elm/ElmAdjustment` : plan de changements et application au modèle copié.
- `ui/updateactions/elm` : choix du périmètre et parcours utilisateur.
- `ui/model/elm-refresh.e4xmi` : contribution de menu, sans modifier le modèle amont.
- `PortfolioUpdateCopy` et `UpdatePreviewDialog` : socle réutilisable.

Les tests utilisent une capture du seul bloc public d'allocation ELM daté du
18 septembre 2026, pas un portefeuille utilisateur. Ils couvrent notamment les
arrondis, erreurs de source, identifiants, cibles, doublons, Pilotage, idempotence,
aperçu périmé et copies XML/ZIP/binaires/chiffrées.

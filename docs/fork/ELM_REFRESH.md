# Ajuster ELM

Branche `feature/elm-refresh`, basée sur le socle `feature/update-actions`.
Les opérations PE, les widgets et l'identité visuelle sont indépendants.

## Utilisation

1. Ouvrir un portefeuille et choisir **Outils du portefeuille → Ajuster ELM…**.
2. Sélectionner le titre correspondant à **ELM Market Navigator ETF**.
3. Cocher les taxonomies à actualiser et choisir, pour chacune, les catégories
   Monétaire, Obligations et Actions. Les correspondances sans ambiguïté sont
   proposées ; les sélections enregistrées sont retrouvées par identifiant.
   **Pilotage Global reste décoché à chaque ouverture**, même s'il était inclus
   dans la configuration enregistrée. Ses catégories restent mémorisées et il
   peut être coché manuellement pour cette actualisation. Cette règle s'applique
   aussi à l'option facultative ci-dessous lorsqu'elle vise Pilotage Global.
4. Facultatif : cocher **Ajuster aussi Pilotage**, choisir la taxonomie et sa
   catégorie **Moteur Dynamique**. ELM y sera affecté à 100 % et retiré des autres
   catégories de cette seule taxonomie, notamment du moteur statique.
5. Récupérer les données puis examiner les poids avant/après, la source et la date.
6. Cliquer sur **Appliquer au portefeuille ouvert**. Les affectations sont
   modifiées dans le portefeuille courant ; les taxonomies et tableaux de bord
   sont rafraîchis. Aucun nouvel onglet ni fichier n'est créé.
7. Enregistrer normalement (⌘S sur Mac). Si l'enregistrement automatique est
   activé, il continue à fonctionner selon les préférences habituelles.

Le fichier courant, son format et son chiffrement restent ceux de l'éditeur ouvert.
Une annulation, une erreur réseau ou une allocation incohérente laisse les
répartitions intactes. Si le portefeuille change pendant la préparation, relancer
l'action. Les modifications non enregistrées déjà présentes sont conservées.

La configuration et la date/source de la dernière actualisation sont mémorisées
dans le portefeuille courant. Si les affectations sont déjà à jour, un message
l'indique. Aucun cache ne retarde une demande manuelle.

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
- `updates/elm/ElmAdjustment` : plan de changements et application au portefeuille courant.
- `ui/updateactions/elm` : choix du périmètre et parcours utilisateur.
- `ui/model/elm-refresh.e4xmi` : contribution de menu, sans modifier le modèle amont.
- `UpdatePreviewDialog` : aperçu réutilisable ; la copie technique en mémoire sert
  seulement à préparer cet aperçu.

Les tests utilisent une capture du seul bloc public d'allocation ELM daté du
18 septembre 2026, pas un portefeuille utilisateur. Ils couvrent notamment les
arrondis, erreurs de source, identifiants, cibles, doublons, Pilotage, idempotence,
aperçu périmé et copies XML/ZIP/binaires/chiffrées.

# Géographies du Vanguard FTSE All-World Acc

Pour IE00BK5BQT80, la mise à jour Actions et `fetchCountries` utilisent le service public Vanguard (`/gpx/graphql`, fonds 9679), plutôt que le graphique Boursorama ou la table HTML Vanguard limitée aux 15 premiers pays.

Le parseur retient `FTCTYATPCS` et `fundMktPercent` : les pays selon FTSE et les poids du fonds. Les lignes MSCI et les agrégats régionaux présents dans la même réponse sont exclus. Les codes ISO sont traduits vers les libellés de la taxonomie française existante ; la région de classement Vanguard n'est pas utilisée pour déplacer les pays dans la taxonomie MSCI de l'utilisateur.

L'identité du fonds, l'identifiant 9679, l'unicité des pays, les dates et la somme des poids sont contrôlés. La somme doit être à moins de 0,001 point de 100 %, avec au moins 40 pays et les principaux marchés présents. Une donnée incomplète ou une erreur réseau conserve les anciennes affectations, sans repli silencieux sur une source tronquée. La date de publication et la page Vanguard sont conservées dans l'audit habituel.

L'arrondi au centième de point de PP utilise les plus grands restes : le total reste exactement 100 %, sans créer une poche Other artificielle. Le résidu explicitement fourni par Vanguard reste inclus avant cet arrondi.

Validation du 28 septembre 2026 : données publiées au 31 août 2026, 50 lignes FTSE (dont Other et Russie à zéro), somme 100,00000 %, Other 0,00002 %. Les 25 tests du module equity passent. La récupération Java en direct et le rapprochement avec Xapa Ventures ont été vérifiés en mémoire : 47 changements, puis zéro changement au second passage. Aucun fichier .portfolio n'a été enregistré.

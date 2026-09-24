# Configuration des cours et fabrication Mac

Le fournisseur de cours intégré nécessite la configuration publique de son client OAuth.
Le projet amont exclut `oauth/impl/config.json` de Git et contrôle son empreinte dans
`name.abuchen.portfolio/pom.xml`. Une compilation de développement peut réussir sans
ce fichier, mais la connexion au fournisseur échouera alors.

Avant de compiler une livraison, préparer cette ressource depuis une application
officielle Mac installée :

```sh
python3 private-equity-product/prepare-oauth.py \
  --from-app /Applications/PortfolioPerformance.app

# JDK 21 et Maven ; depuis la racine du dépôt
mvn -f portfolio-app/pom.xml -Pprivate-equity clean verify

python3 private-equity-product/package-macos.py /chemin/nouvelle-livraison \
  --branding fork-branding
```

L'option `--branding` ne s'utilise que si la branche du logo a été fusionnée.
Le script lit uniquement la ressource de configuration du programme officiel :
aucun portefeuille, compte Google, mot de passe ou jeton de session n'est lu.
L'empreinte SHA-256 doit correspondre exactement à celle attendue par notre base amont.
Une incompatibilité impose de revoir la version officielle source ou la mise à jour
amont, sans désactiver ce contrôle. Le fichier préparé reste ignoré par Git.

L'archive produite est autonome : elle contient la configuration et ne dépend plus
de l'installation officielle. Chaque utilisateur se connecte ensuite avec son propre
compte Portfolio Performance. La connexion et les droits d'accès aux cours restent
gérés par le fournisseur ; aucun accès n'est accordé par la simple configuration.

La fabrication de l'archive refuse un produit dont la configuration est absente ou
incompatible, avant de créer le dossier de livraison. Elle vérifie le contenu du
programme compilé, et non seulement la présence du fichier source.

Tests du contrôle de fabrication :

```sh
python3 -m unittest discover -s private-equity-product -p 'test_*.py' -v
```

Pour une future fabrication sur une autre machine ou dans GitHub Actions, prévoir
la même étape à partir d'une distribution officielle compatible, puis conserver
la vérification d'empreinte. Ne jamais fournir un fichier de session utilisateur.

### Écran de démarrage optionnel

Le manifeste du répertoire passé à `--branding` peut contenir `"splash": "splash.bmp"`.
Le fichier doit être un BMP RGB 24 bits. Le packaging le copie dans l'application
et configure `osgi.splashPath=platform:/base/branding`, sans modifier le bundle UI.
Ce chemin reste valable après déplacement de l'application. Sans cette entrée,
l'écran de démarrage du produit reste inchangé.

Référence : [configuration des produits Eclipse](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/guide/product_configproduct.htm).

### Contrôle PDF dans l'application assemblée

Le contrôle suivant lance le runtime OSGi du produit, sans interface ni portefeuille,
vérifie que le fournisseur Log4j est actif et lit un PDF de test. Il utilise une
configuration et un espace de travail temporaires ; l'application reste inchangée.

```sh
python3 private-equity-product/check-packaged-pdf.py \
  /chemin/PortfolioPerformancePE.app/Contents/Eclipse --java-home "$JAVA_HOME"
```

Ajouter `--live-amundi` pour vérifier aussi les trois compositions publiques de
l'ETF Amundi PEA Emerging (accès réseau nécessaire). Le contrôle local est limité
à 45 secondes et le contrôle avec sources à 150 secondes.

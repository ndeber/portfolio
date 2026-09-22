# Socle des traitements à la demande

Branche `feature/update-actions`, indépendante des opérations PE, des widgets et
du branding. Elle fournit l'aperçu avant/après et l'enregistrement d'une copie,
avec relecture et refus d'écraser un fichier existant. Les commandes spécialisées
prennent une copie du portefeuille en mémoire, travaillent sur cette copie puis
utilisent ces composants. Le fichier et le portefeuille ouverts restent intacts.

Formats : XML, XML compressé ZIP et binaire `.portfolio`. Une copie chiffrée
reste en `.portfolio` avec AES-256 et un mot de passe choisi lors de l'enregistrement.

`ClientInputFactory.openVerifiedCopy` ouvre le modèle relu sans lancer une
actualisation de cours, dividendes ou plans d'investissement. Une réouverture
ultérieure normale du fichier conserve le comportement standard de l'application.

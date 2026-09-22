#!/bin/sh
# Run interactively by the repository owner; never put a token in this script.
set -eu
GITHUB_CLI=${1:-gh}
printf '%s\n' 'Connexion à GitHub pour publier le fork ndeber/portfolio.'
printf '%s\n' 'Choisir le compte ndeber dans le navigateur et examiner les autorisations affichées.'
"$GITHUB_CLI" auth login --hostname github.com --git-protocol https --web
GITHUB_LOGIN=$("$GITHUB_CLI" api user --jq .login)
if [ "$GITHUB_LOGIN" != ndeber ]; then
    printf '%s\n' "Le compte actif est $GITHUB_LOGIN, pas ndeber. La configuration de Git est arrêtée."
    exit 1
fi
"$GITHUB_CLI" auth setup-git --hostname github.com
"$GITHUB_CLI" auth status
printf '%s\n' 'Connexion prête. Aucun code n’a été publié par ce script.'

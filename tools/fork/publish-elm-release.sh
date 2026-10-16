#!/bin/bash
# Publish only public build outputs after the verified GitHub-hosted rebuild.
set -euo pipefail

readonly tag=fork-elm-bonds-2026-10-16
readonly repository=ndeber/portfolio
readonly app_directory=${1:?Pass the verified application output directory}
readonly release_directory=${2:?Pass a new release staging directory}

if [[ ${GITHUB_REPOSITORY:-} != "$repository" ]]; then
    echo 'This release is restricted to ndeber/portfolio.' >&2
    exit 1
fi
if [[ $(git rev-parse HEAD) != "${RELEASE_COMMIT:?Missing release commit}" ]]; then
    echo 'The checkout does not match the workflow commit.' >&2
    exit 1
fi
if gh release view "$tag" --repo "$repository" >/dev/null 2>&1; then
    echo 'The release already exists; refusing to replace its assets.' >&2
    exit 1
fi

mkdir "$release_directory"
cp "$app_directory/PortfolioPerformancePE-mac-arm64.zip" \
    "$release_directory/PortfolioPerformance-ELM-2026-10-16-mac-arm64.zip"
git archive --format=zip --prefix=portfolio-elm/ HEAD \
    > "$release_directory/Sources-ELM-2026-10-16.zip"
cp "$app_directory/Validation/Rebuild.json" "$release_directory/Rebuild-GitHub.json"
cp "$app_directory/Validation/Compilation.log" "$release_directory/Compilation-GitHub.log"
cp "$app_directory/Validation/Packaging-tests.log" "$release_directory/Packaging-tests-GitHub.log"
cp "$app_directory/Validation/Packaged-runtime.log" "$release_directory/Packaged-runtime-GitHub.log"
(
    cd "$release_directory"
    shasum -a 256 *.zip *.json *.log > SHA256SUMS.txt
)

gh release create "$tag" --repo "$repository" --draft \
    --target "$RELEASE_COMMIT" \
    --title 'Portfolio Performance — taxonomies obligataires ELM — 16 octobre 2026' \
    --notes-file docs/fork/ELM_RELEASE_2026-10-16.md \
    "$release_directory"/*
gh release edit "$tag" --repo "$repository" --draft=false

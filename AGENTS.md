# Repository Guidelines

## Règles de travail et de livraison du fork PP

- Toujours repartir de la version publiée sur GitHub (`https://github.com/ndeber/portfolio`, branche `fork/integration`, sauf référence explicitement demandée). Récupérer l'état distant avant de travailler et vérifier la synchronisation de la copie locale ; un état local non publié ne constitue pas la référence.
- Avant de livrer une version compilée à l'utilisateur, publier sur GitHub toutes les sources modifiées ou ajoutées ainsi que tout le nécessaire pour la recompiler : scripts, actifs, configurations publiques, outils et dépendances. Conserver les éléments volumineux dans les fichiers joints à une release, avec leurs empreintes et une procédure de reconstruction.
- Vérifier que les sources et les éléments de reconstruction sont effectivement disponibles sur GitHub et valider la reconstruction depuis un clone ou une archive récupérés sur GitHub. La version doit rester recompilable après suppression complète du local ; ne pas la présenter comme livrée tant qu'un élément indispensable existe uniquement en local.
- Ces publications concernent le code et les éléments de compilation ; elles excluent les portefeuilles personnels, les identifiants, les jetons de connexion et les secrets.

## Project Structure & Module Organization

This repository is an Eclipse RCP application built as modular OSGi bundles. Use `portfolio-app/` as the Maven/Tycho entry point. Core business logic lives in `name.abuchen.portfolio/src/`, UI code in `name.abuchen.portfolio.ui/src/`, shared test helpers in `name.abuchen.portfolio.junit/`, core tests in `name.abuchen.portfolio.tests/src/`, and UI tests in `name.abuchen.portfolio.ui.tests/`. Packaging and installers are under `portfolio-product/`; target platform definitions live in `portfolio-target-definition/`. Docs and contributor references live in `docs/` and `CONTRIBUTING.md`.

## Build, Test, and Development Commands

Set `JAVA_HOME` to a Java 21 JDK before building.

- `mvn -f portfolio-app/pom.xml clean verify`: full CI-style Maven/Tycho build.
- `mvn -f portfolio-app/pom.xml clean verify -Plocal-dev`: faster local build; skips coverage, Checkstyle, and translation-related checks.
- `mvn -f portfolio-app/pom.xml verify -Plocal-dev -o -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,:name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio,:name.abuchen.portfolio.junit,:name.abuchen.portfolio.tests -am -amd`: run core tests offline.
- `mvn -f portfolio-app/pom.xml verify -Plocal-dev -o -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,:name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio,:name.abuchen.portfolio.junit,:name.abuchen.portfolio.tests -am -amd -Dtest=<fully.qualified.CoreTestClass>`: run one core test class, for example `... -Dtest=name.abuchen.portfolio.datatransfer.pdf.baaderbank.BaaderBankPDFExtractorTest`.
- `mvn -f portfolio-app/pom.xml verify -Plocal-dev -o -pl :portfolio-target-definition,:name.abuchen.portfolio.pdfbox1,:name.abuchen.portfolio.pdfbox3,:name.abuchen.portfolio,:name.abuchen.portfolio.bootstrap,:name.abuchen.portfolio.ui,:name.abuchen.portfolio.junit,:name.abuchen.portfolio.ui.tests -am -amd -Dtest=<fully.qualified.UITestClass>`: run one UI test class. Include `:name.abuchen.portfolio.bootstrap` because `name.abuchen.portfolio.ui` requires that OSGi bundle and Tycho does not infer it from `-Dtest`.

Eclipse is the preferred IDE for launching the RCP app and JUnit Plug-in Tests.

## Coding Style & Naming Conventions

Follow the existing Eclipse formatter and organize imports automatically. Use Java 21 features conservatively; prefer `var` for obvious local types. Keep package names lowercase and mirror the existing bundle structure. Name tests `*Test.java`; PDF importer pairs should follow `BankNamePDFExtractor.java` and `BankNamePDFExtractorTest.java`. Do not mass-reformat unrelated files. For PDF extractor sources, preserve manual alignment and use `@formatter:off` only where necessary.

## Testing Guidelines

Add or update tests for any business logic change, especially in `name.abuchen.portfolio`. Core coverage belongs in `name.abuchen.portfolio.tests`; UI behavior belongs in `name.abuchen.portfolio.ui.tests`. Prefer focused regression tests over broad fixture churn. If changing importers, include realistic sanitized samples and matching extractor tests.

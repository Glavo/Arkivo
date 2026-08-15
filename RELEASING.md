# Releasing Arkivo

Arkivo releases are built from a clean Git revision and uploaded as a signed Maven repository bundle to the Maven
Central Portal. The release version is supplied through the `releaseVersion` Gradle property; the repository may
therefore remain on its normal `1.0-SNAPSHOT` development default.

## Prerequisites

- Use a supported JDK. JDK 25 is recommended for the same Javadoc and publication checks used by CI.
- Start from a clean worktree whose revision has passed the GitHub build, Tier 2, and Tier 3 workflows.
- Configure an OpenPGP key accepted for the `org.glavo` namespace. Supply its armored private key through the
  `signingKey` Gradle property and its passphrase through `signingPassword`. `signingKeyId` is optional.
- Keep signing material outside the repository. Environment-backed Gradle properties such as
  `ORG_GRADLE_PROJECT_signingKey` and `ORG_GRADLE_PROJECT_signingPassword` avoid placing secrets in command history or
  project files.

## Verify the release candidate

Choose the immutable public version and run every verification tier with that exact value:

```text
./gradlew -g .gradle-user-home --no-daemon clean checkTier3 fuzzRegressionTest "-PreleaseVersion=1.0"
```

`checkTier3` includes normal checks, the complete Tier 2 upstream corpora, and Tier 3 scalability and low-heap probes.
The deterministic fuzz regression task replays the source-generated seeds without starting an open-ended fuzzing
session. Longer local Jazzer runs may be performed before a release when the affected parsing surface warrants them.

The release build must complete without changing tracked files. Review the generated local Maven staging repository
if publication metadata changed.

## Create the Central Portal bundle

With the signing properties available, run:

```text
./gradlew -g .gradle-user-home --no-daemon centralPortalBundle "-PreleaseVersion=1.0"
```

The task rejects snapshot versions and missing signing keys. It generates every module's binary, source, Javadoc, POM,
and Gradle module metadata artifacts; signs them; generates checksums; verifies their dependency scopes and JPMS
descriptors; and creates this reproducible repository bundle:

```text
build/distributions/arkivo-1.0-central-portal.zip
```

Record the bundle's SHA-256 digest with the release notes. Do not commit the bundle or signing material.

## Publish and tag

1. Upload the bundle to the Maven Central Portal and wait for portal validation to succeed.
2. Inspect the deployment contents and publish it. A published Maven version cannot be replaced.
3. Wait until all `org.glavo:arkivo-*` artifacts and their source and Javadoc archives are visible from Maven Central.
4. Create the `v1.0` Git tag from the exact verified revision and publish the corresponding project release notes.
5. Advance the development version only when work on the next release begins.

If portal validation fails before publication, discard the deployment, correct the release revision, and rebuild the
entire bundle. Never reuse artifacts from different revisions under one version.

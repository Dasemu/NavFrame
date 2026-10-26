# Android builds and automatic releases

The Android workflow verifies the debug build, unit tests and lint for pull requests and pushes to `main` or `development`. A manual Actions run is also available. Pull requests never receive production signing secrets or publish releases.

## Pull request routing and version policy

Commit the version change in the PR itself. `PR branch and version policy` checks the proposed `app/build.gradle.kts` against the destination branch snapshot:

| PR source | Destination | Required versionName |
| --- | --- | --- |
| `feat/*` | `development` | `x.y.(z+1)` |
| `fix/*` | `development` | `x.y.(z+1)` |
| `hotfix/*` | `main` | `x.y.(z+1)` |
| `development` | `main` | `x.(y+1).z` |

Features and ordinary fixes must go through `development`; urgent production fixes use `hotfix/*` into `main`. Other source/destination combinations fail. Components not being incremented are preserved: promotion from `0.13.8` requires `0.14.8`, not `0.14.0`. Every PR also increases the committed Android `versionCode`; the production build override remains independent.

The major component only changes on the owner's explicit request. For that exceptional PR, the repository owner must personally apply the `major-version-approved` label; the workflow verifies the most recent label event actor. The expected version then becomes `(x+1).y.z`, while branch routing still applies. Remove the label when the exception is no longer authorized.

The policy uses `pull_request_target` with read-only permissions and executes only the base branch's trusted script. It reads PR version declarations with `git show` without building or executing PR code, and never uses production secrets. The existing Android checks continue using the ordinary `pull_request` event. Enable **Branch and version policy** as a required status check in branch protection/rulesets for both destinations to prevent merging a failed policy check. Install this policy on both branches before relying on it for development PRs. If another PR changes the destination version, update the branch/version and rerun the check before merging.

Do not compute the bump by hand. From the PR's source branch, fetch the destination and let the policy script write the required versionName, versionCode and README version, then commit the result:

```sh
git fetch origin main development
python3 scripts/check_version_policy.py --write --base-ref origin/DESTINATION --base-branch DESTINATION --head-branch SOURCE
```

For a promotion, that is `--base-ref origin/main --base-branch main --head-branch development`, committed on `development` before the promotion PR is merged. A failed check prints the same command with the values filled in. Rerun it whenever the destination moves (for example after another hotfix lands on `main`).

CI validates the committed bump rather than pushing version commits with privileged tokens. This keeps the exact app version reviewable in the PR and works for fork contributions as well.

## Branch outputs

| Event | Output after checks pass |
| --- | --- |
| Push to `development` | `app-debug.apk` uploaded as `navframe-debug-COMMIT_SHA`, retained for 14 days. |
| Push to `main` | Signed production/release APK, `SHA256SUMS.txt` and `build-info.json` published in a new GitHub Release. |
| Pull request | Build/test/lint reports only; no release publication. |
| Manual run | Same branch behavior as a push when run on `main` or `development`. |

Each push is a separate run, including rapid successive pushes; older builds are not cancelled or discarded. A push containing several commits builds its final commit. Release publication requires successful checks, release compilation/lint, and APK signature verification. Tags include versionName, workflow run number and commit SHA, for example `app-v0.13.2-build-42-0123456789ab`. APK versionCode is `1000000 + github.run_number`, so successive production builds can update each other without changing the human-readable versionName for every commit. Preserve the workflow's run-number history if reorganizing the workflow.

An older build finishing late does not replace the current main build as `latest`. Rerunning a run can finish its own draft release, but preserves an already published release and its assets. Offline-region release/catalog workflows are separate.

## Production signing secrets

Create these repository Actions secrets under **Settings → Secrets and variables → Actions**:

| Secret | Contents |
| --- | --- |
| `ANDROID_RELEASE_KEYSTORE_BASE64` | Base64-encoded production `.p12`/keystore file. |
| `ANDROID_RELEASE_KEYSTORE_PASSWORD` | Keystore password. |
| `ANDROID_RELEASE_KEY_ALIAS` | Alias of the production signing key. |
| `ANDROID_RELEASE_KEY_PASSWORD` | Key password; for a PKCS12 key it normally matches the keystore password. |

Use a permanent production key and keep a secure offline backup of the keystore and passwords. The newly prepared NavFrame key uses RSA-4096, PKCS12, alias `navframe-release`, and a 10,000-day certificate validity. Its private backup is supplied separately from the repository; neither keys nor password values belong in Git, Actions artifacts, release assets or this document. A replacement key normally cannot update an app signed by the old key. A production key also cannot update an earlier APK signed with a debug key; preserve/export app data before any uninstall required by a signature mismatch.

The workflow restores the keystore to a private temporary runner file, checks that all four secrets exist, builds with environment-only signing parameters, verifies the APK signature, and removes the temporary key even if the job fails. The verification job remains read-only; only the guarded main release job has `contents: write`. The normal repository `GITHUB_TOKEN` publishes releases, with no separate personal token required. Repository/org policies must permit the release job's requested permission.

Development artifacts use Android's default debug signing. Fresh GitHub runners can generate different debug keys, so debug artifacts are not a stable production update channel. They do not contain the production private key.

## Local production validation

Provide these values securely in the process environment (never commit a shell script containing their values):

- `NAVFRAME_KEYSTORE_PATH`
- `NAVFRAME_KEYSTORE_PASSWORD`
- `NAVFRAME_KEY_ALIAS`
- `NAVFRAME_KEY_PASSWORD`

From the repository root, using JDK 17 and Android SDK 36:

```sh
./gradlew --no-daemon --max-workers=4 :app:assembleRelease :app:lintRelease -PnavframeVersionCode=1000042
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose app/build/outputs/apk/release/app-release.apk
```

The version-code override is optional for local builds; ordinary debug builds retain the manifest's normal version code and need no signing variables. An unsigned production build is rejected instead of producing a publishable release. Partially specified signing configuration is rejected with variable names only.

Publication is active only after this workflow lands on the selected branch and the production secrets are configured. A successful local production build does not establish a GitHub Release was published.

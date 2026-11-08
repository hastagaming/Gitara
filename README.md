<div align="center">

# Gitara

**A Git client for Android with a hosting-style UI, a private Linux box, and GitHub-Actions-like workflows. All on your phone.**



![License](https://img.shields.io/badge/license-GPL--3.0-blue)




![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)




![Target SDK](https://img.shields.io/badge/target%20SDK-36-brightgreen)




![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white)




![ABI](https://img.shields.io/badge/ABI-arm64--v8a-orange)



</div>

---

Gitara is a fork of [MGit](https://github.com/maks/MGit) rebuilt for current Android. It keeps the solid JGit-based Git engine and adds a modern interface, a self-contained Linux environment, and a workflow runner, so you can clone, edit, build and ship without leaving your phone.

## Highlights

- **Full Git on your phone**: clone, fetch, pull, push, commit, branches, tags, merge, rebase, cherry-pick, reset, diff and SSH keys, powered by JGit.
- **Works with every Git host**: GitHub, GitLab, Bitbucket, Gitea, Forgejo, Codeberg, SourceHut and any plain HTTPS or SSH remote. Gitara recognises the host from the URL and themes the screen with its colour.
- **Hosting-style interface**: repository list and repository page rebuilt in Jetpack Compose and Material 3, with branch chip, tabs, run history and live logs. The remaining screens are being moved over step by step.
- **Gitara Box**: a private Alpine Linux environment (proot) inside the app. Install tools with `apk add`, no root and no external Termux.
- **Workflows**: GitHub-Actions-like automation in YAML or TOML, with jobs, `needs`, `if`, expressions, step outputs, your own composite actions, and encrypted secrets.
- **Your repositories stay in the app's own storage**, like Termux. No fixed size is reserved. It simply uses the free space of the device.
- **Built for Android 15 and 16**: target SDK 36, foreground service for long builds, runtime permission handling.

## Quick start

1. Install the APK and open Gitara.
2. Tap **Clone**, paste any Git URL, and choose a folder name. Add a username and token for private repositories.
3. Open the repository and go to the **Actions** tab.
4. Tap **Install Gitara Box** (about 4 MB, once).
5. Tap **New**, pick **Hello Gitara**, then **Run**.

## Workflows

Workflows live in your repository under `.gitara/workflows/` as `.yml`, `.yaml` or `.toml` files, so they travel with the code to any host.

```yaml
name: Build

on:
  manual:
    inputs:
      variant:
        description: Build variant
        default: debug

env:
  GRADLE_OPTS: -Xmx2g

jobs:
  build:
    name: Build
    runs-on: gitara
    timeout-minutes: 90
    steps:
      - name: Tools
        run: apk add --no-cache bash git openjdk17
      - id: version
        run: echo "name=1.0.${{ inputs.variant }}" >> "$GITARA_OUTPUT"
      - uses: ./.gitara/actions/notify
        with:
          message: Built ${{ steps.version.outputs.name }}
        env:
          TOKEN: ${{ secrets.NOTIFY_TOKEN }}

  report:
    needs: build
    if: ${{ always() }}
    runs-on: gitara
    steps:
      - run: echo "build was ${{ needs.build.result }}"
```

### What is supported

| Area | Keys and features |
| --- | --- |
| Workflow | `name`, `on.manual.inputs` (alias `workflow_dispatch`), `env`, `jobs` |
| Job | `name`, `needs`, `if`, `env`, `timeout-minutes`, `outputs`, `steps` |
| Step | `id`, `name`, `if`, `run`, `uses`, `with`, `shell`, `working-directory`, `env`, `continue-on-error`, `timeout-minutes` |
| Shells | `sh`, `ash`, `bash`, `python3`, `node`, or a custom `cmd {0}` |
| Contexts | `env`, `secrets`, `inputs`, `steps`, `needs`, `job`, `runner`, `gitara` (alias `github`) |
| Functions | `success()`, `failure()`, `always()`, `cancelled()`, `contains`, `startsWith`, `endsWith`, `format`, `join`, `toJSON` |
| Operators | `==` `!=` `<` `<=` `>` `>=` `&&` `\|\|` `!` and parentheses |
| Files | `$GITARA_OUTPUT`, `$GITARA_ENV`, `$GITARA_PATH` (`GITHUB_*` aliases work too) |
| Log commands | `::add-mask::`, `::error::`, `::warning::`, `::notice::` |

Write `if: ${{ !cancelled() }}` or quote the value, because YAML does not accept a bare `!` at the start of a value.

### Your own actions

Create a composite action in `.gitara/actions/<name>/action.yml` (or `action.toml`) and call it with `uses: ./.gitara/actions/<name>`. Actions have `inputs`, `outputs` and `runs.using: composite`. The **New** button can generate a working example for you.

### Secrets

Open **Secrets** in the Actions tab. Secrets can be scoped to one repository or to all repositories, and are used as `${{ secrets.NAME }}`.

- Encrypted with AES-GCM using a key held in the Android Keystore.
- Never shown again after saving.
- Masked as `***` in logs (values of 4 or more characters).
- Generated step scripts are deleted when a run ends.

## Gitara Box

The Box is an Alpine Linux root filesystem that runs through [proot](https://proot-me.github.io/), launched from the app's native library directory, which is the only way to run Linux binaries from an app targeting a recent SDK.

- Requires an **arm64-v8a** device.
- The first install downloads the latest Alpine mini root filesystem and verifies its SHA-256 checksum.
- Your repository is mounted at `/workspace`, and `/root` persists between runs, so caches such as `~/.gradle` are kept.
- Install anything Alpine packages with `apk add` inside a workflow step.
- Android SDK builds need the SDK and an arm64 `aapt2` installed in the Box.
- proot is not a security sandbox and runs slower than native code. Heavy builds are best on a charger.

## Storage layout

```
<app files>/
├── home/repos/        your repositories (shown as /workspace in the Box)
├── box/               Alpine root filesystem and run data
├── secrets/           encrypted secrets
└── workflow-runs/     run history and logs per repository
```

## Building from source

Requirements: JDK 17, Android SDK platform 36, and internet access on the first build.

```bash
git clone https://github.com/hastagaming/Gitara.git
cd Gitara
./gradlew :app:assembleDebug
```

The build downloads the pinned proot package (fixed commit, verified by SHA-256) and packages it as native libraries. On Termux, point Gradle at Termux's own `aapt2` in `~/.gradle/gradle.properties`:

```properties
android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
```

Release signing reads `alias`, `password` and `keystore` from Gradle properties when `-Pspecial` is set.

## Project layout

| Path | What lives there |
| --- | --- |
| `me/sheimi/sgit` | Git engine, database, legacy screens inherited from MGit |
| `io/gitara/hosting` | Git host detection from URLs |
| `io/gitara/ui` | Compose theme, repository list and repository detail |
| `io/gitara/box` | Gitara Box installer, tar extractor and proot runner |
| `io/gitara/workflow` | YAML/TOML parsing, expressions, engine and foreground service |
| `io/gitara/secrets` | Keystore-backed secret store |

## Roadmap

- Interactive terminal inside the Box
- Remote actions with `uses: owner/repo@ref`
- `strategy.matrix`
- One-tap toolchain presets (JDK, Android SDK, Node, Rust)
- Account sign-in per host with issues and pull requests
- Files, commits and diff screens rebuilt in Compose

## Credits

Gitara stands on the work of others:

- [MGit](https://github.com/maks/MGit) by Maksim Lin, itself based on SGit by Sheimi
- [proot](https://proot-me.github.io/) and the Android build by [green-green-avk](https://github.com/green-green-avk/build-proot-android)
- [Alpine Linux](https://alpinelinux.org/)
- [Eclipse JGit](https://www.eclipse.org/jgit/), [JSch (mwiede fork)](https://github.com/mwiede/jsch), [SnakeYAML Engine](https://bitbucket.org/snakeyaml/snakeyaml-engine), [Timber](https://github.com/JakeWharton/timber)

## License

Gitara is free software released under the **GNU General Public License v3.0**. See the `LICENSE` file.
# 🐝 QuentinN42's Beeper ReVanced Patches

[![Release workflow](https://img.shields.io/github/actions/workflow/status/QuentinN42/beeper-revanced-patches/release.yml?label=release)](https://github.com/QuentinN42/beeper-revanced-patches/actions/workflows/release.yml)
[![Latest release](https://img.shields.io/github/v/release/QuentinN42/beeper-revanced-patches?sort=semver)](https://github.com/QuentinN42/beeper-revanced-patches/releases)
[![GPLv3 License](https://img.shields.io/badge/License-GPL%20v3-yellow.svg)](LICENSE)

Custom patches for [Beeper Universal Messenger](https://www.beeper.com) with [ReVanced](https://revanced.app).

All patches target package `com.beeper.android` and are meant to be consumed together with the
official [ReVanced Patches](https://github.com/revanced/revanced-patches) bundle, inside
[ReVanced Manager](https://github.com/revanced/revanced-manager).

## 🧩 Patches

| Patch | Description |
|---|---|
| **Beeper alert once** | Only alert (sound and vibration) once per conversation: repeated messages update the existing notification silently (`FLAG_ONLY_ALERT_ONCE` + stable per-conversation notification id) until it is dismissed. |

## 📱 Usage

1. Install [ReVanced Manager](https://github.com/ReVanced/revanced-manager/releases) (latest version).
2. *Settings → Patch sources* (or *Alternative sources*) and add `QuentinN42/beeper-revanced-patches`.
3. Select the Beeper APK you want to patch and enable the `Beeper alert once` patch.
4. Patch.

## 🏗️ Development

```sh
git clone https://github.com/QuentinN42/beeper-revanced-patches.git
cd beeper-revanced-patches
# JDK 17+ is required
./gradlew build
```

- Development starts on feature branches, then is squash-merged into `dev`.
- `dev` is merged into `main` for releases.
- Pushing to `main` (or `dev`) triggers the [`release.yml`](.github/workflows/release.yml)
  workflow: it builds the patches bundle, signs it with GPG and publishes a GitHub release
  with the `patches*.rvp` / `patches.json` assets.

## 📜 Licence

Published under the [GPLv3 licence](LICENSE).

<!--
SPDX-FileCopyrightText: 2026 Chris Palmer <chris@red-oxide.org>
SPDX-License-Identifier: GPL-3.0-only
-->

# pressbox-android

The Android app for [pressbox](https://github.com/chrisppy/pressbox), a
self-hosted feed reader whose feeds and users are declared in NixOS. It reads
from your pressbox server over its HTTP API (`/api/v1`) and shares read and
starred state with the other clients, such as the `pressbox` terminal reader and
[pressbox-ios](https://github.com/chrisppy/pressbox-ios).

> [!NOTE]
> pressbox-android is in its early days. Expect rough edges, and features and
> behavior that may change between versions.

## Requirements

- Android 12 (API 31) or later.
- A pressbox server the phone can reach. https is best; `tailscale serve --bg
  8470` on the server is the least work, and a reverse proxy with ACME works
  too. Plain http works, and the app warns when an http address isn't on the
  local network or a tailnet.
- To build: JDK 17 and the Android SDK (platform 36), or `nix develop`, which
  provides both. Gradle comes from the wrapper.

## Building

The repo is a Gradle project (Kotlin and Jetpack Compose) with one module,
`app/`.

```sh
nix develop    # JDK 17, the Android SDK, adb; or use Android Studio
just build     # ./gradlew assembleDebug → app/build/outputs/apk/debug
just install   # ./gradlew installDebug, onto a connected device
```

Android Studio opens the directory as is. CI builds the debug APK and runs lint
on every change (`.github/workflows/android.yml`) and keeps the APK as a
workflow artifact.

`just release` builds a minified release APK, unsigned. Sign it with
`apksigner`, or add a `signingConfig` to `app/build.gradle.kts` reading a
keystore that stays out of the repo (`*.jks` and `*.keystore` are ignored).

## Using it

On first launch, enter the server address and tap "Pair this device". The app
shows a code like `K7W-4QX`: approve it on the server with
`sudo pressboxctl approve K7W-4QX`, or from a device that is already paired
(`pressbox approve K7W-4QX`, or Settings in either app). See pressbox's docs on
[pairing](https://github.com/chrisppy/pressbox/blob/master/docs/pairing.md) and
[users](https://github.com/chrisppy/pressbox/blob/master/docs/server.md#users).
"Use a token instead" takes a token from `pressboxctl add-device`.

The feed list has All items, Starred, a Tags section and the feeds, each with
its unread count; feeds removed from the server's config are listed apart while
they still have starred items. In an item list, swipe right to toggle read and
left to toggle the star, or open an article to read it. Every screen keeps its
controls in a bottom bar, within thumb reach: Settings and Refresh under the
feeds; Mark all as read, the Unread/All filter and search under the items; star,
read, share and open in the browser under an article. On a phone, Back (and
Home, from an article) lead the bar, and the system back gesture works too. On a
tablet or a wide window the lists and the article sit side by side.

Settings also has:

- **Approve a device**, for pairing other clients from your phone. Admins can
  approve a device for another user.
- **Require unlock**, which asks for your fingerprint, face or screen lock
  whenever the app comes back from the background, and keeps its content out of
  the recent apps screen.
- **Mark as read when scrolled past**, off by default: items are marked read as
  they scroll off the top of the list while you scroll (not in search results).
- **Unread count on app icon**: a silent notification with the count, which
  launchers show as a badge on the icon. A background job refreshes it about
  every half hour; Android decides exactly when. It needs the notification
  permission, which the app asks for when you turn it on.

The Server section shows the address, the signed-in user and device, and the
server's version.

Opened articles, and the content of up to 30 unread items per refresh, are saved
for offline reading. Articles are shown with JavaScript off, and links open in
your browser. The token is encrypted with a key in the Android Keystore that
can't leave the device, and the app's data is excluded from backups and device
transfers.

## Development

```sh
just           # list the recipes
just fmt       # ktfmt and the other formatters (devfmt's treefmt setup)
just lint      # Android lint
just verify    # check formatting, then nix flake check (formatting, reuse lint)
```

| File | What it does |
| --- | --- |
| `Models.kt` | JSON shapes of the API |
| `ApiClient.kt` | HTTP client (OkHttp, kotlinx.serialization) |
| `Store.kt` | App state shared by every screen |
| `Cache.kt` | The offline snapshot on disk |
| `TokenStore.kt` | The token, encrypted with a Keystore key |
| `Unread.kt` | The unread count notification and its background worker |
| `MainActivity.kt` | Layout (stacked or side by side) and the lock |
| `*Screen.kt` | Feeds, items, article and settings |
| `BottomControls.kt` | The bottom bar and status banners |
| `Theme.kt` | Birdland colors |

Changes are recorded in [CHANGELOG.md](CHANGELOG.md). A release bumps
`versionName` (and `versionCode`) in `app/build.gradle.kts` and is tagged
`vX.Y.Z`.

## License

pressbox-android is [REUSE](https://reuse.software) compliant; every file states
its license in an SPDX header or in `REUSE.toml` (`reuse lint` checks this, also
in `nix flake check`).

- The app, build files and docs are licensed under the
  [GNU General Public License v3.0 only](LICENSES/GPL-3.0-only.txt), like
  pressbox itself.
- The app icon (`assets/app-icon.svg` and the adaptive icon layers drawn from
  it) is licensed under
  [Creative Commons Attribution-ShareAlike 4.0](LICENSES/CC-BY-SA-4.0.txt).
- The Gradle wrapper is Gradle's, under the
  [Apache License 2.0](LICENSES/Apache-2.0.txt).

### Name and logo

The pressbox name and logo identify the pressbox project. The licenses above
cover copyright, not trademarks: you're welcome to use the logo to refer to
pressbox, but forks and derived apps must use their own name and icon.

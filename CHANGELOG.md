<!--
SPDX-FileCopyrightText: 2026 Chris Palmer <chris@red-oxide.org>
SPDX-License-Identifier: GPL-3.0-only
-->

# Changelog

All notable changes to pressbox-android. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions are
[calendar versions](https://calver.org/), `YYYY.M.PATCH`: the year and month of
the release, then a count that starts at 0 each month (2026.11.0, 2026.11.1,
2026.12.0). The app is versioned on its own; it talks to pressbox servers
through API v1 (`/api/v1`).

## [2026.11.0] - Unreleased

- Podcasts (needs a pressbox server with podcast support): episodes play in a
  Media3 media session service (background playback, notification, lock screen,
  headphone and Bluetooth controls), resume where any client left off, and are
  marked read when played to the end. A bar along the bottom opens the full
  player with video, scrubbing, 10/30-second skips, chapters, speeds that keep
  the pitch and a transcript that follows along. The service saves the position
  every 15 seconds and on pause. Opening an episode no longer marks it read.

The first version, matching pressbox-darwin (then pressbox-ios).

- Feeds, tags, item lists and an article reader for a pressbox server (Kotlin
  and Jetpack Compose, Android 12+), with search, cursor paging and offline
  reading.
- Swipe right to toggle read and left to toggle the star; mark all as read; an
  Unread/All filter.
- Controls in a bottom bar within thumb reach, with Back and Home when screens
  are stacked; lists and the article side by side on wide screens.
- Pairing with a short code or a token, and approving other devices (for any
  user, as an admin).
- An unlock requirement (fingerprint, face or screen lock), an unread count
  badge through a silent notification refreshed in the background, and mark as
  read on scroll (off by default).
- Licensed under GPL-3.0-only, like pressbox (the icon stays CC-BY-SA-4.0).
- Birdland colors: Birdland in dark mode, Birdland Light in light mode, and the
  pressbox icon as an adaptive icon with a themed (monochrome) layer.

[2026.11.0]: https://github.com/chrisppy/pressbox-android/releases/tag/v2026.11.0

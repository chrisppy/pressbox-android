<!--
SPDX-FileCopyrightText: 2026 Chris Palmer <chris@red-oxide.org>
SPDX-License-Identifier: MPL-2.0
-->

# Changelog

All notable changes to pressbox-android. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/). The app is versioned on its own; it
talks to pressbox servers through API v1 (`/api/v1`).

## [Unreleased]

The first version, matching pressbox-ios.

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
- Birdland colors: Birdland in dark mode, Birdland Light in light mode, and the
  pressbox icon as an adaptive icon with a themed (monochrome) layer.

// SPDX-FileCopyrightText: 2026 Chris Palmer <chris@red-oxide.org>
// SPDX-License-Identifier: MPL-2.0

package io.github.chrisppy.pressbox

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb

// Birdland colors, from https://github.com/chrisppy/base24-themes: Birdland in
// dark mode, Birdland Light in light mode, as in the iOS app. The comments give
// each color's Base24 slot. Dynamic (wallpaper) color is off on purpose.

@Immutable
data class Palette(
  /** base00: screens, lists and the article. */
  val background: Color,
  /** base05: primary text. */
  val text: Color,
  /** base04: secondary text. */
  val mutedText: Color,
  /** base08, Orioles orange: the app's accent. */
  val accent: Color,
  /** base12: errors. */
  val error: Color,
  /** base0A: stars. */
  val star: Color,
  /** base0B: the read/unread swipe action. */
  val read: Color,
  /** base0D: links in articles. */
  val link: Color,
)

private val Light =
  Palette(
    background = Color(0xFFF4EFE4),
    text = Color(0xFF26231F),
    mutedText = Color(0xFF6B665E),
    accent = Color(0xFFB83C00),
    error = Color(0xFFA83300),
    star = Color(0xFF7A611D),
    read = Color(0xFF3B7048),
    link = Color(0xFF7F5530),
  )

private val Dark =
  Palette(
    background = Color(0xFF0B0B0B),
    text = Color(0xFFE5DFD2),
    mutedText = Color(0xFF8A8A8A),
    accent = Color(0xFFDF4601),
    error = Color(0xFFFF5A00),
    star = Color(0xFFD4AD4C),
    read = Color(0xFF4F8F6A),
    link = Color(0xFFD98B45),
  )

val LocalPalette = staticCompositionLocalOf { Dark }

object Theme {
  val palette: Palette
    @Composable get() = LocalPalette.current

  /**
   * CSS custom properties for the article page: `--text`, `--muted` and `--link`. The page
   * background stays transparent so the screen's background shows through.
   */
  fun articleCSS(palette: Palette): String =
    ":root { --text: ${palette.text.hex()}; --muted: ${palette.mutedText.hex()}; " +
      "--link: ${palette.link.hex()}; }"

  private fun Color.hex(): String = String.format("#%06x", toArgb() and 0xFFFFFF)
}

private fun scheme(p: Palette, dark: Boolean): ColorScheme {
  /** Surfaces a step towards the text color, for cards, bars and sheets. */
  fun tone(amount: Float) = lerp(p.background, p.text, amount)
  val base = if (dark) darkColorScheme() else lightColorScheme()
  return base.copy(
    primary = p.accent,
    onPrimary = Color.White,
    primaryContainer = lerp(p.background, p.accent, 0.25f),
    onPrimaryContainer = p.text,
    secondary = p.link,
    onSecondary = Color.White,
    secondaryContainer = lerp(p.background, p.link, 0.25f),
    onSecondaryContainer = p.text,
    tertiary = p.star,
    background = p.background,
    onBackground = p.text,
    surface = p.background,
    onSurface = p.text,
    surfaceVariant = tone(0.10f),
    onSurfaceVariant = p.mutedText,
    surfaceContainerLowest = p.background,
    surfaceContainerLow = tone(0.03f),
    surfaceContainer = tone(0.05f),
    surfaceContainerHigh = tone(0.08f),
    surfaceContainerHighest = tone(0.11f),
    surfaceBright = tone(0.12f),
    surfaceDim = p.background,
    outline = p.mutedText,
    outlineVariant = tone(0.18f),
    error = p.error,
    onError = Color.White,
    errorContainer = lerp(p.background, p.error, 0.25f),
    onErrorContainer = p.text,
  )
}

@Composable
fun PressboxTheme(content: @Composable () -> Unit) {
  val dark = isSystemInDarkTheme()
  val palette = if (dark) Dark else Light
  CompositionLocalProvider(LocalPalette provides palette) {
    MaterialTheme(colorScheme = scheme(palette, dark), content = content)
  }
}

// SPDX-FileCopyrightText: 2026 Chris Palmer <chris@red-oxide.org>
// SPDX-License-Identifier: GPL-3.0-only

package org.red_oxide.pressbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

// Every screen's controls live in a bottom bar, within thumb reach, including
// Back and Home where a top bar would have them, as in the iOS app. The system
// back gesture works too.

/**
 * Leaving a screen from its bottom bar. Only set where the panes are stacked (a phone, or a narrow
 * window); side by side, there is nothing to go back to.
 */
class Navigation(
    /** Up one screen. */
    val back: () -> Unit,
    /** Straight to the feed list, when that is more than one screen up. */
    val home: (() -> Unit)? = null,
)

val LocalNavigation = compositionLocalOf<Navigation?> { null }

/**
 * The screen's controls, together in one group centred in the bottom bar: Back (and Home) first.
 */
@Composable
fun BottomControls(buttons: @Composable RowScope.() -> Unit) {
  val navigation = LocalNavigation.current
  BottomAppBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
      if (navigation != null) {
        Action("Back", Icons.AutoMirrored.Filled.ArrowBack, onClick = navigation.back)
        navigation.home?.let { Action("Feeds", Icons.Outlined.Home, onClick = it) }
        Spacer(Modifier.width(8.dp))
      }
      buttons()
    }
  }
}

/** An icon button labelled for accessibility, with its label as a long-press tooltip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Action(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    tint: Color? = null,
    onClick: () -> Unit,
) {
  TooltipBox(
      positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
      tooltip = { PlainTooltip { Text(label) } },
      state = rememberTooltipState(),
  ) {
    IconButton(onClick = onClick, enabled = enabled) {
      if (tint != null && enabled) Icon(icon, contentDescription = label, tint = tint)
      else Icon(icon, contentDescription = label)
    }
  }
}

/** A status message floating over the bottom of a list, tinted for errors. */
@Composable
fun StatusBanner(text: String, error: Boolean, modifier: Modifier = Modifier) {
  val palette = Theme.palette
  Surface(
      modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      shape = RoundedCornerShape(50),
      color = if (error) palette.error else MaterialTheme.colorScheme.surfaceContainerHighest,
      contentColor = if (error) Color.White else palette.text,
      tonalElevation = 3.dp,
      shadowElevation = 3.dp,
  ) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
  }
}

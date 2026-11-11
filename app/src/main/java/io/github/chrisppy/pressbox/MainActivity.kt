// SPDX-FileCopyrightText: 2026 Chris Palmer <chris@red-oxide.org>
// SPDX-License-Identifier: GPL-3.0-only

package io.github.chrisppy.pressbox

import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class MainActivity : FragmentActivity() {
  private val store: Store by viewModels()

  /**
   * The prompt is showing. Its screen-lock page is an activity of its own, which stops and starts
   * this one: that must neither lock again nor ask twice.
   */
  private var authenticating = false

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    lifecycle.addObserver(
        object : DefaultLifecycleObserver {
          override fun onStart(owner: LifecycleOwner) {
            if (store.isLocked && !authenticating) unlock()
            if (store.isConfigured) store.reloadAsync()
          }

          override fun onStop(owner: LifecycleOwner) {
            if (!authenticating) store.lockIfEnabled()
            Unread.schedule(applicationContext)
          }
        }
    )
    setContent {
      PressboxTheme {
        // Keep the app's content out of the recent apps screen while locking.
        LaunchedEffect(store.lockEnabled) {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(!store.lockEnabled)
          }
        }
        App(store, onUnlock = ::unlock)
      }
    }
  }

  /** Asks for a fingerprint, face or the screen lock. With none set up, there's nothing to ask. */
  private fun unlock() {
    if (authenticating) return
    val authenticators = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    val available = BiometricManager.from(this).canAuthenticate(authenticators)
    if (available != BiometricManager.BIOMETRIC_SUCCESS) {
      store.unlocked()
      return
    }
    authenticating = true
    val prompt =
        BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
              override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                authenticating = false
                store.unlocked()
              }

              override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Cancelled or failed for good: the lock screen's Unlock button tries again.
                authenticating = false
              }
            },
        )
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock your feeds")
            .setAllowedAuthenticators(authenticators)
            .build()
    )
  }
}

/**
 * Feeds, items and the article. On a phone (or a narrow window) they're stacked, one screen at a
 * time, with Back (and Home) in each bottom bar; wider, they sit side by side.
 */
@Composable
fun App(store: Store, onUnlock: () -> Unit) {
  Box(Modifier.fillMaxSize().background(Theme.palette.background)) {
    Column(Modifier.fillMaxSize()) {
      val playing = store.nowPlaying != null && !store.showSettings
      // While an episode plays, its bar takes the bottom edge, so the screens' own bottom bars
      // leave the navigation bar's inset to it.
      Box(
          Modifier.weight(1f)
              .then(
                  if (playing) Modifier.consumeWindowInsets(WindowInsets.navigationBars)
                  else Modifier
              )
      ) {
        if (store.showSettings) {
          SettingsScreen(store)
        } else {
          BoxWithConstraints(Modifier.fillMaxSize()) {
            when {
              maxWidth >= 1000.dp -> ThreePanes(store)
              maxWidth >= 600.dp -> TwoPanes(store)
              else -> Stacked(store)
            }
          }
        }
      }
      if (playing) MiniPlayer(store, Modifier.navigationBarsPadding())
    }
    if (store.isLocked) LockScreen(onUnlock)
  }
}

@Composable
private fun Stacked(store: Store) {
  val home = {
    store.selectedItem = null
    store.select(null)
  }
  val item = store.selectedItem
  val source = store.source
  when {
    item != null -> {
      BackHandler { store.selectedItem = null }
      CompositionLocalProvider(
          LocalNavigation provides Navigation(back = { store.selectedItem = null }, home = home)
      ) {
        ArticleScreen(store, item)
      }
    }
    source != null -> {
      BackHandler { store.select(null) }
      CompositionLocalProvider(LocalNavigation provides Navigation(back = { store.select(null) })) {
        ItemListScreen(store)
      }
    }
    else -> FeedListScreen(store)
  }
}

/** A list on the left (feeds, or a feed's items), and the article on the right. */
@Composable
private fun TwoPanes(store: Store) {
  Row(Modifier.fillMaxSize()) {
    Box(Modifier.width(360.dp).fillMaxHeight()) {
      if (store.source == null) {
        FeedListScreen(store)
      } else {
        BackHandler { store.select(null) }
        CompositionLocalProvider(
            LocalNavigation provides Navigation(back = { store.select(null) })
        ) {
          ItemListScreen(store)
        }
      }
    }
    VerticalDivider()
    ArticlePane(store)
  }
}

@Composable
private fun ThreePanes(store: Store) {
  Row(Modifier.fillMaxSize()) {
    Box(Modifier.width(300.dp).fillMaxHeight()) { FeedListScreen(store) }
    VerticalDivider()
    Box(Modifier.width(380.dp).fillMaxHeight()) {
      if (store.source != null) ItemListScreen(store)
    }
    VerticalDivider()
    ArticlePane(store)
  }
}

@Composable
private fun ArticlePane(store: Store) {
  Box(Modifier.fillMaxSize()) {
    val item = store.selectedItem
    if (item != null) {
      BackHandler { store.selectedItem = null }
      ArticleScreen(store, item)
    } else {
      NoArticle()
    }
  }
}

/** Covers the app while it's locked. */
@Composable
private fun LockScreen(onUnlock: () -> Unit) {
  BackHandler {}
  Column(
      Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(40.dp))
    Spacer(Modifier.size(16.dp))
    Text("pressbox is locked", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.size(16.dp))
    Button(onClick = onUnlock) { Text("Unlock") }
  }
}

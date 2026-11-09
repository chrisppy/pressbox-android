// SPDX-FileCopyrightText: 2026 Chris Palmer <chris@red-oxide.org>
// SPDX-License-Identifier: MPL-2.0

package io.github.chrisppy.pressbox

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the API token encrypted with a key in the Android Keystore, rather than in plain
 * preferences.
 *
 * The key doesn't need the user to authenticate, so the background worker can update the unread
 * count; the biometric lock guards the app's UI instead. The key can't leave this device, and the
 * app's data is excluded from backups, so the token never does either.
 */
object TokenStore {
  private const val ALIAS = "pressbox-api-token"
  private const val PREFS = "token"
  private const val KEY = "ciphertext"

  private fun key(): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(ALIAS, null) as? SecretKey)?.let {
      return it
    }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    generator.init(
      KeyGenParameterSpec.Builder(
          ALIAS,
          KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    )
    return generator.generateKey()
  }

  fun token(context: Context): String? {
    val stored =
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
    return try {
      val bytes = Base64.decode(stored, Base64.NO_WRAP)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
      String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    } catch (_: Exception) {
      // The key is gone (e.g. the app's data was restored elsewhere): pair again.
      null
    }
  }

  fun setToken(context: Context, token: String) {
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val trimmed = token.trim()
    if (trimmed.isEmpty()) {
      prefs.edit().remove(KEY).apply()
      return
    }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key())
    val sealed = cipher.iv + cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
    prefs.edit().putString(KEY, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
  }
}

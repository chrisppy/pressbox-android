// SPDX-FileCopyrightText: 2026 Chris Palmer <chris@red-oxide.org>
// SPDX-License-Identifier: GPL-3.0-only

package org.red_oxide.pressbox

import android.Manifest
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: Store) {
  val palette = Theme.palette
  var confirmForget by rememberSaveable { mutableStateOf(false) }
  BackHandler(enabled = store.isConfigured) { store.showSettings = false }
  DisposableEffect(Unit) { onDispose { store.cancelPairing() } }

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text("Settings") },
            actions = {
              TextButton(onClick = { store.showSettings = false }, enabled = store.isConfigured) {
                Text("Done")
              }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.background),
        )
      },
      containerColor = palette.background,
  ) { padding ->
    Column(
        Modifier.padding(padding)
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
      if (store.isConfigured) {
        Section("Server") {
          Labeled("Address", store.server)
          store.serverVersion?.let { Labeled("Version", "pressboxd ${it.version}") }
          store.me?.let { me ->
            Labeled("Signed in as", if (me.admin) "${me.user} (admin)" else me.user)
            me.device?.let { Labeled("This device", it) }
          }
        }
        ApproveSection(store)
        Toggles(store)
        Section(
            footer =
                "Removes the token and saved items. Revoke it on the server with `pressboxctl revoke`."
        ) {
          OutlinedButton(
              onClick = { confirmForget = true },
              modifier = Modifier.padding(horizontal = 16.dp),
          ) {
            Text("Forget this device", color = palette.error)
          }
        }
      } else {
        PairSection(store)
      }
      store.errorMessage?.let {
        Text(it, color = palette.error, modifier = Modifier.padding(16.dp))
      }
    }
  }

  if (confirmForget) {
    AlertDialog(
        onDismissRequest = { confirmForget = false },
        title = { Text("Forget this device?") },
        confirmButton = {
          TextButton(
              onClick = {
                confirmForget = false
                store.forget()
              }
          ) {
            Text("Forget", color = palette.error)
          }
        },
        dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun Section(
    title: String? = null,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
  Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
    if (title != null) {
      Text(
          title,
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
      )
    }
    content()
    if (footer != null) {
      Text(
          footer,
          style = MaterialTheme.typography.bodySmall,
          color = Theme.palette.mutedText,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      )
    }
  }
}

@Composable
private fun Labeled(label: String, value: String) {
  Row(
      Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(label)
    Spacer(Modifier.width(16.dp))
    SelectionContainer { Text(value, color = Theme.palette.mutedText) }
  }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
      Modifier.fillMaxWidth()
          .clickable { onChange(!checked) }
          .padding(horizontal = 16.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(label, modifier = Modifier.weight(1f))
    Switch(checked = checked, onCheckedChange = onChange)
  }
}

@Composable
private fun Toggles(store: Store) {
  val context = LocalContext.current
  val askNotifications =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) store.updateNotifyUnread(true)
        else store.errorMessage = "Allow notifications for pressbox in the system settings."
      }
  Section(
      footer =
          "Scrolled past means off the top of the list while you scroll. The unread count is a " +
              "silent notification, which your launcher shows as a badge; it is updated in the " +
              "background from time to time, and Android decides how often."
  ) {
    ToggleRow("Require unlock", store.lockEnabled) { store.updateLockEnabled(it) }
    ToggleRow("Mark as read when scrolled past", store.markReadOnScroll) {
      store.updateMarkReadOnScroll(it)
    }
    ToggleRow("Unread count on app icon", store.notifyUnread) { enabled ->
      if (
          enabled &&
              !Unread.canNotify(context) &&
              Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
      ) {
        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
      } else {
        store.updateNotifyUnread(enabled)
      }
    }
  }
}

@Composable
private fun PairSection(store: Store) {
  val palette = Theme.palette
  val context = LocalContext.current
  var server by rememberSaveable { mutableStateOf(store.server) }
  var deviceName by rememberSaveable {
    mutableStateOf(
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?: Build.MODEL
    )
  }
  var manualToken by rememberSaveable { mutableStateOf("") }
  var showToken by rememberSaveable { mutableStateOf(false) }

  Section(
      "Connect to pressbox",
      footer =
          if (!ApiClient.isLocalHttp(server))
              "This is plain http to an address that isn't on your network: the token and your " +
                  "feeds would travel unencrypted. Use https, for example with tailscale serve."
          else "Plain http is fine on your local network or tailnet; use https otherwise.",
  ) {
    OutlinedTextField(
        value = server,
        onValueChange = { server = it },
        label = { Text("Server") },
        placeholder = { Text("https://rss.example.org") },
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
    OutlinedTextField(
        value = deviceName,
        onValueChange = { deviceName = it },
        label = { Text("Device name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
  }
  Section {
    Column(Modifier.padding(horizontal = 16.dp)) {
      val code = store.pairCode
      if (code != null) {
        SelectionContainer {
          Text(
              code,
              fontSize = 40.sp,
              fontWeight = FontWeight.Bold,
              fontFamily = FontFamily.Monospace,
          )
        }
        Text(
            "Approve it on the server with `pressboxctl approve $code`, or from another paired " +
                "device.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.size(8.dp))
        OutlinedButton(onClick = { store.cancelPairing() }) { Text("Cancel") }
      } else {
        Button(
            onClick = { store.startPairing(server, deviceName) },
            enabled = server.isNotBlank(),
        ) {
          Text("Pair this device")
        }
      }
      store.pairStatus?.let {
        Text(it, color = palette.mutedText, modifier = Modifier.padding(top = 8.dp))
      }
    }
  }
  Section {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
    Row(
        Modifier.fillMaxWidth()
            .clickable { showToken = !showToken }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("Use a token instead", modifier = Modifier.weight(1f))
      Icon(
          if (showToken) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
          contentDescription = null,
      )
    }
    if (showToken) {
      OutlinedTextField(
          value = manualToken,
          onValueChange = { manualToken = it },
          label = { Text("Token from pressboxctl add-device") },
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions =
              KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
      )
      FilledTonalButton(
          onClick = { store.useToken(server, manualToken) },
          enabled = manualToken.isNotBlank() && server.isNotBlank(),
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
      ) {
        Text("Use token")
      }
    }
  }
}

@Composable
private fun ApproveSection(store: Store) {
  val scope = rememberCoroutineScope()
  var code by rememberSaveable { mutableStateOf("") }
  var user by rememberSaveable { mutableStateOf("") }
  var status by rememberSaveable { mutableStateOf<String?>(null) }
  Section(
      "Approve a device",
      footer =
          "Enter the code another device shows while pairing. It's added to your account unless " +
              "you name another user.",
  ) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      OutlinedTextField(
          value = code,
          onValueChange = { code = it },
          label = { Text("Code, e.g. K7W-4QX") },
          singleLine = true,
          textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
          keyboardOptions =
              KeyboardOptions(
                  capitalization = KeyboardCapitalization.Characters,
                  autoCorrectEnabled = false,
              ),
          modifier = Modifier.weight(1f),
      )
      Spacer(Modifier.width(8.dp))
      FilledTonalButton(
          onClick = {
            scope.launch {
              store.approve(code, user)?.let {
                status = "Paired $it."
                code = ""
                user = ""
              }
            }
          },
          enabled = code.trim().length >= 6,
      ) {
        Text("Approve")
      }
    }
    if (store.me?.admin == true) {
      OutlinedTextField(
          value = user,
          onValueChange = { user = it },
          label = { Text("For user (blank: you)") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
      )
    }
    status?.let {
      Text(
          it,
          color = Theme.palette.mutedText,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
      )
    }
  }
}

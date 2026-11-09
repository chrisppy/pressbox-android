// SPDX-FileCopyrightText: 2026 Chris Palmer <chris@red-oxide.org>
// SPDX-License-Identifier: GPL-3.0-only

package io.github.chrisppy.pressbox

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The unread count, Android's take on the iOS app's icon badge: a silent notification carrying the
 * count, which launchers show as a badge (a dot, or the number on launchers that show numbers). A
 * periodic worker keeps it current while the app isn't open; Android decides exactly when it runs.
 */
object Unread {
  const val PREF = "notifyUnread"
  private const val CHANNEL = "unread"
  private const val NOTIFICATION = 1
  private const val WORK = "unread-refresh"

  fun canNotify(context: Context): Boolean =
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED

  private fun ensureChannel(context: Context) {
    val channel =
        NotificationChannel(CHANNEL, "Unread count", NotificationManager.IMPORTANCE_LOW).apply {
          description = "How many items are unread, shown as a badge on the app icon."
          setShowBadge(true)
          enableVibration(false)
          setSound(null, null)
        }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  /** Shows [count] unread, or removes the notification at zero. */
  // The permission is checked by canNotify, which lint can't see through.
  @SuppressLint("MissingPermission")
  fun show(context: Context, count: Int) {
    val manager = NotificationManagerCompat.from(context)
    if (count <= 0 || !canNotify(context)) {
      manager.cancel(NOTIFICATION)
      return
    }
    ensureChannel(context)
    val open =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    val notification =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (count == 1) "1 unread item" else "$count unread items")
            .setNumber(count)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()
    try {
      manager.notify(NOTIFICATION, notification)
    } catch (_: SecurityException) {
      // Permission withdrawn between the check and here.
    }
  }

  fun cancel(context: Context) {
    NotificationManagerCompat.from(context).cancel(NOTIFICATION)
  }

  /** Starts or stops the periodic refresh, following the setting. */
  fun schedule(context: Context) {
    val work = WorkManager.getInstance(context)
    val enabled =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(PREF, false)
    if (!enabled) {
      work.cancelUniqueWork(WORK)
      cancel(context)
      return
    }
    val request =
        PeriodicWorkRequestBuilder<UnreadWorker>(30, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
    work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
  }
}

/** The background refresh: fetch unread counts and update the notification. */
class UnreadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val prefs = applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    if (!prefs.getBoolean(Unread.PREF, false)) return Result.success()
    val server = prefs.getString("server", null) ?: return Result.success()
    val token = TokenStore.token(applicationContext) ?: return Result.success()
    val feeds =
        try {
          ApiClient(server, token).feeds()
        } catch (e: Exception) {
          return if (e.isOffline()) Result.retry() else Result.success()
        }
    Unread.show(applicationContext, feeds.filter { !it.isArchived }.sumOf { it.unread })
    return Result.success()
  }
}

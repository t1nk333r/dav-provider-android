package xyz.satr.davprovider.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File
import xyz.satr.davprovider.R
import xyz.satr.davprovider.core.SyncError
import xyz.satr.davprovider.sync.AccountSyncReport

/**
 * Notifications for the failures that need the user, and only those.
 *
 * Two rules shape this. A notification fires only for a **terminal** class (proxy rejection, a
 * server asking for credentials, an unavailable certificate, unreadable credentials, no
 * certificate sent) — a transport failure retries itself and a DAV information condition is not a
 * problem. And the same failure state notifies **once**: the last signature per Account is kept, so
 * a Collection that stays broken does not alert on every interval, while any change in what is
 * wrong alerts again.
 */
internal class SyncNotifications(private val context: Context) {

    fun dispatch(report: AccountSyncReport) {
        val label = report.account.name
        val terminalCollections = report.collections.filter { it.error?.errorClass?.terminal == true }
        val runFailure = report.error?.takeIf { it.errorClass.terminal }
        if (terminalCollections.isEmpty() && runFailure == null) {
            // The account is fine again: a stale failure must not stay in the shade.
            dismiss(label)
            return
        }
        val signature = (
            terminalCollections.map { "${it.collectionId}:${it.error?.errorClass}" } +
                listOfNotNull(runFailure?.let { "run:${it.errorClass}" })
            ).sorted().joinToString(",")
        // Not shown means not remembered: granting the permission later must still alert, and
        // until then a repeat costs nothing.
        if (!canPost()) return
        if (remembered(label) == signature) return
        remember(label, signature)

        val failure: SyncError = runFailure ?: terminalCollections.first().error ?: return
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dav)
            .setContentTitle(context.getString(R.string.notification_title, label))
            .setContentText(failure.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(failure.summary))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openSettings())
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(label), notification)
    }

    /** Removing an Account also removes the failure it was telling the user about. */
    fun dismiss(label: String) {
        cancel(label)
        forget(label)
    }

    private fun cancel(label: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(label))
    }

    private fun openSettings(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notificationId(label: String): Int = label.hashCode()

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.notification_channel_description) },
        )
    }

    /**
     * On API 33+ the app must have been granted POST_NOTIFICATIONS; the user is asked when they
     * start a sync by hand, which is the moment the answer is about something.
     */
    private fun canPost(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun remembered(label: String): String? = readState()[label]

    private fun remember(label: String, signature: String) {
        val state = readState() + (label to signature)
        file().writeText(state.entries.joinToString("\n") { "${it.key}\t${it.value}" })
    }

    private fun forget(label: String) {
        val state = readState() - label
        file().writeText(state.entries.joinToString("\n") { "${it.key}\t${it.value}" })
    }

    private fun readState(): Map<String, String> {
        val file = file()
        if (!file.exists()) return emptyMap()
        return file.readLines()
            .mapNotNull { line ->
                val parts = line.split("\t")
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()
    }

    private fun file() = File(context.applicationContext.filesDir, FILE_NAME)

    private companion object {
        const val CHANNEL_ID = "sync-failures"
        const val FILE_NAME = "notified-failures.txt"
    }
}

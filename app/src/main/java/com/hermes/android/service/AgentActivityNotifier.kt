package com.hermes.android.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.hermes.android.MainActivity
import com.hermes.android.R
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proactive agent-activity notifications (delegation v1).
 *
 * When a turn or background task finishes while no Activity is visible, the
 * user gets a tappable notification carrying a preview of the result. Tapping
 * opens MainActivity with [EXTRA_SESSION_ID] so the nav host can resume the
 * session the result belongs to.
 *
 * Mirrors [ApprovalNotificationManager]'s structure (channel-per-concern,
 * notification id derived from the stable key) so the service layer stays
 * consistent.
 */
@Singleton
class AgentActivityNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    init {
        createNotificationChannel()
    }

    /** A live chat turn finished while the app was backgrounded. */
    fun showTurnComplete(sessionId: String?, preview: String) {
        // User-level kill switch (Settings → Behavior). Checked here, not at
        // call sites, so every delivery path (live events, reconnect sync,
        // periodic worker) honors it without re-implementing the gate.
        if (!com.hermes.android.util.AppPrefs.isAgentReplyNotifEnabled(context)) return
        show(
            key = sessionId ?: "turn",
            title = context.getString(R.string.notification_agent_reply_title),
            preview = preview,
            sessionId = sessionId,
        )
    }

    /** A prompt.background task finished (result is ephemeral — show it). */
    fun showBackgroundTaskComplete(taskId: String, sessionId: String?, preview: String) {
        if (!com.hermes.android.util.AppPrefs.isTaskDoneNotifEnabled(context)) return
        show(
            key = "bg_$taskId",
            title = context.getString(R.string.notification_task_done_title),
            preview = preview,
            sessionId = sessionId,
        )
    }

    /**
     * Dismiss every agent-activity notification this class posted.
     * Called when the app returns to the foreground — once the user is
     * looking at the app, the "agent finished while you were away" alerts
     * have served their purpose and shouldn't linger in the shade.
     *
     * Cancels only ids we tracked, never [NotificationManager.cancelAll] —
     * that would also kill the gateway foreground-service notification.
     */
    fun dismissAll() {
        if (postedIds.isEmpty()) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        postedIds.forEach { manager.cancel(it) }
        postedIds.clear()
    }

    private fun show(key: String, title: String, preview: String, sessionId: String?) {
        val text = preview.trim().take(400).ifEmpty { return }
        Timber.i("[AgentNotify] $title (key=$key, session=$sessionId)")

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!sessionId.isNullOrBlank()) putExtra(EXTRA_SESSION_ID, sessionId)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            key.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            // Same group on every agent notification → the system bundles
            // them into one expandable stack instead of spamming the shade
            // when several sessions/tasks finish back-to-back.
            .setGroup(GROUP_AGENT)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()

        val id = key.hashCode()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notification)
        postedIds.add(id)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_agent_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notification_channel_agent_desc)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "hermes_agent_activity"
        private const val GROUP_AGENT = "hermes_agent_activity_group"

        /** Intent extra: session to resume when the notification is tapped. */
        const val EXTRA_SESSION_ID = "hermes.notification.session_id"
    }

    /** Notification ids this instance posted, so [dismissAll] is surgical. */
    private val postedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
}

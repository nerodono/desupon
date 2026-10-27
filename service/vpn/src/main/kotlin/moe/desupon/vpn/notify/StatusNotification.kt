package moe.desupon.vpn.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import moe.desupon.brain.port.ConnState
import moe.desupon.vpn.R
import moe.desupon.vpn.service.DesuponVpnService

/** Neutral status text for a connection state; also used by the tile. */
@StringRes
fun ConnState.statusText(): Int = when (this) {
    ConnState.Idle -> R.string.svc_state_idle
    is ConnState.Connecting, is ConnState.TurnWarmup -> R.string.svc_state_connecting
    is ConnState.Connected -> R.string.svc_state_connected
    is ConnState.Switching -> R.string.svc_state_switching
    is ConnState.Degraded -> R.string.svc_state_degraded
    is ConnState.Unstable -> R.string.svc_state_unstable
    is ConnState.Failed -> R.string.svc_state_failed
}

/**
 * The foreground notification. Connected, it names the place, the subscription and the server and shows ping, speeds,
 * session totals and a session chronometer; otherwise only the app name and a state word. The lock screen gets the
 * neutral app name and state word in every state.
 */
class StatusNotification(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var shown: StatusContent? = null

    fun build(content: StatusContent): Notification {
        ensureChannel()
        shown = content
        val builder = base(neutralText(content))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(base(neutralText(content)).build())
            .addAction(0, context.getString(R.string.svc_action_disconnect), stopIntent())
        if (content is StatusContent.Connected) {
            val text = context.resources.connectedText(content)
            builder.setContentTitle(text.title)
                .setContentText(text.text)
                .setSubText(text.subText)
                .setUsesChronometer(true)
                .setShowWhen(true)
                .setWhen(content.sinceMs)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.bigText))
        }
        launchIntent()?.let(builder::setContentIntent)
        return builder.build()
    }

    /** Re-posts only when the content changes. */
    fun show(content: StatusContent) {
        if (content == shown) return
        val notification = build(content)
        try {
            manager.notify(ID, notification)
        } catch (_: SecurityException) {
        }
    }

    /** App name and a state word, nothing that tells what the connection is or where it goes. */
    private fun base(@StringRes text: Int): NotificationCompat.Builder = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.svc_ic_sprout)
        .setColor(ContextCompat.getColor(context, R.color.svc_accent))
        .setContentTitle(context.getString(R.string.svc_title))
        .setContentText(context.getString(text))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

    @StringRes
    private fun neutralText(content: StatusContent): Int = when (content) {
        is StatusContent.Plain -> content.text
        is StatusContent.Connected -> R.string.svc_state_connected
    }

    private fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.svc_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        context,
        REQUEST_STOP,
        DesuponVpnService.stopIntent(context),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun launchIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, REQUEST_OPEN, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val ID = 1
        const val CHANNEL_ID = "status"
        private const val REQUEST_STOP = 1
        private const val REQUEST_OPEN = 2
    }
}

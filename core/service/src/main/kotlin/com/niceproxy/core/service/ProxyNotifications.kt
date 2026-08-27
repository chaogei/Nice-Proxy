package com.niceproxy.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class ProxyNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val manager: NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.service_notification_channel_name),
            // LOW 而不是 DEFAULT：这是一条常驻状态通知，
            // 每秒刷新速率，任何提示音或悬浮都是骚扰。
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.service_notification_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager?.createNotificationChannel(channel)
    }

    /**
     * @param statusText 覆盖正文。用于展示 [ProxyState] 表达不了的过渡信息 ——
     *        典型的是退避重试的倒计时：状态确实是 Starting，但干巴巴显示
     *        「正在启动…」半分钟会让用户以为卡死了。
     */
    fun build(
        state: ProxyState,
        traffic: TrafficSnapshot,
        contentIntent: PendingIntent?,
        stopIntent: PendingIntent,
        statusText: String? = null,
    ): Notification {
        val title = when (state) {
            ProxyState.Starting -> context.getString(R.string.service_state_starting)
            ProxyState.Stopping -> context.getString(R.string.service_state_stopping)
            is ProxyState.Failed -> context.getString(R.string.service_state_failed)
            is ProxyState.Running -> context.getString(R.string.service_state_running)
            ProxyState.Stopped -> context.getString(R.string.service_state_running)
        }

        val text = statusText ?: when (state) {
            is ProxyState.Running -> buildString {
                append(
                    context.getString(
                        R.string.service_ports_prefix,
                        state.listeningOn.joinToString("、") { it.port.toString() },
                    ),
                )
                append("    ")
                append(
                    context.getString(
                        R.string.service_speed_format,
                        formatSpeed(traffic.uploadBytesPerSecond),
                        formatSpeed(traffic.downloadBytesPerSecond),
                    ),
                )
            }
            is ProxyState.Failed -> state.message
            else -> null
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(state.isActive)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { contentIntent?.let(::setContentIntent) }
            .addAction(
                0,
                context.getString(R.string.service_notification_stop),
                stopIntent,
            )
            .build()
    }

    fun notify(notification: Notification) {
        runCatching { manager?.notify(NOTIFICATION_ID, notification) }
    }

    fun cancel() {
        runCatching { manager?.cancel(NOTIFICATION_ID) }
    }

    companion object {
        const val CHANNEL_ID = "proxy_service"
        const val NOTIFICATION_ID = 1001

        fun formatSpeed(bytesPerSecond: Long): String {
            val value = abs(bytesPerSecond)
            return when {
                value < 1024 -> "$value B/s"
                value < 1024 * 1024 -> "%.1f KB/s".format(value / 1024.0)
                value < 1024L * 1024 * 1024 -> "%.1f MB/s".format(value / (1024.0 * 1024))
                else -> "%.2f GB/s".format(value / (1024.0 * 1024 * 1024))
            }
        }
    }
}

/** 通知上「停止」按钮的目标，避免为它单开一个 Activity。 */
internal fun stopPendingIntent(context: Context): PendingIntent =
    PendingIntent.getService(
        context,
        0,
        Intent(context, ProxyService::class.java).setAction(ProxyService.ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

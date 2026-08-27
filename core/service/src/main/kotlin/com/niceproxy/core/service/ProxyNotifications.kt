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

        // 单独一个渠道：这类提醒需要用户真的看见并去操作，
        // 混进上面那条静音的常驻通知里等于不存在。用户也应当能单独关掉它。
        val alertChannel = NotificationChannel(
            ALERT_CHANNEL_ID,
            context.getString(R.string.keepalive_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.keepalive_channel_description)
        }
        manager?.createNotificationChannel(alertChannel)
    }

    /**
     * 代理掉了、而自动恢复也被系统挡住了。
     *
     * 这是保活链条上最坏的一格：看门狗醒来发现该跑却没在跑，却因为 Android 12+ 的
     * 后台启动限制拉不起前台服务。不出这条通知的话，用户那边的表现是「所有设备
     * 莫名其妙断网，而且再也不会自己好」，且没有任何线索指向原因。
     */
    fun notifyRecoveryBlocked() {
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.keepalive_blocked_title))
            .setContentText(context.getString(R.string.keepalive_blocked_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.keepalive_blocked_text)),
            )
            .setAutoCancel(true)
            // 同 ID 会替换而不是叠加，配合 OnlyAlertOnce，
            // 看门狗每 15 分钟失败一次也不会反复响
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .apply { launchIntent()?.let(::setContentIntent) }
            .build()
        runCatching { manager?.notify(ALERT_NOTIFICATION_ID, notification) }
    }

    fun cancelRecoveryBlocked() {
        runCatching { manager?.cancel(ALERT_NOTIFICATION_ID) }
    }

    private fun launchIntent(): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

    /**
     * 通知上真正会被用户看见的那部分。
     *
     * 拆出来是为了**判重**：这条通知每秒要刷好几次，而 `notify` 是一次跨进程 Binder
     * 调用，全都发生在主线程上。速率从 1.0 KB/s 变成 1.04 KB/s 时渲染出来的文本
     * 一模一样，那一次 IPC 纯属浪费。比对原始字节数没用 —— 那玩意儿每秒都在变；
     * 只有比对**渲染后的文本**才能真正把无效刷新挡掉。
     */
    data class Content(
        val title: String,
        val text: String?,
        val ongoing: Boolean,
    )

    /**
     * @param statusText 覆盖正文。用于展示 [ProxyState] 表达不了的过渡信息 ——
     *        典型的是退避重试的倒计时：状态确实是 Starting，但干巴巴显示
     *        「正在启动…」半分钟会让用户以为卡死了。
     */
    fun content(
        state: ProxyState,
        traffic: TrafficSnapshot,
        statusText: String? = null,
    ): Content {
        val title = when (state) {
            ProxyState.Starting -> context.getString(R.string.service_state_starting)
            ProxyState.Stopping -> context.getString(R.string.service_state_stopping)
            is ProxyState.Failed -> context.getString(R.string.service_state_failed)
            is ProxyState.Running -> context.getString(R.string.service_state_running)
            ProxyState.Stopped -> context.getString(R.string.service_state_stopped)
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

        return Content(title = title, text = text, ongoing = state.isActive)
    }

    /**
     * @param contentIntent 与 [stopIntent] 都由调用方缓存后传进来。构造它们各是一次
     *        跨进程调用，而这条通知每秒要重建好几次 —— 每次都现建等于白白多花几次 IPC。
     */
    fun build(
        content: Content,
        contentIntent: PendingIntent?,
        stopIntent: PendingIntent,
    ): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setOngoing(content.ongoing)
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

        private const val ALERT_CHANNEL_ID = "keepalive_alert"
        private const val ALERT_NOTIFICATION_ID = 1002

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

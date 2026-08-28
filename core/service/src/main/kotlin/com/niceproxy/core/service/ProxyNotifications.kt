package com.niceproxy.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.niceproxy.core.model.StartReason
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
     *
     * **光有一条「点此打开应用」的通知还不够。** 它要求用户看懂文案、进应用、找到
     * 设置页、找到电池优化那一项 —— 中间任何一步放弃，代理就一直是停的。所以这条
     * 通知现在带三个动作按钮，每一个都能一步到位：
     *
     * - **立即启动**：从通知动作发出的 PendingIntent 属于官方豁免项之一，
     *   哪怕应用完全在后台，这一下也能真的把前台服务拉起来。这是这条通知最重要的
     *   一个按钮 —— 它让「恢复」这件事不再需要用户理解发生了什么。
     * - **关闭电池优化**：直达系统设置里的那一页，用户不用自己去翻。
     * - **不再尝试**：清掉落盘的运行意图。没有它的话，看门狗会每 15 分钟弹一次，
     *   而界面上那时只有「启动」没有「停止」，用户根本找不到能让它安静下来的开关。
     */
    fun notifyRecoveryBlocked(detail: String? = null) {
        val text = detail?.let {
            context.getString(R.string.keepalive_blocked_text_with_reason, it)
        } ?: context.getString(R.string.keepalive_blocked_text)

        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.keepalive_blocked_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            // 同 ID 会替换而不是叠加，配合 OnlyAlertOnce，
            // 看门狗每 15 分钟失败一次也不会反复响
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .apply { launchIntent()?.let(::setContentIntent) }
            .addAction(0, context.getString(R.string.action_start_now), startPendingIntent())
            .apply {
                batteryOptimizationIntent()?.let {
                    addAction(0, context.getString(R.string.action_battery_settings), it)
                }
            }
            .addAction(0, context.getString(R.string.action_give_up), giveUpPendingIntent())
            .build()
        runCatching { manager?.notify(ALERT_NOTIFICATION_ID, notification) }
    }

    fun cancelRecoveryBlocked() {
        runCatching { manager?.cancel(ALERT_NOTIFICATION_ID) }
    }

    /**
     * 代理进入终态失败。
     *
     * 以前这条路径上是 `stopForeground(REMOVE)` 加 `cancel()` —— 通知被撤掉、服务
     * 退出，**用户那边什么都不会发生**。一个给全屋设备供网的网关就这么无声无息地
     * 没了，别的设备表现为「网页打不开」，而手机上没有任何痕迹可查。
     *
     * 所以终态失败必须留下一条通知，而且要带上出路：可重试的失败给「立即重试」，
     * 不管哪种都给「不再尝试」让用户能把它彻底关掉。
     *
     * @param retryable 失败成因是暂时性的（见 [FailureCause.deterministic]），
     *        再试一次有意义。确定性错误不给这个按钮 —— 那只会让用户白点几次。
     */
    fun notifyFailure(message: String, detail: String?, retryable: Boolean) {
        val text = listOfNotNull(message, detail?.takeIf { it != message }).joinToString("\n")
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.service_failed_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .apply {
                launchIntent()?.let(::setContentIntent)
                if (retryable) {
                    addAction(0, context.getString(R.string.action_retry), startPendingIntent())
                }
            }
            .addAction(0, context.getString(R.string.action_give_up), giveUpPendingIntent())
            .build()
        runCatching { manager?.notify(FAILURE_NOTIFICATION_ID, notification) }
    }

    fun cancelFailure() {
        runCatching { manager?.cancel(FAILURE_NOTIFICATION_ID) }
    }

    private fun launchIntent(): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }

    /**
     * 通知动作发出的启动 PendingIntent。
     *
     * 两个细节都不能省：
     *
     * - **必须是 `getForegroundService`**。这些按钮出现的时机恰恰是「服务没在跑、
     *   应用在后台」，那时候普通的 `startService` 会直接抛 IllegalStateException，
     *   按钮点了什么都不会发生。
     * - **它能绕过后台启动限制**。用户在通知上的操作是官方豁免项之一，所以哪怕
     *   看门狗刚刚才被系统拦下来，这一下也能真的把前台服务拉起来 —— 这正是这个
     *   按钮比任何「打开应用自己按一下」的引导都可靠的原因。
     */
    private fun startPendingIntent(): PendingIntent {
        val intent = Intent(context, ProxyService::class.java)
            .setAction(ProxyService.ACTION_START)
            .putExtra(ProxyService.EXTRA_START_REASON, StartReason.USER.name)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, REQUEST_START, intent, flags)
        } else {
            PendingIntent.getService(context, REQUEST_START, intent, flags)
        }
    }

    private fun giveUpPendingIntent(): PendingIntent = PendingIntent.getService(
        context,
        REQUEST_GIVE_UP,
        Intent(context, ProxyService::class.java).setAction(ProxyService.ACTION_GIVE_UP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * 直达系统的电池优化设置页。
     *
     * 用 `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`（列表页）而不是
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（直接弹允许对话框）：后者需要
     * 一个会被应用商店重点审查的敏感权限，而它省下的只是用户在列表里点一下自己的
     * 应用名。用不着为那一下背上分发风险。
     *
     * @return 系统没有这个页面时返回 null（少数精简 ROM），那就不加这个按钮，
     *         而不是加一个点了什么也不会发生的按钮。
     */
    private fun batteryOptimizationIntent(): PendingIntent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        // 这个 action 不接受 data，多塞一个包名 Uri 会让它在部分 ROM 上解析不出来
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return null
        return runCatching {
            PendingIntent.getActivity(
                context,
                REQUEST_BATTERY,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }.getOrNull()
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

        /**
         * 终态失败与「无法自动恢复」用不同的 ID。
         *
         * 它们说的是两件事：一个是「这次起不来，原因是 X」，另一个是「系统不让我在
         * 后台自己起来」。共用一个 ID 的话，后者会把前者的具体原因整个盖掉。
         */
        private const val FAILURE_NOTIFICATION_ID = 1003

        private const val REQUEST_START = 1
        private const val REQUEST_GIVE_UP = 2
        private const val REQUEST_BATTERY = 3

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

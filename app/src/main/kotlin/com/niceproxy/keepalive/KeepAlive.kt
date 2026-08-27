package com.niceproxy.keepalive

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import java.util.Locale

/**
 * 把服务从系统与厂商省电策略手里保下来所需的那些入口。
 *
 * 本应用不走 VpnService，代理只是一个普通前台服务：它一旦被冻结或杀掉，局域网里
 * 那些把网关指过来的设备（Switch、PS5、电视盒子）会集体断网，而这些设备不会报错，
 * 用户往往过很久才发现。所以这里每一项都是功能性的，不是「优化建议」。
 *
 * 刻意不依赖 Compose：设置页、首页之外，服务侧与诊断代码也可能要读这些状态。
 */
object KeepAlive {

    // ------------------------------------------------------------ 电池优化白名单

    /**
     * [PowerManager.isIgnoringBatteryOptimizations] 自 API 23 起提供，minSdk 24，
     * 因此不需要版本判断。
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        // 必须用 context.packageName 取包名：debug 构建带 .debug 后缀，写死会恒为 false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 依次尝试三档入口，返回是否真的打开了某个页面。
     *
     * 第一档是系统的直接授权对话框，用户点一下「允许」就完事。代价是必须在 manifest
     * 里声明 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`，否则运行时抛 SecurityException。
     * 该权限受 Google Play 政策限制（只对闹钟、VoIP 等少数类别开放），本项目经
     * GitHub Release / F-Droid 分发，不受该政策约束，且长期存活正是代理网关的核心需求。
     * 若将来要上架 Play，删掉这一档、只留后两档即可，功能会退化但不违规。
     *
     * 后两档不是保险而是必需：部分国产 ROM 直接屏蔽了这个 Intent。
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        val candidates = buildList {
            // 已在白名单时 AOSP 这个 Activity 会立刻 finish，用户只会看到「点了没反应」。
            // 此时直接送到列表页，那里才能看到并撤销当前设置。
            if (!isIgnoringBatteryOptimizations(context)) {
                add(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
            add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            add(appDetailsIntent(context))
        }
        return startFirstAvailable(context, candidates)
    }

    // -------------------------------------------------------------- 厂商自启动管理

    /**
     * 当前设备是否存在厂商自己的自启动 / 后台管理白名单。
     *
     * 用于决定 UI 上要不要露出这个入口：原生 ROM（Pixel 等）上它只会跳到应用详情页，
     * 摆着只会让用户以为自己漏配了什么。
     */
    fun hasVendorAutoStartSettings(context: Context): Boolean {
        val vendor = currentVendor() ?: return false
        return vendor.intents().any { it.resolvable(context) } ||
            vendor.packages.any { context.packageManager.getLaunchIntentForPackage(it) != null }
    }

    /**
     * 打开厂商的自启动管理页。
     *
     * 国产 ROM 的自启动白名单是独立于 Android 电池优化的另一套管控：不在名单里，
     * 前台服务照样会被管家清掉，START_STICKY 也救不回来——进程根本不会被重建。
     *
     * 组件名随 ROM 版本变动频繁，所以每个厂商都排了多个候选，从新到旧依次尝试；
     * 全部失效时先退到「手机管家首页」（自启动开关就在里面，只是要多点几下），
     * 再退到应用详情页。
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val vendor = currentVendor()
        val candidates = buildList {
            if (vendor != null) {
                addAll(vendor.intents())
                vendor.packages.forEach { pkg ->
                    context.packageManager.getLaunchIntentForPackage(pkg)?.let { add(it) }
                }
            }
            add(appDetailsIntent(context))
        }
        return startFirstAvailable(context, candidates)
    }

    // -------------------------------------------------------------------- 内部实现

    private fun currentVendor(): Vendor? {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
        val brand = Build.BRAND.lowercase(Locale.ROOT)
        return VENDORS.firstOrNull { manufacturer in it.keys || brand in it.keys }
    }

    private fun appDetailsIntent(context: Context) = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )

    private fun startFirstAvailable(context: Context, intents: List<Intent>): Boolean {
        intents.forEach { intent ->
            // 从 ViewModel / Application 这类非 Activity 上下文启动时这个 flag 是必需的
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                // 捕获范围刻意放宽到 Exception。这里至少有三类可预期失败：
                // ActivityNotFoundException（该 ROM 版本没有这个组件）、
                // SecurityException（组件存在但被签名级权限保护，ColorOS 的
                // oppo.permission.OPPO_COMPONENT_SAFE 是典型）、
                // 以及个别 ROM 抛出的自定义异常。哪一种都不该让应用崩溃。
                Log.d(TAG, "打不开 ${intent.component ?: intent.action}", e)
            }
        }
        return false
    }

    // API 33 起 int flags 的重载被标为废弃，但 minSdk 24 还得留着它
    @Suppress("DEPRECATION")
    private fun Intent.resolvable(context: Context): Boolean {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(
                this,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            ) != null
        } else {
            pm.resolveActivity(this, PackageManager.MATCH_DEFAULT_ONLY) != null
        }
    }

    private const val TAG = "KeepAlive"
}

/**
 * 一个厂商的候选入口。
 *
 * [keys] 同时比对 `Build.MANUFACTURER` 和 `Build.BRAND`，只看一个都会漏：
 * Redmi / POCO 的 MANUFACTURER 是 Xiaomi，iQOO 的是 vivo；而 2020 年独立之后的荣耀
 * 两个字段都是 HONOR，更早的机器却是 MANUFACTURER=HUAWEI、BRAND=HONOR。
 *
 * @param components `包名/类名` 扁平写法，按「新 ROM 优先」排列，第一个能打开的就用。
 *   类名以 `.` 开头表示与包名同前缀，由 [ComponentName.unflattenFromString] 展开。
 */
private class Vendor(
    val keys: Set<String>,
    components: List<String>,
) {
    private val resolved: List<ComponentName> =
        components.mapNotNull { ComponentName.unflattenFromString(it) }

    /** 组件名全部失效时，用它们的 launcher 入口把用户送到管家首页。 */
    val packages: List<String> = resolved.map { it.packageName }.distinct()

    fun intents(): List<Intent> = resolved.map { Intent().setComponent(it) }
}

/**
 * 厂商自启动管理页的候选组件表。
 *
 * 这些字符串没有任何官方文档，全部来自社区实测汇总，主要来源：
 *  - judemanutd/AutoStarter（长期维护的跨厂商列表，本表的骨架）
 *  - gddhy/miaoa_yousa 的 Util.java（国产 ROM 覆盖最全，含版本标注）
 *  - StackOverflow #34149198 / #39366231 与各厂商开发者社区帖
 *
 * 可靠性差异很大，注释里逐条标了适用版本。因为 ROM 一升级组件名就可能改，
 * 这里的策略始终是「多候选 + 一路回退」，而不是赌某一个名字永远有效。
 */
private val VENDORS: List<Vendor> = listOf(
    // 荣耀（MagicOS / Magic UI）。必须排在华为前面：老荣耀机器 MANUFACTURER 报 HUAWEI，
    // 靠 BRAND=HONOR 才能命中；命中后仍会向下尝试华为组件，因为它们那时跑的就是 EMUI。
    Vendor(
        keys = setOf("honor", "hihonor"),
        components = listOf(
            // 荣耀独立后自建的包名，MagicOS 7 及以后
            "com.hihonor.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
            "com.hihonor.systemmanager/.appcontrol.activity.StartupAppControlActivity",
            // Magic UI 时期与华为共用 com.huawei.systemmanager
            "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager/.optimize.process.ProtectActivity",
        ),
    ),

    // 华为（EMUI / HarmonyOS）。「应用启动管理」，需要先关掉「自动管理」再开自启动。
    Vendor(
        keys = setOf("huawei"),
        components = listOf(
            // EMUI 8 起至 HarmonyOS，最常见的一个
            "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
            // EMUI 5 ~ 8
            "com.huawei.systemmanager/.appcontrol.activity.StartupAppControlActivity",
            // EMUI 4 / 5 的「受保护应用」，语义接近但不完全等同
            "com.huawei.systemmanager/.optimize.process.ProtectActivity",
            // 更早的「开机启动项」
            "com.huawei.systemmanager/.optimize.bootstart.BootStartActivity",
        ),
    ),

    // 小米 / Redmi / POCO（MIUI / HyperOS）。本表里最稳的一条：MIUI 8 一路到 HyperOS
    // 都是这个组件，多年未变。
    Vendor(
        keys = setOf("xiaomi", "redmi", "poco"),
        components = listOf(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
    ),

    // 一加。OxygenOS 11 及以前用自家 com.oneplus.security 的「应用自启动」，
    // 之后并入 ColorOS，因此把 OPPO 那一套也接在后面。
    Vendor(
        keys = setOf("oneplus"),
        components = listOf(
            "com.oneplus.security/.chainlaunch.view.ChainLaunchAppListActivity",
            "com.coloros.safecenter/.startupapp.StartupAppListActivity",
            "com.coloros.safecenter/.permission.startup.StartupAppListActivity",
        ),
    ),

    // OPPO / realme（ColorOS）。注意：这些组件多数被签名级权限
    // oppo.permission.OPPO_COMPONENT_SAFE 保护，第三方应用拿不到，
    // 在部分 ColorOS 版本上会直接抛 SecurityException —— 所以回退链在这里格外重要。
    Vendor(
        keys = setOf("oppo", "realme"),
        components = listOf(
            // ColorOS 7 及以后
            "com.coloros.safecenter/.startupapp.StartupAppListActivity",
            // ColorOS 3 ~ 6
            "com.coloros.safecenter/.permission.startup.StartupAppListActivity",
            // ColorOS 2.x，那时安全中心还叫 com.oppo.safe
            "com.oppo.safe/.permission.startup.StartupAppListActivity",
            // 权限隐私总入口，自启动在其下一级
            "com.coloros.safecenter/com.coloros.privacypermissionsentry.PermissionTopActivity",
            // 耗电管理（「允许后台运行」在这里），与自启动是两套开关，两个都要开
            "com.coloros.oppoguardelf/com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
        ),
    ),

    // vivo / iQOO（OriginOS / Funtouch）。自启动与「后台高耗电」是两个独立开关，
    // 只开一个仍会被清理，所以两个页面都排进候选。
    Vendor(
        keys = setOf("vivo", "iqoo"),
        components = listOf(
            // Funtouch 9 起 / OriginOS，i 管家里的「后台高耗电」
            "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
            // Funtouch 4 ~ 9
            "com.iqoo.secure/.ui.phoneoptimize.BgStartUpManager",
            // i 管家权限管理主页
            "com.iqoo.secure/.safeguard.PurviewTabActivity",
        ),
    ),

    // 魅族（Flyme）。「后台管理」而非严格意义的自启动，但起作用的是同一套管控。
    Vendor(
        keys = setOf("meizu"),
        components = listOf(
            // Flyme 7.x
            "com.meizu.safe/.permission.SmartBGActivity",
            "com.meizu.safe/.permission.PermissionMainActivity",
        ),
    ),

    // 三星（One UI）。One UI 没有「自启动」白名单，最接近的是「未使用应用置于休眠」
    // 与电池用量页；国行 sm_cn 才有「自动运行应用」。跳过去多少能救一点，
    // 但比国产 ROM 的自启动名单弱得多。
    Vendor(
        keys = setOf("samsung"),
        components = listOf(
            // 国行「自动运行应用」
            "com.samsung.android.sm_cn/com.samsung.android.sm.ui.ram.AutoRunActivity",
            "com.samsung.android.sm/com.samsung.android.sm.ui.ram.AutoRunActivity",
            // 国际版设备维护里的电池页，「深度睡眠应用」在其下
            "com.samsung.android.lool/com.samsung.android.sm.battery.ui.BatteryActivity",
            "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.samsung.android.lool/com.samsung.android.sm.battery.ui.usage.CheckableAppListActivity",
        ),
    ),

    // 联想 / ZUK。ZUI 的「纯净后台」。可靠性一般，主要靠后面的管家首页回退兜住。
    Vendor(
        keys = setOf("lenovo", "zuk"),
        components = listOf(
            "com.lenovo.security/.purebackground.PureBackgroundActivity",
        ),
    ),

    // 金立（Amigo OS）。部分机型 MANUFACTURER 报的是单个字母 "F"，一并收进 keys。
    Vendor(
        keys = setOf("gionee", "f"),
        components = listOf(
            "com.gionee.softmanager/.MainActivity",
        ),
    ),

    // 酷派 / 宇龙（CoolUI）。两个包名对应不同世代的安全中心。
    Vendor(
        keys = setOf("coolpad", "yulong"),
        components = listOf(
            "com.yulong.android.coolsafe/.ui.activity.autorun.AutoRunListActivity",
            // 类名确实是全小写的 tabbarmain，不是笔误，别去「修正」它
            "com.yulong.android.security/com.yulong.android.seccenter.tabbarmain",
        ),
    ),

    // 乐视（EUI）。机器基本绝迹了，但候选表里多一条不花钱。
    Vendor(
        keys = setOf("letv", "leeco"),
        components = listOf(
            "com.letv.android.letvsafe/.AutobootManageActivity",
            "com.letv.android.letvsafe/.BackgroundAppManageActivity",
        ),
    ),

    // 中兴（MiFavor）
    Vendor(
        keys = setOf("zte", "nubia"),
        components = listOf(
            "com.zte.heartyservice/.autorun.AppAutoRunManager",
        ),
    ),

    // 华硕（ZenUI）
    Vendor(
        keys = setOf("asus"),
        components = listOf(
            "com.asus.mobilemanager/.autostart.AutoStartActivity",
            "com.asus.mobilemanager/.powersaver.PowerSaverSettings",
            "com.asus.mobilemanager/.MainActivity",
        ),
    ),
)

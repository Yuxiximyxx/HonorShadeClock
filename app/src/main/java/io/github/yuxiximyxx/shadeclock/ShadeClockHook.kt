package io.github.yuxiximyxx.shadeclock

import android.content.res.XResources
import android.util.TypedValue
import android.view.View
import de.robv.android.xposed.IXposedHookInitPackageResources
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_InitPackageResources
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * 正式版入口：通知中心时钟居中 + 放大（v1.1.0 起带自定义设置）。
 *
 * 两件互相独立的事（可行性依据见仓库 HEADER_ANALYSIS.md）：
 * 1. 放大（资源替换）：handleInitPackageResources 里把 SystemUI 的
 *    dimen/notif_center_header_clock_text_size（默认 40dp）、
 *    dimen/notif_center_header_clock_date_size（默认 14dp）替换为
 *    设置里的字号。头部 composable 每次重组都现读
 *    getDimensionPixelSize，替换直接生效。DimensionReplacement 的
 *    单位按 API 82 签名 (float, int unit) 传
 *    TypedValue.COMPLEX_UNIT_DIP，即 dp 语义。
 * 2. 居中（MeasurePolicy 替换）：见 PolicyCentering——带结构自检与
 *    异常回退地复刻 DateAndTime 内部 MeasurePolicy 的 measure，
 *    只给时钟/日期在盒内的摆放 x 整体加居中偏移（v1.1.2 起的新
 *    路线；v1.0–v1.1.1 在外层 Lambda9 挪盒子的路线已撤销，稳态下
 *    盒子本身满宽、挪盒子居中不了）。仅当设置 center_clock=true
 *    时安装；false 时不装 hook，摆放完全走系统原逻辑。
 *
 * 设置来自模块 App 的 SharedPreferences（名 settings），经
 * XSharedPreferences 在 SystemUI 进程启动时读取一次（改设置后强停
 * 系统界面生效）；读不到时用默认值（居中开、时钟 54dp、日期 16dp）。
 *
 * 诊断只保留每进程一次的字号验读回、设置读数与 hook 安装结果，
 * 仍写 /sdcard/Download/shadeclock_probe.log（+ LSPosed 日志）。
 *
 * 作用域只有 com.android.systemui（见 AndroidManifest 的 xposedscope）。
 */
class ShadeClockHook : IXposedHookLoadPackage, IXposedHookInitPackageResources {

    companion object {
        private const val TAG = "[ShadeClock]"
        private const val TARGET_PACKAGE = "com.android.systemui"
        private val LOG_FILE = File("/sdcard/Download/shadeclock_probe.log")

        private const val CLOCK_DIMEN = "notif_center_header_clock_text_size"
        private const val DATE_DIMEN = "notif_center_header_clock_date_size"

        private const val MODULE_PACKAGE = "io.github.yuxiximyxx.shadeclock"
        private const val PREFS_NAME = "settings"
        private const val KEY_CENTER_CLOCK = "center_clock"
        private const val KEY_CLOCK_SIZE = "clock_size_dp"
        private const val KEY_DATE_SIZE = "date_size_dp"
        private const val DEFAULT_CENTER_CLOCK = true
        private const val DEFAULT_CLOCK_DP = 54
        private const val DEFAULT_DATE_DP = 16
        private const val CLOCK_DP_MIN = 36
        private const val CLOCK_DP_MAX = 68
        private const val DATE_DP_MIN = 12
        private const val DATE_DP_MAX = 24

        private const val HEADER_CLASS_NAME =
            "com.android.systemui.statusbar.notification.header.HnNotificationCenterHeader"

        /** 诊断输出：XposedBridge.log + 文件双通道；写文件失败不影响宿主。 */
        fun diag(message: String) {
            try {
                XposedBridge.log("$TAG $message")
            } catch (t: Throwable) {
                // ignore
            }
            try {
                synchronized(LOG_FILE) {
                    LOG_FILE.appendText("$TAG $message\n")
                }
            } catch (t: Throwable) {
                // 写失败不影响 hook
            }
        }
    }

    /** 一次进程生命周期内读取到的设置快照。 */
    private data class ModuleSettings(
        val centerClock: Boolean,
        val clockDp: Float,
        val dateDp: Float,
    )

    @Volatile
    private var cachedSettings: ModuleSettings? = null

    private val dimenDiagDone = AtomicBoolean(false)

    /**
     * 读取设置（每进程只读一次并缓存）。XSharedPreferences 读不到
     * （文件不可读/尚不存在）时整体回退默认值，并各写一行诊断。
     */
    private fun settings(): ModuleSettings {
        cachedSettings?.let { return it }
        val loaded = try {
            val prefs = XSharedPreferences(MODULE_PACKAGE, PREFS_NAME)
            val s = ModuleSettings(
                centerClock = prefs.getBoolean(KEY_CENTER_CLOCK, DEFAULT_CENTER_CLOCK),
                clockDp = prefs.getInt(KEY_CLOCK_SIZE, DEFAULT_CLOCK_DP)
                    .coerceIn(CLOCK_DP_MIN, CLOCK_DP_MAX).toFloat(),
                dateDp = prefs.getInt(KEY_DATE_SIZE, DEFAULT_DATE_DP)
                    .coerceIn(DATE_DP_MIN, DATE_DP_MAX).toFloat(),
            )
            diag(
                "settings loaded: center_clock=${s.centerClock}, " +
                    "clock_size_dp=${s.clockDp.roundToInt()}, date_size_dp=${s.dateDp.roundToInt()}"
            )
            s
        } catch (t: Throwable) {
            diag("settings load failed, using defaults: ${t.javaClass.name}: ${t.message}")
            ModuleSettings(
                DEFAULT_CENTER_CLOCK,
                DEFAULT_CLOCK_DP.toFloat(),
                DEFAULT_DATE_DP.toFloat(),
            )
        }
        cachedSettings = loaded
        return loaded
    }

    // ================= 放大：资源替换 =================

    override fun handleInitPackageResources(
        resparam: XC_InitPackageResources.InitPackageResourcesParam
    ) {
        if (resparam.packageName != TARGET_PACKAGE) return
        val s = settings()
        replaceDimen(resparam.res, CLOCK_DIMEN, s.clockDp)
        replaceDimen(resparam.res, DATE_DIMEN, s.dateDp)
    }

    private fun replaceDimen(res: XResources, name: String, dp: Float) {
        try {
            val id = res.getIdentifier(name, "dimen", TARGET_PACKAGE)
            if (id == 0) {
                diag("dimen replacement skipped: $name not found in $TARGET_PACKAGE resources")
                return
            }
            res.setReplacement(
                TARGET_PACKAGE, "dimen", name,
                XResources.DimensionReplacement(dp, TypedValue.COMPLEX_UNIT_DIP)
            )
            diag("dimen replaced: $name -> ${dp}dp")
        } catch (t: Throwable) {
            diag("dimen replacement failed for $name: ${t.javaClass.name}: ${t.message}")
        }
    }

    // ================= hook 安装 =================

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return
        diag("=== HonorShadeClock v1.1.2 loaded in $TARGET_PACKAGE ===")
        installDimenDiagnostic(lpparam)
        if (settings().centerClock) {
            PolicyCentering.install(lpparam) { message -> diag(message) }
        } else {
            diag("policy centering hook not installed: center_clock=false in settings")
        }
    }

    /**
     * 字号验读回：头部首次 onAttachedToWindow 后读一次两个 dimen 的
     * getDimensionPixelSize，与期望像素（设置 dp x 当前 density）对照
     * 写一行诊断。每个进程只读一次；失败只记一行，不影响其他功能。
     */
    private fun installDimenDiagnostic(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val s = settings()
            val headerClass = XposedHelpers.findClass(HEADER_CLASS_NAME, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                headerClass, "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!dimenDiagDone.compareAndSet(false, true)) return
                        try {
                            val view = param.thisObject as View
                            val res = view.resources
                            val density = res.displayMetrics.density
                            val clockId = res.getIdentifier(CLOCK_DIMEN, "dimen", TARGET_PACKAGE)
                            val dateId = res.getIdentifier(DATE_DIMEN, "dimen", TARGET_PACKAGE)
                            val clockPx =
                                if (clockId != 0) res.getDimensionPixelSize(clockId) else -1
                            val datePx =
                                if (dateId != 0) res.getDimensionPixelSize(dateId) else -1
                            val clockExpected = (s.clockDp * density).roundToInt()
                            val dateExpected = (s.dateDp * density).roundToInt()
                            diag(
                                "dimen check: density=$density " +
                                    "clock=${clockPx}px (expect ${clockExpected}px), " +
                                    "date=${datePx}px (expect ${dateExpected}px)"
                            )
                        } catch (t: Throwable) {
                            diag("dimen check failed: ${t.javaClass.name}: ${t.message}")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            diag("dimen diagnostic hook skipped: ${t.javaClass.name}: ${t.message}")
        }
    }
}

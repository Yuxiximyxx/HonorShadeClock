package io.github.yuxiximyxx.shadeclock

import android.content.res.XResources
import android.util.TypedValue
import android.view.View
import de.robv.android.xposed.IXposedHookInitPackageResources
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_InitPackageResources
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * 正式版 v1.0.0 入口：通知中心时钟居中 + 放大。
 *
 * 两件互相独立的事（可行性依据见仓库 HEADER_ANALYSIS.md）：
 * 1. 放大（资源替换）：handleInitPackageResources 里把 SystemUI 的
 *    dimen/notif_center_header_clock_text_size 40dp -> 54dp、
 *    dimen/notif_center_header_clock_date_size 14dp -> 16dp。
 *    头部 composable 每次重组都现读 getDimensionPixelSize，替换直接生效。
 *    DimensionReplacement 的单位按 API 82 签名 (float, int unit) 传
 *    TypedValue.COMPLEX_UNIT_DIP，即 dp 语义。
 * 2. 居中（摆放 lambda 替换）：见 HeaderCentering——带结构自检与异常
 *    回退地复刻头部 SubcomposeLayout 的摆放逻辑，只改 dateAndTime 的 x。
 *
 * v0.1-v0.3 的探针逻辑（setText hook、整树遍历、图纸测绘）已全部下线；
 * 只保留每进程一次的字号验读回与 hook 安装结果诊断，仍写
 * /sdcard/Download/shadeclock_probe.log（+ LSPosed 日志）。
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
        private const val CLOCK_DP = 54f
        private const val DATE_DP = 16f

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

    private val dimenDiagDone = AtomicBoolean(false)

    // ================= 放大：资源替换 =================

    override fun handleInitPackageResources(
        resparam: XC_InitPackageResources.InitPackageResourcesParam
    ) {
        if (resparam.packageName != TARGET_PACKAGE) return
        replaceDimen(resparam.res, CLOCK_DIMEN, CLOCK_DP)
        replaceDimen(resparam.res, DATE_DIMEN, DATE_DP)
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
        diag("=== HonorShadeClock v1.0.0 loaded in $TARGET_PACKAGE ===")
        installDimenDiagnostic(lpparam)
        HeaderCentering.install(lpparam) { message -> diag(message) }
    }

    /**
     * 字号验读回：头部首次 onAttachedToWindow 后读一次两个 dimen 的
     * getDimensionPixelSize，与期望像素（dp x 当前 density）对照写一行
     * 诊断。每个进程只读一次；失败只记一行，不影响其他功能。
     */
    private fun installDimenDiagnostic(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
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
                            val clockExpected = (CLOCK_DP * density).roundToInt()
                            val dateExpected = (DATE_DP * density).roundToInt()
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

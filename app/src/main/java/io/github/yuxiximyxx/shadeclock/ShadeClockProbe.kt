package io.github.yuxiximyxx.shadeclock

import android.view.View
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

/**
 * 探针版（v0.1.0）：只定位、不改样式。
 *
 * 目标：找到荣耀 MagicOS 11 通知中心（notification shade）顶部时钟控件的
 * 运行时视图结构——类名、id/资源名、字号、父链各层的类名/尺寸/LayoutParams，
 * 为正式版「居中大时钟」改造提供落点依据。
 *
 * 做法：hook SystemUI 进程里 TextView.setText 的两个 CharSequence 重载，
 * 文本命中时钟格式（HH:mm 或 HH:mm:ss）的实例只 dump 一次。
 * 全程只读，绝不修改任何视图属性。
 */
class ShadeClockProbe : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "[ShadeClockProbe]"
        private const val TARGET_PACKAGE = "com.android.systemui"
        private const val MAX_PARENT_LEVELS = 8
        private val LOG_FILE = File("/sdcard/Download/shadeclock_probe.log")
        private val TIME_REGEX = Regex("""^([01]?\d|2[0-3]):[0-5]\d(:[0-5]\d)?$""")
    }

    /** 已 dump 过的 TextView 实例（弱引用，避免长期持有视图） */
    private val dumped: MutableSet<TextView> =
        Collections.newSetFromMap(WeakHashMap<TextView, Boolean>())

    private val logLock = Any()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val tv = param.thisObject as? TextView ?: return
                    val text = tv.text?.toString() ?: return
                    if (!TIME_REGEX.matches(text)) return
                    synchronized(dumped) {
                        if (!dumped.add(tv)) return
                    }
                    dumpClock(tv, text)
                } catch (t: Throwable) {
                    probeLog("dump failed: ${t.javaClass.name}: ${t.message}")
                }
            }
        }

        XposedHelpers.findAndHookMethod(
            TextView::class.java, "setText",
            CharSequence::class.java, hook
        )
        XposedHelpers.findAndHookMethod(
            TextView::class.java, "setText",
            CharSequence::class.java, TextView.BufferType::class.java, hook
        )
        probeLog("probe installed in $TARGET_PACKAGE (TextView.setText hooked)")
    }

    private fun dumpClock(tv: TextView, text: String) {
        val sb = StringBuilder()
        sb.appendLine("=== clock TextView hit ===")
        sb.appendLine("class=${tv.javaClass.name}")
        sb.appendLine(idDesc(tv))
        val px = tv.textSize
        val scaledDensity = tv.resources?.displayMetrics?.scaledDensity ?: 0f
        val sp = if (scaledDensity > 0f) px / scaledDensity else 0f
        sb.appendLine("textSize=${px}px (${sp}sp)")
        sb.appendLine("text=\"$text\"")

        var current: View = tv
        var level = 0
        while (level < MAX_PARENT_LEVELS) {
            val parent = current.parent as? View ?: break
            level++
            sb.appendLine(
                "parent[$level] class=${parent.javaClass.name} " +
                    "${idDesc(parent)} " +
                    "size=${parent.width}x${parent.height}px " +
                    "layoutParams=${parent.layoutParams?.javaClass?.name ?: "null"}"
            )
            current = parent
        }
        sb.appendLine("----------------------------------------")
        probeLog(sb.toString().trimEnd())
    }

    private fun idDesc(view: View): String {
        val id = view.id
        if (id == View.NO_ID) return "id=no-id"
        val entryName = try {
            view.resources?.getResourceEntryName(id) ?: "no-id"
        } catch (t: Throwable) {
            "no-id"
        }
        return "id=0x${Integer.toHexString(id)} name=$entryName"
    }

    /** 双通道输出：XposedBridge.log + 追加写文件；写文件失败不影响 hook。 */
    private fun probeLog(message: String) {
        try {
            XposedBridge.log("$TAG $message")
        } catch (t: Throwable) {
            // ignore
        }
        synchronized(logLock) {
            try {
                LOG_FILE.appendText("$TAG $message\n")
            } catch (t: Throwable) {
                // 写失败不影响 hook
            }
        }
    }
}

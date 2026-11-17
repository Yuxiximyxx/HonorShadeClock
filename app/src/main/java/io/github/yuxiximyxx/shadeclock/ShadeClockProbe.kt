package io.github.yuxiximyxx.shadeclock

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.ArrayDeque
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 探针版。
 *
 * v0.1.0：hook SystemUI 进程里 TextView.setText 的两个 CharSequence 重载，
 * 文本命中时钟格式（HH:mm 或 HH:mm:ss）的实例只 dump 一次（类名、id、字号、父链）。
 * 真机回传结论：通知中心头部大时钟不是靠 setText 更新的普通文本控件
 * （只抓到灵动胶囊 Chronometer、媒体进度、通知时间戳），故 v0.2.0 换侦察方式。
 *
 * v0.2.0：整棵视图树遍历。hook WindowManagerGlobal.addView 收集窗口根视图，
 * 再用 getRootViews() 反射兜底补全；主线程 Handler 每 5 秒一轮、最多 36 轮
 * （有新根视图加入时重置轮次），迭代式（显式栈）遍历每棵树，直接读控件
 * 当前文本/类名/id/尺寸/位置，不依赖 setText。命中条件：TextView 且文本
 * 长度 1..40，或类名/id 名含 clock/time/date/shade/header/panel/compose。
 * 以「实例 identityHashCode + 文本」去重，全程只记一次；单轮上限 800 条。
 *
 * v0.2.1 热修：真机日志显示 getRootViews() 在 MagicOS 上不存在
 * （NoSuchMethodError），且该异常被当成致命错误停掉了全部轮次、一轮都没
 * 扫成。本版兜底补全改为读 WindowManagerGlobal 的 mRoots 字段
 * （ArrayList<ViewRootImpl>），逐个取 ViewRootImpl.mView；并做错误隔离：
 * 兜底补全失败只记一行、继续用已收集的根遍历；每个根视图的遍历各自
 * try-catch，单个根失败不影响其他根与后续轮次；只有调度本身连续出错
 * 才停。每轮标题行后加一行根视图总数统计，方便判读。
 *
 * 安全红线：遍历与记录全部 try-catch 兜底，绝不能让 SystemUI 崩溃。
 * 全程只读，绝不修改任何视图属性。
 */
class ShadeClockProbe : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "[ShadeClockProbe]"
        private const val TARGET_PACKAGE = "com.android.systemui"
        private const val MAX_PARENT_LEVELS = 8
        private val LOG_FILE = File("/sdcard/Download/shadeclock_probe.log")
        private val TIME_REGEX = Regex("""^([01]?\d|2[0-3]):[0-5]\d(:[0-5]\d)?$""")

        // ---- v2 整树遍历参数 ----
        private const val PASS_INTERVAL_MS = 5_000L
        private const val MAX_PASSES = 36
        private const val MAX_HITS_PER_PASS = 800
        /** 调度本身连续出错达到此数才允许停掉全部轮次 */
        private const val MAX_CONSECUTIVE_SCHEDULER_ERRORS = 3
        private val KEYWORDS = listOf("clock", "time", "date", "shade", "header", "panel", "compose")
    }

    /** 已 dump 过的 TextView 实例（弱引用，避免长期持有视图） */
    private val dumped: MutableSet<TextView> =
        Collections.newSetFromMap(WeakHashMap<TextView, Boolean>())

    private val logLock = Any()

    // ---- v2 状态 ----
    /** 已发现的窗口根视图（弱引用集合，线程安全） */
    private val roots: MutableSet<View> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<View, Boolean>()))

    /** v2 去重键：identityHashCode + 文本 */
    private val seenNodes: MutableSet<String> =
        Collections.synchronizedSet(HashSet<String>())

    private val passCount = AtomicInteger(0)

    /** 调度层连续出错次数（兜底补全与单根遍历的错误已各自隔离，不计入） */
    private var consecutiveSchedulerErrors = 0

    @Volatile
    private var stopped = false

    private var windowManagerGlobalClass: Class<*>? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return

        // ---------- v0.1.0：setText hook（保留不动） ----------
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

        // ---------- v0.2.0：整树遍历探针（v0.2.1 热修兜底链路） ----------
        fileOnly("$TAG === HonorShadeClock probe v0.2.1 started (tree traversal) ===")
        installRootDiscovery(lpparam)
        startTraversalScheduler()
    }

    // ================= v0.1.0 dump 逻辑（原样保留） =================

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
        fileOnly("$TAG $message")
    }

    /** 只写文件，不刷 LSPosed 日志（v2 节点明细用）。 */
    private fun fileOnly(text: String) {
        synchronized(logLock) {
            try {
                LOG_FILE.appendText(text + "\n")
            } catch (t: Throwable) {
                // 写失败不影响 hook
            }
        }
    }

    // ================= v0.2.0 整树遍历 =================

    /** hook WindowManagerGlobal.addView（全部重载），记录新增的窗口根视图。 */
    private fun installRootDiscovery(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(
                "android.view.WindowManagerGlobal", lpparam.classLoader
            )
            windowManagerGlobalClass = clazz
            XposedBridge.hookAllMethods(clazz, "addView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val root = param.args.firstOrNull() as? View ?: return
                        if (roots.add(root)) {
                            // 有新根视图加入：重置轮次计数
                            passCount.set(0)
                        }
                    } catch (t: Throwable) {
                        // 纯记录失败不影响宿主
                    }
                }
            })
            fileOnly("$TAG v2 root discovery installed (WindowManagerGlobal.addView hooked)")
        } catch (t: Throwable) {
            fileOnly("$TAG v2 root discovery install failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    /**
     * 兜底补全根视图：反射拿 WindowManagerGlobal 单例，读其 mRoots 字段
     * （ArrayList<ViewRootImpl>），逐个读 ViewRootImpl 的 mView 字段得到根视图，
     * 合并进已收集的根集合。返回本轮是否有新根加入。
     *
     * v0.2.1 起不再调 getRootViews()——该方法在 MagicOS 上不存在
     * （NoSuchMethodError）。本函数由调度层单独 try-catch 调用：失败只记
     * 一行日志、继续用 addView 已收集的根遍历，绝不中止轮次。
     */
    private fun mergeFallbackRoots(): Boolean {
        val clazz = windowManagerGlobalClass ?: return false
        val instance = XposedHelpers.callStaticMethod(clazz, "getInstance") ?: return false
        val mRoots = XposedHelpers.getObjectField(instance, "mRoots") as? Iterable<*> ?: return false
        var addedNew = false
        for (rootImpl in mRoots) {
            if (rootImpl == null) continue
            val view = try {
                XposedHelpers.getObjectField(rootImpl, "mView") as? View
            } catch (t: Throwable) {
                null
            } ?: continue
            if (roots.add(view)) addedNew = true
        }
        return addedNew
    }

    /**
     * 主线程 Handler 调度：每 5 秒一轮，最多 36 轮；新根加入重置计数。
     *
     * 错误隔离（v0.2.1）：兜底补全单独 try-catch，失败只记一行、继续用已
     * 收集的根遍历；单轮遍历内部再按根隔离。只有调度本身连续出错达到
     * MAX_CONSECUTIVE_SCHEDULER_ERRORS 次才停掉全部轮次。
     */
    private fun startTraversalScheduler() {
        try {
            val handler = Handler(Looper.getMainLooper())
            val task = object : Runnable {
                override fun run() {
                    if (stopped) return
                    // 兜底补全：单独隔离，失败不中止轮次
                    try {
                        if (mergeFallbackRoots()) {
                            passCount.set(0)
                        }
                    } catch (t: Throwable) {
                        fileOnly(
                            "$TAG v2 fallback root merge failed (ignored, " +
                                "continuing with collected roots): " +
                                "${t.javaClass.name}: ${t.message}"
                        )
                    }
                    var passFailed = false
                    try {
                        if (passCount.get() < MAX_PASSES) {
                            runPass(passCount.incrementAndGet())
                        }
                    } catch (t: Throwable) {
                        passFailed = true
                        consecutiveSchedulerErrors++
                        fileOnly(
                            "$TAG v2 scheduler error " +
                                "($consecutiveSchedulerErrors consecutive): " +
                                "${t.javaClass.name}: ${t.message}"
                        )
                    }
                    if (!passFailed) {
                        consecutiveSchedulerErrors = 0
                    }
                    if (stopped) return
                    if (consecutiveSchedulerErrors >= MAX_CONSECUTIVE_SCHEDULER_ERRORS) {
                        fileOnly(
                            "$TAG probe v2 stopped after " +
                                "$consecutiveSchedulerErrors consecutive scheduler errors"
                        )
                        stopped = true
                        return
                    }
                    try {
                        handler.postDelayed(this, PASS_INTERVAL_MS)
                    } catch (t: Throwable) {
                        stopped = true
                    }
                }
            }
            handler.postDelayed(task, PASS_INTERVAL_MS)
            fileOnly("$TAG v2 traversal scheduler started (every ${PASS_INTERVAL_MS}ms, max $MAX_PASSES passes)")
        } catch (t: Throwable) {
            fileOnly("$TAG v2 scheduler start failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    /**
     * 一轮遍历：对每个根视图做迭代式全树遍历（显式栈，禁止递归）。
     * 每个根各自 try-catch（v0.2.1 错误隔离）：单个根遍历失败只记一行，
     * 不影响其他根与后续轮次，本函数自身不因遍历异常向上抛。
     */
    private fun runPass(pass: Int) {
        val header = "=== probe v2 pass $pass ==="
        try {
            XposedBridge.log("$TAG $header")
        } catch (t: Throwable) {
            // ignore
        }
        val lines = StringBuilder()
        lines.appendLine("$TAG $header")
        val rootsSnapshot = synchronized(roots) { roots.toList() }
        lines.appendLine("$TAG pass $pass roots total=${rootsSnapshot.size}")
        var hits = 0
        var capReached = false
        for (root in rootsSnapshot) {
            if (capReached) break
            try {
                val stack = ArrayDeque<Pair<View, Int>>()
                stack.push(root to 0)
                while (!stack.isEmpty()) {
                    val (view, depth) = stack.pop()
                    if (isHit(view)) {
                        val text = (view as? TextView)?.text?.toString()
                        val key = System.identityHashCode(view).toString() + "|" + (text ?: "")
                        if (seenNodes.add(key)) {
                            lines.appendLine(describeNode(pass, depth, view, text))
                            hits++
                            if (hits >= MAX_HITS_PER_PASS) {
                                lines.appendLine("$TAG pass $pass hit cap $MAX_HITS_PER_PASS, pass truncated")
                                capReached = true
                                break
                            }
                        }
                    }
                    if (view is ViewGroup) {
                        val childCount = view.childCount
                        var i = childCount - 1
                        while (i >= 0) {
                            val child = view.getChildAt(i)
                            if (child != null) {
                                stack.push(child to depth + 1)
                            }
                            i--
                        }
                    }
                }
            } catch (t: Throwable) {
                lines.appendLine(
                    "$TAG pass $pass root ${root.javaClass.name} traversal failed " +
                        "(ignored): ${t.javaClass.name}: ${t.message}"
                )
            }
        }
        lines.appendLine("$TAG pass $pass done, hits=$hits")
        fileOnly(lines.toString().trimEnd())
    }

    /** 命中判定：(a) TextView 且文本长度 1..40；(b) 类名或 id 名含关键词（大小写不敏感）。 */
    private fun isHit(view: View): Boolean {
        if (view is TextView) {
            val len = view.text?.length ?: 0
            if (len in 1..40) return true
        }
        val classLower = view.javaClass.name.lowercase()
        for (kw in KEYWORDS) {
            if (kw in classLower) return true
        }
        val idLower = idEntryName(view).lowercase()
        if (idLower != "no-id") {
            for (kw in KEYWORDS) {
                if (kw in idLower) return true
            }
        }
        return false
    }

    private fun describeNode(pass: Int, depth: Int, view: View, text: String?): String {
        val loc = IntArray(2)
        try {
            view.getLocationOnScreen(loc)
        } catch (t: Throwable) {
            // 位置取不到时保持 0
        }
        val visibility = when (view.visibility) {
            View.VISIBLE -> "VISIBLE"
            View.INVISIBLE -> "INVISIBLE"
            View.GONE -> "GONE"
            else -> view.visibility.toString()
        }
        val sb = StringBuilder()
        sb.append("[v2] pass=").append(pass)
        sb.append(" depth=").append(depth)
        sb.append(" class=").append(view.javaClass.name)
        sb.append(" id=").append(idEntryName(view))
        if (view is TextView) {
            sb.append(" text=\"").append(escapeText(text ?: "")).append('"')
        }
        sb.append(" size=").append(view.width).append('x').append(view.height).append("px")
        sb.append(" visibility=").append(visibility)
        sb.append(" loc=(").append(loc[0]).append(',').append(loc[1]).append(')')
        return sb.toString()
    }

    private fun idEntryName(view: View): String {
        val id = view.id
        if (id == View.NO_ID) return "no-id"
        return try {
            view.resources?.getResourceEntryName(id) ?: "no-id"
        } catch (t: Throwable) {
            "no-id"
        }
    }

    private fun escapeText(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\"", "\\\"")
    }
}

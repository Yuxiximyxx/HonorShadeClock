package io.github.yuxiximyxx.shadeclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.yuxiximyxx.shadeclock.theme.RenderEnv
import io.github.yuxiximyxx.shadeclock.theme.ThemeRenderer
import io.github.yuxiximyxx.shadeclock.theme.WeatherSource
import java.util.Calendar
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 方案 B 覆盖层（v2.0.1）：在 SystemUI 里定位通知中心头部
 * HnNotificationCenterHeader，隐藏其 Compose 内容子视图，把头部
 * 高度改为覆盖层高度，并按选中的荣耀锁屏主题
 * （/sdcard/Download/HonorShadeClock/themes/<id>/）渲染。
 *
 * 挂载方式（v2.0.1 改）：头部继承 BasicComposeView，禁止 addView
 * （v2.0.0 真机实证 UnsupportedOperationException），故覆盖层不再
 * 加进头部内部，而是**同级覆盖**：加到头部的父容器（实测为
 * HnColumeContainer，FrameLayout 子类）里、z 序在头部之后，位置
 * 尺寸与头部对齐；父容器若同样拒绝 addView 则逐级向上找第一个
 * 可加子视图的 ViewGroup 祖先。头部本体保留占位（内容隐藏、高度
 * 改为覆盖层目标高度），布局流仍由头部高度驱动、通知被推到下面。
 * 每秒 tick 把覆盖层 bounds 与头部重新对齐一次。
 * 右上角复刻铃铛（点击反射调用宿主
 * HwNotificationUtils.getManageNotificationsIntent()，与原
 * ViewModel.onClickNotifMgeBtn 的主路径一致；失败退化为系统
 * 通知设置页。原铃铛在有前台服务包时的弹窗分支不复刻）。
 *
 * 设置经 StampStore 的 stamp.txt 跨进程传递；覆盖层挂载时与每秒
 * tick 比对戳记，变了就重解析重载——切换主题免强停。
 * 全路径 try-catch：构建/渲染任何异常都卸下覆盖层、恢复原头部、
 * 写日志，绝不让 SystemUI 崩；同进程连续失败 8 次后本进程停用，
 * 首次成功挂载后失败计数归零。
 */
object HeaderOverlay {

    private const val HEADER_CLASS_NAME =
        "com.android.systemui.statusbar.notification.header.HnNotificationCenterHeader"
    private const val HW_UTILS_CLASS =
        "com.android.systemui.statusbar.notification.HwNotificationUtils"
    private const val WRAPPER_MARKER = "shadeclock_overlay_wrapper"
    private const val MAX_FAILURES = 8

    private lateinit var logFn: (String) -> Unit
    private val states = WeakHashMap<View, State>()
    @Volatile
    private var processFailureCount = 0
    @Volatile
    private var bellClickLogCount = 0

    private class State(val header: ViewGroup) {
        var wrapper: FrameLayout? = null
        var mountParent: ViewGroup? = null
        var mountBounds: IntArray? = null
        var themeView: ThemeView? = null
        var bell: ImageButton? = null
        var bellDrawableSet = false
        var originalHeight: Int = 0
        var appliedStamp: StampStore.Stamp? = null
        var loadedThemeId: String? = null
        var stampMtimeSeen: Long = -1L
        val renderer = ThemeRenderer { message -> logFn(message) }
        val handler = Handler(Looper.getMainLooper())
        var ticker: Runnable? = null
        var batteryLevel: Int = 100
        var batteryState: Int = 0
        var batteryReceiver: BroadcastReceiver? = null
        var mountLogged = false
    }

    /** 自绘层：每帧按当前环境（时间/电量/天气）让渲染器画一遍。 */
    private class ThemeView(
        context: Context,
        private val envProvider: () -> RenderEnv?,
        private val renderer: ThemeRenderer,
        private val onContentHeightChanged: () -> Unit,
        private val onDrawError: (Throwable) -> Unit,
    ) : View(context) {

        private var lastContentBottom = -1f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            try {
                val env = envProvider() ?: return
                renderer.draw(canvas, env)
                val cb = renderer.contentBottomPx
                if (abs(cb - lastContentBottom) > 2f) {
                    lastContentBottom = cb
                    post { onContentHeightChanged() }
                }
            } catch (t: Throwable) {
                onDrawError(t)
            }
        }
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam, log: (String) -> Unit) {
        logFn = log
        try {
            val headerClass = XposedHelpers.findClass(HEADER_CLASS_NAME, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                headerClass, "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        (param.thisObject as? ViewGroup)?.let { attach(it) }
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                headerClass, "onConfigurationChanged",
                android.content.res.Configuration::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        (param.thisObject as? ViewGroup)?.let { attach(it) }
                    }
                }
            )
            log("overlay hooks installed (header attach/config)")
        } catch (t: Throwable) {
            log("overlay hook install failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    // ---------- 挂载 ----------

    private fun attach(header: ViewGroup) {
        if (processFailureCount >= MAX_FAILURES) return
        try {
            val stamp = StampStore.readStamp()
            if (stamp == null ||
                stamp.topStyle != StampStore.TOP_STYLE_THEME ||
                stamp.themeId.isEmpty()
            ) {
                teardown(header)
                return
            }
            val themeDir = StampStore.themeDir(stamp.themeId)
            if (!themeDir.isDirectory) {
                logFn("overlay: selected theme dir missing: $themeDir, keeping original header")
                teardown(header)
                return
            }
            val st = synchronized(states) {
                states.getOrPut(header) { State(header) }
            }
            ensureWrapper(header, st)
            // 走到这里说明覆盖层已成功挂到父容器上：失败计数归零
            if (processFailureCount != 0) processFailureCount = 0
            hideComposeChildren(header, st)
            if (st.loadedThemeId != stamp.themeId ||
                st.appliedStamp?.stamp != stamp.stamp
            ) {
                if (!st.renderer.load(themeDir)) {
                    teardown(header)
                    return
                }
                st.loadedThemeId = stamp.themeId
                // 先量一遍内容下缘，跟随模式下首帧前就把高度定对
                buildEnv(st)?.let { env -> st.renderer.measureContentBottom(env) }
            }
            st.appliedStamp = stamp
            st.stampMtimeSeen = StampStore.stampMtime()
            applyHeight(header, st, stamp)
            startTicker(st)
            WeatherSource.start(header.context) { message -> logFn(message) }
            if (!st.mountLogged) {
                st.mountLogged = true
                val b = st.mountBounds
                logFn(
                    "overlay mounted as sibling: parent=${st.mountParent?.javaClass?.name}, " +
                        "bounds=${b?.joinToString(",")}, theme=${stamp.themeId}, " +
                        "heightMode=${stamp.heightMode}"
                )
            }
        } catch (t: Throwable) {
            fail(header, t)
        }
    }

    private fun ensureWrapper(header: ViewGroup, st: State) {
        if (st.wrapper == null) {
            val ctx = header.context
            st.originalHeight = header.layoutParams?.height?.takeIf { it > 0 } ?: 280

            val wrapper = FrameLayout(ctx)
            wrapper.tag = WRAPPER_MARKER
            val themeView = ThemeView(
                ctx,
                envProvider = { buildEnv(st) },
                renderer = st.renderer,
                onContentHeightChanged = {
                    val stamp = st.appliedStamp
                    if (stamp != null && stamp.heightMode == StampStore.HEIGHT_FOLLOW) {
                        applyHeight(header, st, stamp)
                    }
                },
                onDrawError = { t -> fail(header, t) },
            )
            wrapper.addView(
                themeView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            val bell = buildBell(header, st)
            wrapper.addView(bell)
            st.wrapper = wrapper
            st.themeView = themeView
            st.bell = bell

            wrapper.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    registerBatteryReceiver(st)
                    startTicker(st)
                }

                override fun onViewDetachedFromWindow(v: View) {
                    stopTicker(st)
                    unregisterBatteryReceiver(st)
                }
            })
        }
        mountOverlay(header, st)
    }

    /**
     * 同级覆盖挂载：把 wrapper 加到头部的父容器（逐级向上找第一个
     * 不拒绝 addView 的 ViewGroup），并与头部对齐。已挂载时只做对齐。
     */
    private fun mountOverlay(header: ViewGroup, st: State) {
        val wrapper = st.wrapper ?: return
        if (wrapper.parent == null) {
            st.mountParent = null
            var candidate = header.parent as? ViewGroup
            var lastError: Throwable? = null
            while (candidate != null) {
                try {
                    candidate.addView(wrapper, siblingLayoutParams(candidate, st))
                    st.mountParent = candidate
                    break
                } catch (t: Throwable) {
                    lastError = t
                    logFn(
                        "overlay: addView rejected by ${candidate.javaClass.name}: " +
                            "${t.javaClass.simpleName}: ${t.message}, trying ancestor"
                    )
                    candidate = candidate.parent as? ViewGroup
                }
            }
            val mounted = st.mountParent
                ?: throw IllegalStateException(
                    "no ancestor ViewGroup accepts addView", lastError
                )
            wrapper.bringToFront()
            logFn("overlay: sibling mount point = ${mounted.javaClass.name}")
        }
        alignOverlay(st)
    }

    /** 头部相对挂载父容器的偏移（逐级累加 left/top）。 */
    private fun offsetIn(header: View, ancestor: ViewGroup): IntArray {
        var x = 0
        var y = 0
        var v: View? = header
        while (v != null && v !== ancestor) {
            x += v.left
            y += v.top
            v = v.parent as? View
        }
        return intArrayOf(x, y)
    }

    private fun siblingLayoutParams(parent: ViewGroup, st: State): ViewGroup.LayoutParams {
        val header = st.header
        val w = if (header.width > 0) header.width else ViewGroup.LayoutParams.MATCH_PARENT
        val h = if (header.height > 0) header.height else 280
        val parent0 = st.mountParent ?: parent
        val off = offsetIn(header, parent0)
        return if (parent is FrameLayout) {
            FrameLayout.LayoutParams(w, h).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                leftMargin = off[0]
                topMargin = off[1]
            }
        } else {
            ViewGroup.MarginLayoutParams(w, h).apply {
                leftMargin = off[0]
                topMargin = off[1]
            }
        }
    }

    /** 把覆盖层 bounds 与头部重新对齐（父容器相对坐标）。 */
    private fun alignOverlay(st: State) {
        val wrapper = st.wrapper ?: return
        val parent = wrapper.parent as? ViewGroup ?: return
        st.mountParent = parent
        val header = st.header
        if (header.width <= 0 || header.height <= 0) return
        val off = offsetIn(header, parent)
        val bounds = intArrayOf(off[0], off[1], header.width, header.height)
        st.mountBounds = bounds
        val lp = wrapper.layoutParams
        if (lp is FrameLayout.LayoutParams) {
            if (lp.width != bounds[2] || lp.height != bounds[3] ||
                lp.leftMargin != bounds[0] || lp.topMargin != bounds[1]
            ) {
                lp.width = bounds[2]
                lp.height = bounds[3]
                lp.leftMargin = bounds[0]
                lp.topMargin = bounds[1]
                wrapper.layoutParams = lp
                parent.requestLayout()
            }
        } else if (lp is ViewGroup.MarginLayoutParams) {
            if (lp.width != bounds[2] || lp.height != bounds[3] ||
                lp.leftMargin != bounds[0] || lp.topMargin != bounds[1]
            ) {
                lp.width = bounds[2]
                lp.height = bounds[3]
                lp.leftMargin = bounds[0]
                lp.topMargin = bounds[1]
                wrapper.layoutParams = lp
                parent.requestLayout()
            }
        }
        // 立即按目标 bounds 摆一次：父容器若有自定义 onLayout 不认
        // margin，也能先到位；tick 每秒再校，父容器后续 layout 若按
        // LayoutParams 摆放则与此一致。
        if (wrapper.left != bounds[0] || wrapper.top != bounds[1] ||
            wrapper.right != bounds[0] + bounds[2] ||
            wrapper.bottom != bounds[1] + bounds[3]
        ) {
            wrapper.layout(bounds[0], bounds[1], bounds[0] + bounds[2], bounds[1] + bounds[3])
        }
    }

    private fun hideComposeChildren(header: ViewGroup, st: State) {
        val wrapper = st.wrapper ?: return
        for (i in 0 until header.childCount) {
            val child = header.getChildAt(i)
            if (child !== wrapper && child.visibility != View.INVISIBLE) {
                child.visibility = View.INVISIBLE
            }
        }
    }

    // ---------- 高度 ----------

    private fun screenHeightPx(header: View): Int {
        val rootH = header.rootView?.height ?: 0
        if (rootH > 0) return rootH
        return header.resources.displayMetrics.heightPixels
    }

    private fun desiredHeight(header: ViewGroup, st: State, stamp: StampStore.Stamp): Int {
        val screenH = screenHeightPx(header)
        return when (stamp.heightMode) {
            StampStore.HEIGHT_THIRD -> screenH / 3
            StampStore.HEIGHT_HALF -> screenH / 2
            else -> {
                val minH = if (st.originalHeight > 0) st.originalHeight else 280
                val content = st.renderer.contentBottomPx.roundToInt()
                content.coerceIn(minH, (screenH * 2 / 3).coerceAtLeast(minH))
            }
        }
    }

    private fun applyHeight(header: ViewGroup, st: State, stamp: StampStore.Stamp) {
        val lp = header.layoutParams ?: return
        val target = desiredHeight(header, st, stamp)
        if (lp.height != target) {
            lp.height = target
            header.layoutParams = lp
            header.requestLayout()
        }
    }

    // ---------- 铃铛 ----------

    private fun buildBell(header: ViewGroup, st: State): ImageButton {
        val ctx = header.context
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).roundToInt()
        val bell = ImageButton(ctx)
        val size = dp(34)
        val lp = FrameLayout.LayoutParams(size, size)
        lp.gravity = android.view.Gravity.TOP or android.view.Gravity.END
        lp.topMargin = dp(34)
        lp.rightMargin = dp(20)
        bell.layoutParams = lp
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x59000000)
        }
        bell.background = circle
        bell.setPadding(dp(7), dp(7), dp(7), dp(7))
        bell.scaleType = ImageView.ScaleType.FIT_CENTER
        bell.contentDescription = "通知管理"
        trySetBellDrawable(header, st, bell)
        bell.setOnClickListener { onBellClick(header) }
        return bell
    }

    /** 铃铛图标：优先克隆头部里真实 NotifMgeBtn 的图，其次按资源名找。 */
    private fun trySetBellDrawable(header: ViewGroup, st: State, bell: ImageButton) {
        if (st.bellDrawableSet) return
        try {
            findRealBellDrawable(header, st.wrapper)?.let {
                bell.setImageDrawable(it)
                st.bellDrawableSet = true
                return
            }
            val res = header.resources
            for (name in listOf("ic_hnnotification_setting_filled", "notif_settings_button")) {
                val id = res.getIdentifier(name, "drawable", "com.android.systemui")
                if (id != 0) {
                    bell.setImageDrawable(res.getDrawable(id, header.context.theme))
                    st.bellDrawableSet = true
                    logFn("overlay bell icon: drawable $name")
                    return
                }
            }
        } catch (t: Throwable) {
            logFn("overlay bell icon resolve failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun findRealBellDrawable(root: View, exclude: View?): Drawable? {
        if (root === exclude) return null
        if (root.javaClass.name.endsWith("NotifMgeBtn")) {
            (root as? ImageView)?.drawable?.let { return cloneDrawable(it) }
            root.background?.let { return cloneDrawable(it) }
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    (root.getChildAt(i) as? ImageView)?.drawable?.let { return cloneDrawable(it) }
                }
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findRealBellDrawable(root.getChildAt(i), exclude)?.let { return it }
            }
        }
        return null
    }

    private fun cloneDrawable(d: Drawable): Drawable =
        d.constantState?.newDrawable() ?: d

    private fun onBellClick(header: ViewGroup) {
        val ctx = header.context
        try {
            val utilsClass = XposedHelpers.findClass(HW_UTILS_CLASS, ctx.classLoader)
            val intent = XposedHelpers.callStaticMethod(
                utilsClass, "getManageNotificationsIntent"
            ) as Intent
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            if (bellClickLogCount < 3) {
                bellClickLogCount++
                logFn("bell click: opened manage-notifications intent from HwNotificationUtils")
            }
        } catch (t: Throwable) {
            logFn(
                "bell click: HwNotificationUtils path failed " +
                    "(${t.javaClass.name}: ${t.message}), fallback to notification settings"
            )
            try {
                ctx.startActivity(
                    Intent("android.settings.NOTIFICATION_SETTINGS")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t2: Throwable) {
                logFn("bell click fallback failed: ${t2.javaClass.name}: ${t2.message}")
            }
        }
    }

    // ---------- 环境（时间/电量/天气） ----------

    private fun buildEnv(st: State): RenderEnv? {
        val tv = st.themeView ?: return null
        val w = if (tv.width > 0) tv.width else st.header.width
        if (w <= 0) return null
        return RenderEnv(
            now = Calendar.getInstance(),
            batteryLevel = st.batteryLevel,
            batteryState = st.batteryState,
            weather = WeatherSource.vars(),
            screenWidthPx = w,
            screenHeightPx = screenHeightPx(st.header),
        )
    }

    private fun registerBatteryReceiver(st: State) {
        if (st.batteryReceiver != null) return
        val ctx = st.header.context
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent != null) updateBattery(st, intent)
            }
        }
        try {
            val sticky = ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (sticky != null) updateBattery(st, sticky)
            st.batteryReceiver = receiver
        } catch (t: Throwable) {
            logFn("battery receiver register failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun unregisterBatteryReceiver(st: State) {
        val receiver = st.batteryReceiver ?: return
        st.batteryReceiver = null
        try {
            st.header.context.unregisterReceiver(receiver)
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun updateBattery(st: State, intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) {
            st.batteryLevel = (level * 100 / scale).coerceIn(0, 100)
        }
        st.batteryState = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> 1
            BatteryManager.BATTERY_STATUS_FULL -> 3
            else -> 0
        }
    }

    // ---------- tick ----------

    private fun startTicker(st: State) {
        if (st.ticker != null) return
        val runnable = object : Runnable {
            override fun run() {
                try {
                    val wrapper = st.wrapper
                    if (wrapper == null) {
                        st.ticker = null
                        return
                    }
                    if (wrapper.parent == null) {
                        // 覆盖层被摘下：尝试重新同级挂载
                        mountOverlay(st.header, st)
                    } else if (!wrapper.isAttachedToWindow) {
                        st.ticker = null
                        return
                    }
                    // 戳记变化 → 重新走挂载流程（含切回原头部的情况）
                    val mtime = StampStore.stampMtime()
                    if (st.stampMtimeSeen >= 0 && mtime != st.stampMtimeSeen) {
                        attach(st.header)
                    } else {
                        hideComposeChildren(st.header, st)
                        st.appliedStamp?.let { applyHeight(st.header, st, it) }
                        // 每秒把覆盖层 bounds 与头部重新对齐一次
                        alignOverlay(st)
                        if (!st.bellDrawableSet) {
                            st.bell?.let { trySetBellDrawable(st.header, st, it) }
                        }
                    }
                    WeatherSource.maybeRefresh(st.header.context)
                    st.themeView?.invalidate()
                } catch (t: Throwable) {
                    fail(st.header, t)
                    return
                }
                st.handler.postDelayed(this, 1000L)
            }
        }
        st.ticker = runnable
        st.handler.postDelayed(runnable, 1000L)
    }

    private fun stopTicker(st: State) {
        st.ticker?.let { st.handler.removeCallbacks(it) }
        st.ticker = null
    }

    // ---------- 卸载/回退 ----------

    private fun teardown(header: ViewGroup) {
        val st = synchronized(states) { states.remove(header) } ?: return
        try {
            stopTicker(st)
            unregisterBatteryReceiver(st)
            st.wrapper?.let { wrapper ->
                (wrapper.parent as? ViewGroup)?.removeView(wrapper)
            }
            for (i in 0 until header.childCount) {
                val child = header.getChildAt(i)
                if (child.visibility == View.INVISIBLE) {
                    child.visibility = View.VISIBLE
                }
            }
            val lp = header.layoutParams
            if (lp != null && st.originalHeight > 0 && lp.height != st.originalHeight) {
                lp.height = st.originalHeight
                header.layoutParams = lp
                header.requestLayout()
            }
            logFn("overlay removed, original header restored")
        } catch (t: Throwable) {
            logFn("overlay teardown failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun fail(header: ViewGroup, t: Throwable) {
        processFailureCount++
        logFn(
            "overlay failed (#$processFailureCount), restoring original header: " +
                "${t.javaClass.name}: ${t.message}"
        )
        teardown(header)
    }
}

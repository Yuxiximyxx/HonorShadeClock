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
 *
 * v2.0.2 修「挂载成功但覆盖层不可见」：v2.0.1 真机日志
 * `bounds=null`——attach 发生在头部布局之前（宽高为 0），
 * alignOverlay 直接早退、mountBounds 一直是 null，初始
 * LayoutParams 只能用兜底值，后续只靠 tick 的手动 layout()
 * 校正，与父容器随后的布局 pass 互相覆盖，不可靠。改法：
 * 给头部注册 OnLayoutChangeListener，头部 bounds 一旦有效
 * 或变化，就在本帧布局结束后（post）重新对齐并重绘；
 * alignOverlay 每轮顺带把 wrapper 顶回父容器最前并恢复
 * VISIBLE/alpha=1（防别的子视图后加进来盖住）。tick 前
 * 5 次各打一行 overlay diag（头部/wrapper/ThemeView 的
 * bounds、可见性、onDraw 次数与实绘元素数），供定位。
 * 右上角复刻铃铛（点击反射调用宿主
 * HwNotificationUtils.getManageNotificationsIntent()，与原
 * ViewModel.onClickNotifMgeBtn 的主路径一致；失败退化为系统
 * 通知设置页。原铃铛在有前台服务包时的弹窗分支不复刻）。
 *
 * v2.0.3：①铃铛图标加白色 tint（主题深色背景上原深色图标看不见）；
 * ②锁屏门控——hook 宿主 StatusBarStateControllerImpl.setState
 * 跟踪状态栏状态（反编译核实：0=SHADE、1=KEYGUARD、2=SHADE_LOCKED、
 * 3=FS_USRSW），锁屏侧状态时挂起覆盖层并保持原头部原样，解锁后
 * tick 自动恢复；状态未知时以 KeyguardManager.isKeyguardLocked
 * 兜底。本模块只管解锁后的下拉面板，不管锁屏。
 * ③主题背景图开关（设置页默认关）：关时渲染器跳过文档顺序里
 * 第一个覆盖画布 ≥95% 的 Image（整幅背景图），经 stamp 第 5 行
 * 传递；④通知栈顶同步——头部变高后把 NSSL 的
 * AmbientState.mTopPadding 按「基线 + 高度增量」同步（系统原配
 * 值只认 Compose 场景量出的头部高度、不认 View 层 lp.height），
 * 挂起/卸载时恢复基线（该 tick 同步 v2.0.4 已被写入口拦截取代，
 * 见下方 v2.0.4 说明与栈顶一节注释）。
 *
 * v2.0.4 修 v2.0.3 真机三处：①铃铛锁屏泄漏——挂起只 GONE 了
 * wrapper，但 alignOverlay 的可见性自愈不感知挂起状态，头部
 * 布局监听（锁屏下面板布局必触发）在挂起期把它顶回 VISIBLE；
 * 主题簇在恢复后的原高切片之外画不出来，只剩固定位的铃铛露头。
 * 改：挂起成为硬门控——alignOverlay/mountOverlay 在挂起期直接
 * 保持 wrapper GONE，挂起/恢复日志带 wrapper 与铃铛可见性。
 * ②控制中心页泄漏——通知页与控制中心页共用同一头部，覆盖层在
 * 控制中心页也画。信号取宿主 HwPanelControlImpl
 * .notifyControlCenterPanelChange（状态分发唯一入口、带去重，
 * 与 setState 同构）+ 头部父容器 HnColumeContainer 的镜像字段
 * isControlCenterExpand 轮询兜底；控制中心页与锁屏走同一挂起
 * 路径（suspended + suspendReason 区分原因）。③通知避让改拦截
 * 写入口——hook NotificationPanelView
 * .requestScrollerTopPaddingUpdate(boolean)（mTopPadding 的
 * 系统写入口）在其写完后同调用栈内把值加上头部高度增量再放行，
 * 系统每次更新都自带偏移；tick 只做一次性兜底（识别系统原值/
 * 我们调整过的值，绝不叠加双加），挂起/卸载恢复系统原值。
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

    // 锁屏门控（v2.0.3）：状态值与宿主 StatusBarState 的反编译源码
    // 逐一核对过（0=SHADE、1=KEYGUARD、2=SHADE_LOCKED、3=FS_USRSW）。
    private const val STATE_CONTROLLER_CLASS =
        "com.android.systemui.statusbar.StatusBarStateControllerImpl"
    private const val STATE_SHADE = 0
    private const val STATE_KEYGUARD = 1
    private const val STATE_SHADE_LOCKED = 2
    private const val STATE_FS_USRSW = 3

    // 通知栈顶（v2.0.3 第 6 项 / v2.0.4 改拦截写入口）：类名经反编译核实。
    private const val PANEL_CLASS_NAME =
        "com.android.systemui.statusbar.phone.NotificationPanelView"
    private const val STACK_CLASS_NAME =
        "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout"

    // 控制中心门控（v2.0.4）：类名经反编译核实——HwPanelControlImpl
    // 是页面状态分发者，HnColumeContainer 是头部父容器（镜像字段宿主自维护）。
    private const val PANEL_CONTROL_IMPL_CLASS =
        "com.android.systemui.statusbar.phone.HwPanelControlImpl"
    private const val COLUME_CONTAINER_CLASS =
        "com.android.systemui.statusbar.phone.HnColumeContainer"

    private lateinit var logFn: (String) -> Unit
    private val states = WeakHashMap<View, State>()
    @Volatile
    private var processFailureCount = 0

    /** 宿主状态栏状态（StatusBarStateControllerImpl.setState 跟踪）；-1=尚未知。 */
    @Volatile
    private var statusBarState = -1

    /** 控制中心页是否展开（HwPanelControlImpl 分发 hook 跟踪；字段读取兜底见 isControlCenterActive）。 */
    @Volatile
    private var controlCenterExpand = false
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
        var layoutListener: View.OnLayoutChangeListener? = null
        var diagTicks = 0
        /** 挂起中（锁屏或控制中心页）：覆盖层整组不可见、原头部保持原样，tick 继续跑以便条件解除后自动恢复。 */
        var suspended = false
        /** 挂起原因："keyguard" / "control-center"，日志与恢复判断用。 */
        var suspendReason: String? = null
        /** 通知栈（NSSL）及其 AmbientState 的反射句柄（找到后缓存）。 */
        var stackScroller: View? = null
        var stackAmbient: Any? = null
        /** 写入口拦截跟踪：系统最近一次写入的 topPadding 原值（未加增量）。 */
        var hookSystemTopPadding: Int? = null
        /** 写入口拦截跟踪：我们调整后放行的值；恢复时只认这个值。 */
        var hookAdjustedTopPadding: Int? = null
        /** 写入口调整日志计数（有界，避免刷屏）。 */
        var interceptLogCount = 0
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

        /** 诊断计数：onDraw 被调用次数（v2.0.2 overlay diag 用）。 */
        var drawCalls = 0
            private set

        /** 最近一帧实际绘制的元素数；env 缺失时为 0。 */
        var lastDrawnElements = 0
            private set

        /** 最近一帧是否因 env 缺失（视图/头部尚无宽度）而未绘制。 */
        var lastEnvMissing = false
            private set

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            drawCalls++
            try {
                val env = envProvider()
                if (env == null) {
                    lastEnvMissing = true
                    lastDrawnElements = 0
                    return
                }
                lastEnvMissing = false
                renderer.draw(canvas, env)
                lastDrawnElements = renderer.lastDrawnElements
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
        installKeyguardGate(lpparam, log)
        installControlCenterGate(lpparam, log)
        installStackWriteInterceptor(lpparam, log)
    }

    /**
     * 锁屏门控的信号源（v2.0.3）：hook 宿主
     * StatusBarStateControllerImpl.setState(int)——反编译源码核实它
     * 是状态转换的唯一入口（校验 0..3、同值直接返回、更新 mState 后
     * 通知监听者），after 里记录新状态即可。hook 失败时
     * [isKeyguardActive] 自动退化为 KeyguardManager 轮询。
     */
    private fun installKeyguardGate(
        lpparam: XC_LoadPackage.LoadPackageParam,
        log: (String) -> Unit,
    ) {
        try {
            val stateClass = XposedHelpers.findClass(STATE_CONTROLLER_CLASS, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                stateClass, "setState", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val newState = param.args.getOrNull(0) as? Int ?: return
                        if (newState in 0..3 && newState != statusBarState) {
                            statusBarState = newState
                            log("status bar state -> $newState")
                        }
                    }
                }
            )
            log("keyguard gate hook installed (StatusBarStateControllerImpl.setState)")
        } catch (t: Throwable) {
            log(
                "keyguard gate hook failed, fallback to KeyguardManager polling: " +
                    "${t.javaClass.name}: ${t.message}"
            )
        }
    }

    /**
     * 控制中心门控的信号源（v2.0.4）：hook 宿主 HwPanelControlImpl
     * .notifyControlCenterPanelChange(boolean)——反编译核实它是
     * 控制中心页状态的分发唯一入口（与旧值相同直接返回、更新
     * mIsControlCenterExpand 后通知全部 ControlCenterChangedListener，
     * 与 StatusBarStateControllerImpl.setState 同构），after 里记录
     * 新状态即可。hook 失败时 [isControlCenterActive] 仍可直接读
     * 头部父容器的镜像字段，不影响门控成立。
     */
    private fun installControlCenterGate(
        lpparam: XC_LoadPackage.LoadPackageParam,
        log: (String) -> Unit,
    ) {
        try {
            val implClass = XposedHelpers.findClass(PANEL_CONTROL_IMPL_CLASS, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                implClass, "notifyControlCenterPanelChange",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val z = param.args.getOrNull(0) as? Boolean ?: return
                        if (z != controlCenterExpand) {
                            controlCenterExpand = z
                            log(
                                "panel page -> " +
                                    if (z) "control-center" else "notification-center"
                            )
                        }
                    }
                }
            )
            log("control-center gate hook installed (HwPanelControlImpl.notifyControlCenterPanelChange)")
        } catch (t: Throwable) {
            log(
                "control-center gate hook failed, fallback to HnColumeContainer field polling: " +
                    "${t.javaClass.name}: ${t.message}"
            )
        }
    }

    /**
     * 当前是否处于控制中心页：优先读头部父容器 HnColumeContainer 的
     * 镜像字段 isControlCenterExpand（宿主自己的 ControlCenter 监听
     * 维护、与分发状态严格同步，头部直父就是它）；读不到再用 hook
     * 跟踪值兜底。本模块只管通知中心页，控制中心页激活即挂起。
     */
    private fun isControlCenterActive(header: View): Boolean {
        var v: View? = header
        while (v != null) {
            if (v.javaClass.name == COLUME_CONTAINER_CLASS) {
                try {
                    return XposedHelpers.getBooleanField(v, "isControlCenterExpand")
                } catch (t: Throwable) {
                    break
                }
            }
            v = v.parent as? View
        }
        return controlCenterExpand
    }

    /** 挂起原因：null=可挂载；锁屏优先于控制中心（两者可同时成立）。 */
    private fun suspendReason(header: ViewGroup): String? = when {
        isKeyguardActive(header.context) -> "keyguard"
        isControlCenterActive(header) -> "control-center"
        else -> null
    }

    /**
     * 当前是否处于锁屏态：状态机已知时以它为准（KEYGUARD /
     * SHADE_LOCKED / FS_USRSW 都算锁屏侧——本模块只管解锁后的下拉
     * 面板）；尚未知（进程刚起、还没等到第一次状态转换）时用系统
     * KeyguardManager.isKeyguardLocked 兜底。
     */
    private fun isKeyguardActive(context: Context): Boolean {
        return when (statusBarState) {
            STATE_KEYGUARD, STATE_SHADE_LOCKED, STATE_FS_USRSW -> true
            STATE_SHADE -> false
            else -> try {
                val km = context.getSystemService(Context.KEYGUARD_SERVICE)
                    as? android.app.KeyguardManager
                km?.isKeyguardLocked == true
            } catch (t: Throwable) {
                false
            }
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
            // 页态门控（v2.0.3 锁屏 / v2.0.4 加控制中心）：头部在锁屏
            // 通知区与控制中心页里同样存在，覆盖层挂上去会盖住锁屏
            // 自己的大时钟、压住控制中心瓷砖。本模块只管解锁后下拉
            // 的通知中心页——其余页态不挂覆盖层、不隐藏原头部，只
            // 保持 tick 运行，回到通知中心页后自动恢复挂载。
            val reason = suspendReason(header)
            if (reason != null) {
                suspendOverlay(header, st, reason)
                st.stampMtimeSeen = StampStore.stampMtime()
                startTicker(st)
                return
            }
            if (st.suspended) {
                val prevReason = st.suspendReason
                st.suspended = false
                st.suspendReason = null
                logFn(
                    "overlay resumed: $prevReason cleared " +
                        "(state=$statusBarState, cc=${isControlCenterActive(header)}) " +
                        "wrapperVis=${st.wrapper?.visibility} bellVis=${st.bell?.visibility}"
                )
            }
            // 主题背景图开关（v2.0.3）：stamp 每次挂载/变更时同步给渲染器
            st.renderer.showBackgroundImage = stamp.showBackground
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
        // 对齐时机改到头部布局完成之后：attach 时头部多半还没
        // 布局（宽高 0），监听其 bounds 一旦有效/变化，本帧布局
        // 结束后再对齐一次（v2.0.2，修 bounds=null 不可见）。
        if (st.layoutListener == null) {
            val listener = View.OnLayoutChangeListener {
                    _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left > 0 && bottom - top > 0 &&
                    (left != oldLeft || top != oldTop ||
                        right != oldRight || bottom != oldBottom)
                ) {
                    header.post {
                        if (st.wrapper != null) {
                            alignOverlay(st)
                            ensureStackAdjusted(st)
                            st.themeView?.invalidate()
                        }
                    }
                }
            }
            header.addOnLayoutChangeListener(listener)
            st.layoutListener = listener
        }
        mountOverlay(header, st)
    }

    /**
     * 同级覆盖挂载：把 wrapper 加到头部的父容器（逐级向上找第一个
     * 不拒绝 addView 的 ViewGroup），并与头部对齐。已挂载时只做对齐。
     */
    private fun mountOverlay(header: ViewGroup, st: State) {
        // 挂起期（锁屏/控制中心）绝不挂载：整组保持 GONE（v2.0.4）
        if (st.suspended) {
            st.wrapper?.let { w ->
                if (w.visibility != View.GONE) w.visibility = View.GONE
            }
            return
        }
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
        // 挂起期硬门控（v2.0.4，修铃铛锁屏泄漏）：下方的可见性自愈
        // 不感知挂起状态时，头部布局监听会在锁屏下把已 GONE 的
        // wrapper 顶回 VISIBLE——主题簇在原高切片外画不出来、只剩
        // 固定位铃铛露头（v2.0.3 真机实证）。挂起期只保持整组 GONE。
        if (st.suspended) {
            if (wrapper.visibility != View.GONE) wrapper.visibility = View.GONE
            return
        }
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
        // 可见性/z 序自愈：wrapper 被改 INVISIBLE/透明或别的子视图
        // 后加进来盖住它时，每轮对齐都恢复并顶回父容器最前。
        if (wrapper.visibility != View.VISIBLE) wrapper.visibility = View.VISIBLE
        if (wrapper.alpha != 1f) wrapper.alpha = 1f
        if (parent.indexOfChild(wrapper) != parent.childCount - 1) {
            wrapper.bringToFront()
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

    // ---------- 通知栈顶（v2.0.3 第 6 项 / v2.0.4 改拦截写入口） ----------
    //
    // 机制（反编译逐环核实）：通知栈内容起始位置 = NSSL 的
    // AmbientState.mTopPadding，由 NotificationPanelView
    // .requestScrollerTopPaddingUpdate(boolean) 写入（方法体内两处
    // 写点：非锁屏走 NotifCenterInteractor.nsslTopPadding 流量值、
    // 锁屏走 mIntrinsicPadding），值来自 Compose 场景里 Nssl 盒的
    // window Y，由 Compose 布局量出的头部高度决定，不认 View 层
    // 改的 lp.height。v2.0.3 用 tick 事后增量同步，真机未生效
    // （通知从面板顶部压住时钟）——系统写入时机不受我们控制、
    // tick 总慢半拍且基线捕获条件苛刻。v2.0.4 改拦截写入口：
    // hook 该方法，after 里在同一次调用栈内读出系统刚写的值，
    // 加上（当前头部高 - 原头部高）的增量后按系统同组更新方法
    // 放行——系统每次更新都自带偏移，不再赛跑。tick 只留一次性
    // 兜底：识别「系统原值未调整」或「头部高度又变了」时补一次，
    // 始终以跟踪到的系统原值为基数，绝不叠加双加。挂起/卸载时
    // 只在当前值仍是我们调整过的值时才恢复系统原值。

    /** 定位 NSSL 并取其 AmbientState（从面板子树按类名找，找到后缓存）。 */
    private fun findStack(st: State): Boolean {
        val cached = st.stackScroller
        if (cached != null && cached.isAttachedToWindow && st.stackAmbient != null) {
            return true
        }
        st.stackScroller = null
        st.stackAmbient = null
        // 搜索根：优先 NotificationPanelView 祖先的子树，找不到再退窗口根
        var root: View = st.header
        var v: View? = st.header
        while (v != null) {
            if (v.javaClass.name == PANEL_CLASS_NAME) {
                root = v
                break
            }
            root = v
            v = v.parent as? View
        }
        val found = findViewByClassName(root, STACK_CLASS_NAME) ?: return false
        val ambient = try {
            XposedHelpers.getObjectField(found, "mAmbientState")
        } catch (t: Throwable) {
            logFn("stack sync: mAmbientState read failed: ${t.javaClass.name}: ${t.message}")
            null
        } ?: return false
        st.stackScroller = found
        st.stackAmbient = ambient
        logFn("stack sync: NotificationStackScrollLayout located")
        return true
    }

    private fun findViewByClassName(view: View, className: String): View? {
        if (view.javaClass.name == className) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findViewByClassName(view.getChildAt(i), className)?.let { return it }
            }
        }
        return null
    }

    private fun readTopPadding(st: State): Int? {
        val ambient = st.stackAmbient ?: return null
        return try {
            XposedHelpers.getIntField(ambient, "mTopPadding")
        } catch (t: Throwable) {
            null
        }
    }

    private fun writeTopPadding(st: State, value: Int) {
        val ambient = st.stackAmbient ?: return
        val stack = st.stackScroller ?: return
        try {
            XposedHelpers.setIntField(ambient, "mTopPadding", value)
            // 与系统 requestScrollerTopPaddingUpdate 写完字段后相同的
            // 更新组（方法名经反编译核实），逐个尽力调用。
            runCatching { XposedHelpers.callMethod(stack, "updateAlgorithmHeightAndPadding") }
            runCatching { XposedHelpers.callMethod(stack, "updateContentHeight") }
            runCatching { XposedHelpers.callMethod(stack, "updateStackPosition\$1") }
                .onFailure {
                    runCatching { XposedHelpers.callMethod(stack, "updateStackPosition") }
                }
            runCatching { XposedHelpers.callMethod(stack, "requestChildrenUpdate") }
            runCatching {
                val evClass = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.notification.row.ExpandableView",
                    stack.javaClass.classLoader,
                )
                XposedHelpers.callMethod(
                    stack, "notifyHeightChangeListener",
                    arrayOf(evClass, Boolean::class.javaPrimitiveType),
                    null, false,
                )
            }
        } catch (t: Throwable) {
            logFn("stack sync: write failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    /**
     * 栈顶写入口拦截（v2.0.4）：hook NotificationPanelView
     * .requestScrollerTopPaddingUpdate(boolean)——反编译核实它是
     * AmbientState.mTopPadding 的系统写入口（方法体内完成计算、
     * 写字段与整组更新）。签名只有 boolean（动画标志），值在方法
     * 内部算出，故在 after 里同调用栈调整：读系统刚写的值 → 记为
     * 系统原值 → 加头部高度增量 → 按系统同组更新方法放行。
     */
    private fun installStackWriteInterceptor(
        lpparam: XC_LoadPackage.LoadPackageParam,
        log: (String) -> Unit,
    ) {
        try {
            val panelClass = XposedHelpers.findClass(PANEL_CLASS_NAME, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                panelClass, "requestScrollerTopPaddingUpdate",
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            onSystemTopPaddingWrite(param.thisObject as? View)
                        } catch (t: Throwable) {
                            logFn(
                                "stack intercept failed: " +
                                    "${t.javaClass.name}: ${t.message}"
                            )
                        }
                    }
                }
            )
            log("stack write interceptor installed (NotificationPanelView.requestScrollerTopPaddingUpdate)")
        } catch (t: Throwable) {
            log(
                "stack write interceptor install failed, tick fallback only: " +
                    "${t.javaClass.name}: ${t.message}"
            )
        }
    }

    /** 系统刚写完一次 mTopPadding：覆盖层激活时把增量加回去。 */
    private fun onSystemTopPaddingWrite(panel: View?) {
        val st = synchronized(states) {
            states.values.firstOrNull { it.wrapper != null }
        } ?: return
        // 挂起（锁屏/控制中心）时放行原值：锁屏分支写的是
        // mIntrinsicPadding，绝不能加增量。
        if (st.suspended) return
        if (panel != null) {
            val keyguardShowing = try {
                XposedHelpers.getBooleanField(panel, "mKeyguardShowing")
            } catch (t: Throwable) {
                false
            }
            if (keyguardShowing) return
        }
        if (!findStack(st)) return
        val cur = readTopPadding(st) ?: return
        // 这是系统原值（方法刚算出并写入的），记下后加增量放行
        st.hookSystemTopPadding = cur
        st.hookAdjustedTopPadding = null
        applyStackDelta(st, cur)
    }

    /** 把系统原值加上头部高度增量写回（增量 ≤0 时不写、保持原值）。 */
    private fun applyStackDelta(st: State, systemValue: Int) {
        if (st.originalHeight <= 0) return
        val delta = st.header.height - st.originalHeight
        if (delta <= 0) return
        val target = systemValue + delta
        writeTopPadding(st, target)
        st.hookAdjustedTopPadding = target
        if (st.interceptLogCount < 5) {
            st.interceptLogCount++
            logFn(
                "stack write adjusted #${st.interceptLogCount}: " +
                    "system=$systemValue + delta=$delta -> $target"
            )
        }
    }

    /**
     * tick/布局兜底（v2.0.4）：写入口拦截是主路径；这里只处理两种
     * 漏网——系统原值已在位但没经拦截调整（如拦截安装前写入的）、
     * 头部高度在两次系统写入之间又变了。基数永远是跟踪到的系统
     * 原值、且认得出我们调整过的值，绝不叠加双加。
     */
    private fun ensureStackAdjusted(st: State) {
        if (st.suspended || st.originalHeight <= 0) return
        if (!findStack(st)) return
        val cur = readTopPadding(st) ?: return
        val adjusted = st.hookAdjustedTopPadding
        if (adjusted != null && cur == adjusted) {
            // 仍是我们调整过的值：头部高度再变时按新 delta 重算；
            // 头部回到原高（delta 归零）时恢复系统原值，别留增量残值
            val sys = st.hookSystemTopPadding ?: return
            val delta = st.header.height - st.originalHeight
            if (delta <= 0) {
                writeTopPadding(st, sys)
                st.hookAdjustedTopPadding = null
                logFn("stack sync: topPadding restored to system value $sys (delta back to 0)")
            } else if (cur != sys + delta) {
                applyStackDelta(st, sys)
            }
            return
        }
        // 系统原值（刚被系统复位/初次见到）：记下并调整
        st.hookSystemTopPadding = cur
        applyStackDelta(st, cur)
    }

    /** 恢复栈顶：只在当前值仍是我们调整过的值时写回系统原值（挂起/卸载时调用）。 */
    private fun restoreStackTop(st: State) {
        val adjusted = st.hookAdjustedTopPadding ?: return
        st.hookAdjustedTopPadding = null
        val sys = st.hookSystemTopPadding ?: return
        if (!findStack(st)) return
        val cur = readTopPadding(st) ?: return
        if (cur == adjusted) {
            writeTopPadding(st, sys)
            logFn("stack sync: topPadding restored to system value $sys")
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
        // 主题背景多为深色（云镜是蓝天图），克隆来的原铃铛图标是深色
        // 描画、在半透明深色圆底上几乎看不见：统一白色 tint（v2.0.3）。
        // imageTintList 与 drawable 解耦，后续 tick 里补设图标也生效。
        bell.imageTintList = android.content.res.ColorStateList.valueOf(
            android.graphics.Color.WHITE
        )
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
                    // 状态已被 teardown 摘除时这条 tick 必须停（否则会把
                    // 已卸载的覆盖层重新挂回去）。
                    val registered = synchronized(states) { states[st.header] === st }
                    if (!registered) {
                        st.ticker = null
                        return
                    }
                    // 页态门控（v2.0.3 锁屏 / v2.0.4 加控制中心）：
                    // 挂起期保持整组 GONE，条件解除后经 attach 恢复。
                    val reason = suspendReason(st.header)
                    if (reason != null) {
                        if (!st.suspended || st.suspendReason != reason) {
                            suspendOverlay(st.header, st, reason)
                        }
                        val mtimeKg = StampStore.stampMtime()
                        if (st.stampMtimeSeen >= 0 && mtimeKg != st.stampMtimeSeen) {
                            attach(st.header)
                        }
                        st.handler.postDelayed(this, 1000L)
                        return
                    }
                    if (st.suspended) {
                        attach(st.header)
                        st.handler.postDelayed(this, 1000L)
                        return
                    }
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
                        // 通知栈顶兜底（主路径是写入口拦截，见
                        // installStackWriteInterceptor）
                        ensureStackAdjusted(st)
                        if (!st.bellDrawableSet) {
                            st.bell?.let { trySetBellDrawable(st.header, st, it) }
                        }
                    }
                    logDiag(st)
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

    /**
     * 覆盖层诊断（v2.0.2）：每进程前 5 次 tick 各一行，记录头部、
     * wrapper、ThemeView 的 bounds/可见性与 onDraw 实绘情况，
     * 用于定位「挂上了但看不见」卡在哪一环。
     * visibility 数值：0=VISIBLE、4=INVISIBLE、8=GONE。
     */
    private fun logDiag(st: State) {
        if (st.diagTicks >= 5) return
        st.diagTicks++
        val header = st.header
        val wrapper = st.wrapper
        val tv = st.themeView
        val parent = wrapper?.parent as? ViewGroup
        val childIdx = if (parent != null && wrapper != null) {
            parent.indexOfChild(wrapper)
        } else {
            -1
        }
        logFn(
            "overlay diag #${st.diagTicks}: " +
                "header=[${header.left},${header.top},${header.width}x${header.height}] " +
                "vis=${header.visibility} | " +
                "wrapper=[${wrapper?.left},${wrapper?.top},${wrapper?.right},${wrapper?.bottom}] " +
                "vis=${wrapper?.visibility} alpha=${wrapper?.alpha} " +
                "child=$childIdx/${parent?.childCount} clip=${parent?.clipChildren} | " +
                "themeView=[${tv?.width}x${tv?.height}] vis=${tv?.visibility} " +
                "alpha=${tv?.alpha} drawCalls=${tv?.drawCalls} " +
                "drawn=${tv?.lastDrawnElements} envNull=${tv?.lastEnvMissing} | " +
                "bgShown=${st.appliedStamp?.showBackground} | " +
                "mountBounds=${st.mountBounds?.joinToString(",")}"
        )
        // 高度核对（v2.0.3）：计算高度 vs 元素最大下缘，验证跟随内容
        // 模式下 Text 下缘新口径是否把内容完整包住。
        val computed = st.appliedStamp?.let { desiredHeight(header, st, it) }
        logFn(
            "overlay height check #${st.diagTicks}: computed=$computed " +
                "contentMaxBottom=${st.renderer.contentBottomPx} headerH=${header.height}"
        )
        // 栈顶核对（v2.0.3 第 6 项 / v2.0.4 拦截口径）：头部下缘 vs
        // 栈顶实际 Y（均为 window 坐标）；拦截生效后 gap 应回到系统
        // 原有间距（系统原值 - 原头部下缘），而不是负数（通知钻进
        // 覆盖层）。system/adjusted 为写入口拦截的跟踪值。
        val stack = st.stackScroller
        val ambient = st.stackAmbient
        if (stack != null && ambient != null) {
            val hLoc = IntArray(2)
            header.getLocationOnScreen(hLoc)
            val sLoc = IntArray(2)
            stack.getLocationOnScreen(sLoc)
            val tp = readTopPadding(st)
            val translation = try {
                XposedHelpers.getFloatField(ambient, "mStackTranslation")
            } catch (t: Throwable) {
                0f
            }
            val headerBottom = hLoc[1] + header.height
            val stackTop = if (tp != null) {
                sLoc[1] + tp + translation.roundToInt()
            } else {
                -1
            }
            logFn(
                "overlay stack check #${st.diagTicks}: headerBottom=$headerBottom " +
                    "stackTop=$stackTop gap=${stackTop - headerBottom} " +
                    "topPadding=$tp system=${st.hookSystemTopPadding} " +
                    "adjusted=${st.hookAdjustedTopPadding} suspended=${st.suspended}"
            )
        } else {
            logFn("overlay stack check #${st.diagTicks}: stack not located yet")
        }
    }

    /**
     * 页态挂起（v2.0.3 锁屏 / v2.0.4 加控制中心）：把覆盖层整组
     * （含铃铛）藏起来、原头部内容与高度恢复原样，但保留 State 与
     * tick——回到通知中心页后 tick 会经 attach 自动恢复挂载。
     * 与 teardown 的区别：不摘状态、不停 tick。挂起/原因变化时各
     * 记一行，带 wrapper 与铃铛的可见性数值（0=VISIBLE、8=GONE），
     * 便于核对整组是否真的撤下去了。
     */
    private fun suspendOverlay(header: ViewGroup, st: State, reason: String) {
        val wasSuspended = st.suspended
        val prevReason = st.suspendReason
        st.suspended = true
        st.suspendReason = reason
        // 栈顶停手并恢复系统原值，别误伤锁屏/控制中心自己的布局
        restoreStackTop(st)
        try {
            st.wrapper?.let { wrapper ->
                if (wrapper.visibility != View.GONE) wrapper.visibility = View.GONE
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
        } catch (t: Throwable) {
            logFn("overlay suspend failed: ${t.javaClass.name}: ${t.message}")
        }
        if (!wasSuspended || prevReason != reason) {
            logFn(
                "overlay suspended: $reason (state=$statusBarState, " +
                    "cc=${isControlCenterActive(header)}) " +
                    "wrapperVis=${st.wrapper?.visibility} bellVis=${st.bell?.visibility}"
            )
        }
    }

    // ---------- 卸载/回退 ----------

    private fun teardown(header: ViewGroup) {
        val st = synchronized(states) { states.remove(header) } ?: return
        restoreStackTop(st)
        try {
            stopTicker(st)
            st.layoutListener?.let { listener ->
                header.removeOnLayoutChangeListener(listener)
                st.layoutListener = null
            }
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

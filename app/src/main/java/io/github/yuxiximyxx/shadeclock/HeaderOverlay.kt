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
import java.lang.ref.WeakReference
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
 * v2.0.5 修 v2.0.4 真机「很卡顿、几乎卡死、秒数不跳」：根因是
 * 栈顶调整不幂等且会自激——①我们写回 topPadding 时调用的系统
 * 更新组会同步再触发写入口 hook，旧代码没有重入保护，一次系统
 * 写入能级联出多次全栈重排；②SystemUI 重启过渡期系统会按已变
 * 高的头部量出含增量的值（909），旧代码把它当原值又加一遍
 * （日志实数 adjusted=2167），头部越高通知栈越乱、主线程越忙。
 * 改：拦截与兜底统一走 reconcileStackTop 的写死判定（原值→加
 * 增量；原值+增量→认作已正确不加；我们写过的值→回声跳过，但
 * 增量变了要重算；未知值→按与头部高度的合理性窗口定性一次），
 * writeTopPadding 加重入标志，目标值已在位时不调系统更新组；
 * alignOverlay 删直接 layout() 调用、bounds 未变时只做廉价自愈；
 * tick 每秒只做戳记比对与（面板可见时的）秒数重绘，对齐/栈检查
 * 在稳态下都是零写入空转，并每 10 秒打一行心跳（drawCalls 应
 * 稳定每秒 +1）。另加「小模块（温度/风向/电量）」开关（stamp
 * 第 6 行，默认关）：关时渲染器跳过数据小模块，只显示时间样式。
 *
 * v2.0.6 加第三道门控：横幅通知（heads-up peek）呈现时覆盖层也
 * 会露出来压住横幅卡片（v2.0.5 真机，此时 state=0、cc=false，
 * 两道旧门控都放行）。信号取宿主 HeadsUpManagerImpl
 * .setEntryPinned(HeadsUpEntry, PinnedStatus, String)——反编译
 * 核实它是 pinned 状态变更的唯一汇聚点：onEntryAdded /
 * onEntryRemoved / updateNotificationInternal 全部经它，它重算
 * 聚合状态 mHasPinnedNotification、仅在变化时更新字段并向全部
 * OnHeadsUpChangedListener 分发 onHeadsUpPinnedModeChanged；
 * 系统自己的 StatusBarHeadsUpChangeListener 正是把这个回调
 * 翻译成窗口状态 mHeadsUpShowing（日志原文 "setHeadsUpShowing
 * true"），即「横幅呈现中」的系统定义，与前两道门控同构
 * （唯一入口＋去重＋分发）。after 里直读字段跟踪状态，变化时
 * 除等 tick 外还即时重评各 State 的门控（横幅是瞬时呈现，
 * 等 1 秒 tick 会压卡片一截）；轮询兜底直读 hook 捕获的实例
 * 字段。一致性核查：本构建没有 HeadsUpAppearanceController；
 * 横幅机制对 pinned 变化的反应只有窗口状态（mHeadsUpShowing /
 * mForceCollapsed）、面板 requestLayout、音量弹窗关闭、NSSL
 * 动画记账与 setHeadsUpAnimatingAway，HnHeadsUpManagerPhoneImpl
 * 的同名监听只做横幅行自身的模糊/注视/触摸——都不碰头部本体、
 * 其 Compose 子视图或父容器 HnColumeContainer 的可见性，与
 * 本模块挂起时恢复的项目（wrapper 可见性、头部子视图可见性、
 * 头部高度、栈顶）完全 disjoint，挂起保持标准恢复、不打架。
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

    /**
     * 栈顶值定性的合理性窗口（v2.0.5）：系统原值应约等于原头部
     * 高度、含增量的值应约等于当前头部高度，允许 ±240 的系统
     * 自有间距（沿用 v2.0.3 基线捕获的容差）。
     */
    private const val STACK_PLAUSIBLE_SLACK = 240

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

    // 横幅门控（v2.0.6）：类名经反编译核实——HeadsUpManagerImpl
    // 是新场景架构的横幅管理器，setEntryPinned 是 pinned 状态
    // 变更的唯一汇聚点，聚合字段 mHasPinnedNotification 即
    // 系统 mHeadsUpShowing 的同源状态。
    private const val HEADS_UP_MANAGER_IMPL_CLASS =
        "com.android.systemui.statusbar.notification.headsup.HeadsUpManagerImpl"

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

    /** 横幅是否呈现中（HeadsUpManagerImpl 聚合 pinned 状态 hook 跟踪；字段直读兜底见 isHeadsUpActive）。 */
    @Volatile
    private var headsUpPinned = false

    /** hook 回调捕获的 HeadsUpManagerImpl 实例（弱引用），供轮询直读其 mHasPinnedNotification 字段。 */
    @Volatile
    private var headsUpManagerRef: WeakReference<Any>? = null
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
        /** 挂起原因："keyguard" / "control-center" / "heads-up"，日志与恢复判断用。 */
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
        /**
         * 栈顶写回重入保护（v2.0.5）：置位期间拦截/兜底一律跳过——
         * 我们调用的系统更新组会同步再触发写入口 hook，不挡住就
         * 会级联自激（v2.0.4 真机卡死的主因之一）。
         */
        var stackAdjusting = false
        /** tick 计数（心跳日志用，v2.0.5）。 */
        var tickCount = 0
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
        installHeadsUpGate(lpparam, log)
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
     * 横幅门控的信号源（v2.0.6）：hook 宿主 HeadsUpManagerImpl
     * .setEntryPinned(HeadsUpEntry, PinnedStatus, String)——反编译
     * 核实它是 pinned 状态变更的唯一汇聚点（onEntryAdded /
     * onEntryRemoved / updateNotificationInternal 全部经它），
     * 方法内重算聚合状态、仅在变化时更新字段 mHasPinnedNotification
     * 并向全部 OnHeadsUpChangedListener 分发
     * onHeadsUpPinnedModeChanged；系统自己的
     * StatusBarHeadsUpChangeListener 正是把该回调翻译成窗口状态
     * mHeadsUpShowing，即「横幅呈现中」的系统定义，与锁屏门控的
     * setState、控制中心门控的 notifyControlCenterPanelChange
     * 同构（唯一入口＋去重＋分发）。after 里直读字段跟踪状态；
     * 状态变化时除每秒 tick 外还即时重评各 State 的门控——横幅
     * 是瞬时呈现，等 tick 会先压卡片一截。hook 失败时
     * [isHeadsUpActive] 退化为跟踪值（初始 false），门控不成立
     * 但其余功能不受影响，日志会写明。
     */
    private fun installHeadsUpGate(
        lpparam: XC_LoadPackage.LoadPackageParam,
        log: (String) -> Unit,
    ) {
        try {
            val implClass = XposedHelpers.findClass(HEADS_UP_MANAGER_IMPL_CLASS, lpparam.classLoader)
            val entryClass = XposedHelpers.findClass(
                "$HEADS_UP_MANAGER_IMPL_CLASS\$HeadsUpEntry", lpparam.classLoader
            )
            val pinnedStatusClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.notification.headsup.PinnedStatus",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                implClass, "setEntryPinned",
                entryClass, pinnedStatusClass, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val mgr = param.thisObject ?: return
                        if (headsUpManagerRef?.get() !== mgr) {
                            headsUpManagerRef = WeakReference(mgr)
                        }
                        val z = try {
                            XposedHelpers.getBooleanField(mgr, "mHasPinnedNotification")
                        } catch (t: Throwable) {
                            return
                        }
                        if (z != headsUpPinned) {
                            headsUpPinned = z
                            log("heads-up pinned mode -> $z")
                            reevaluateGatesNow()
                        }
                    }
                }
            )
            log("heads-up gate hook installed (HeadsUpManagerImpl.setEntryPinned)")
        } catch (t: Throwable) {
            log(
                "heads-up gate hook failed, gate inactive: " +
                    "${t.javaClass.name}: ${t.message}"
            )
        }
    }

    /**
     * 当前是否有横幅正在呈现：优先直读 hook 捕获的 HeadsUpManagerImpl
     * 实例字段 mHasPinnedNotification（聚合 pinned 状态，与系统
     * mHeadsUpShowing 同源、随呈现进入/退出即时变化）；实例还没
     * 捕获到或直读失败时用 hook 跟踪值兜底。本模块只管通知中心
     * 页，横幅呈现中即挂起；呈现状态的进入/退出以该信号自身
     * 语义为准（用户下拉展开进完整通知页时 pinned 随场景转换
     * 退出，门控随之恢复）。
     */
    private fun isHeadsUpActive(): Boolean {
        val mgr = headsUpManagerRef?.get()
        if (mgr != null) {
            try {
                return XposedHelpers.getBooleanField(mgr, "mHasPinnedNotification")
            } catch (t: Throwable) {
                // 直读失败退回跟踪值
            }
        }
        return headsUpPinned
    }

    /**
     * 即时重评各 State 的页态门控（v2.0.6，横幅 hook 状态变化时
     * 调用）：横幅呈现是瞬时的，等每秒 tick 会先压卡片一截；
     * 这里把 tick 的门控一轮（[applyGate]）post 到各 State 的
     * handler 上立即执行——post 而非在 hook 调用栈内同步做，
     * 避开宿主 setEntryPinned 尚在通知管线中途的重入。tick 仍
     * 是兜底（hook 装不上或状态漏变时靠它收敛）。
     */
    private fun reevaluateGatesNow() {
        val snapshot = synchronized(states) { states.values.toList() }
        for (st in snapshot) {
            st.handler.post {
                try {
                    val registered = synchronized(states) { states[st.header] === st }
                    if (registered) applyGate(st)
                } catch (t: Throwable) {
                    logFn("overlay gate reevaluate failed: ${t.javaClass.name}: ${t.message}")
                }
            }
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

    /** 挂起原因：null=可挂载；优先级 锁屏 > 控制中心 > 横幅（可同时成立，取先者记日志）。 */
    private fun suspendReason(header: ViewGroup): String? = when {
        isKeyguardActive(header.context) -> "keyguard"
        isControlCenterActive(header) -> "control-center"
        isHeadsUpActive() -> "heads-up"
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
            // 页态门控（v2.0.3 锁屏 / v2.0.4 控制中心 / v2.0.6 横幅）：头部在锁屏
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
                        "(state=$statusBarState, cc=${isControlCenterActive(header)}, " +
                        "hun=${isHeadsUpActive()}) " +
                        "wrapperVis=${st.wrapper?.visibility} bellVis=${st.bell?.visibility}"
                )
            }
            // 主题背景图开关（v2.0.3）：stamp 每次挂载/变更时同步给渲染器
            st.renderer.showBackgroundImage = stamp.showBackground
            // 小模块开关（v2.0.5）：同上，stamp 第 6 行
            st.renderer.showWidgets = stamp.showWidgets
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
                            // 面板不可见时不重绘（v2.0.5 瘦身）
                            if (st.wrapper?.isShown == true) {
                                st.themeView?.invalidate()
                            }
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
        val prevBounds = st.mountBounds
        st.mountBounds = bounds
        if (prevBounds != null && prevBounds.contentEquals(bounds)) {
            // bounds 未变（稳态 tick 与重复布局回调的绝大多数情况）：
            // 只做廉价的可见性/z 序自愈，不写 LayoutParams、不触发
            // requestLayout（v2.0.5 瘦身，防布局自激）。
            healWrapperVisibility(wrapper, parent)
            return
        }
        // LayoutParams 只在数值真变化时才写；定位完全交给父容器
        // （标准 FrameLayout，已实证不重写 onLayout）的布局 pass，
        // 不再直接调 layout() 硬摆（v2.0.4 及以前每秒硬摆一次，
        // 与父容器布局互相打架，是卡顿嫌疑之一，v2.0.5 删除）。
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
        healWrapperVisibility(wrapper, parent)
    }

    /** 可见性/z 序自愈：wrapper 被改 INVISIBLE/透明或别的子视图后加进来盖住它时恢复并顶回最前（只在状态真不对时才写）。 */
    private fun healWrapperVisibility(wrapper: View, parent: ViewGroup) {
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
    // 放行——系统每次更新都自带偏移，不再赛跑。挂起/卸载时只在
    // 当前值仍是我们调整过的值时才恢复系统原值。
    // v2.0.5 教训：v2.0.4 的拦截不幂等且会自激（见类头 v2.0.5
    // 段）——拦截与兜底统一走 reconcileStackTop 的写死判定，
    // writeTopPadding 带重入保护，目标值已在位时不调更新组。

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
        // 重入保护（v2.0.5）：下面的系统更新组会同步再触发写入口
        // hook（乃至系统再写一次），置位期间拦截/兜底/恢复一律
        // 跳过——v2.0.4 没这层保护，一次写入级联多次全栈重排，
        // 是真机卡死与重复加增量的共同源头。
        if (st.stackAdjusting) return
        st.stackAdjusting = true
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
        } finally {
            st.stackAdjusting = false
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

    /** 系统刚写完一次 mTopPadding：覆盖层激活时按统一判定处理。 */
    private fun onSystemTopPaddingWrite(panel: View?) {
        val st = synchronized(states) {
            states.values.firstOrNull { it.wrapper != null }
        } ?: return
        // 我们自己写回引发的重入：整段跳过（v2.0.5 防自激）
        if (st.stackAdjusting) return
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
        reconcileStackTop(st, cur)
    }

    /** 拦截/兜底共用的有界日志（避免动画期间逐帧刷屏）。 */
    private fun logIntercept(st: State, message: String) {
        if (st.interceptLogCount < 8) {
            st.interceptLogCount++
            logFn(message)
        }
    }

    /**
     * 栈顶统一判定（v2.0.5，拦截与 tick 兜底共用，判定规则写死）：
     * 维护 (hookSystemTopPadding=系统原值, hookAdjustedTopPadding=
     * 调整值) 一对跟踪值，对当前值 cur 按当前头部增量 delta 定性：
     * - delta ≤ 0：cur 即原值，校准基准；若 cur 还是我们旧的调整
     *   值且头部已复原，写回原值清残值。
     * - cur == 调整值：回声（系统把调整过的值又写回来/我们写回的
     *   同步回读），已正确跳过；仅当 delta 已变（头部又长高/缩回）
     *   时按新 delta 重算一次。
     * - cur == 原值：纯系统原值，加增量放行。
     * - cur == 原值 + delta：系统已按变高后的头部量出（SystemUI
     *   重启过渡期实证：先量出 909）、增量已含，认作已正确不加，
     *   只把跟踪调整值更新为 cur（v2.0.4 在这里误当原值再加，
     *   1538/2167 即此）。
     * - 其余未知值：按合理性窗口定性一次——约等于当前头部高度
     *   （±[STACK_PLAUSIBLE_SLACK]）视为已含增量并反推原值；约
     *   等于原头部高度视为原值并加增量；都对不上则原样采用为
     *   原值候选、本轮不加，等后续确切匹配再处理。
     * 任何分支里目标值与现值相同都不调用系统更新组（见
     * [applyStackDelta]）。
     */
    private fun reconcileStackTop(st: State, cur: Int) {
        if (st.stackAdjusting) return
        val origH = st.originalHeight
        if (origH <= 0) return
        val delta = st.header.height - origH
        val sys = st.hookSystemTopPadding
        val adj = st.hookAdjustedTopPadding
        when {
            delta <= 0 -> {
                if (adj != null && cur == adj && sys != null) {
                    writeTopPadding(st, sys)
                    st.hookAdjustedTopPadding = null
                    logFn("stack sync: topPadding restored to system value $sys (delta back to 0)")
                } else if (cur != adj) {
                    st.hookSystemTopPadding = cur
                    st.hookAdjustedTopPadding = null
                }
            }
            adj != null && cur == adj -> {
                if (sys != null && adj != sys + delta) {
                    applyStackDelta(st, sys)
                }
            }
            sys != null && cur == sys -> {
                applyStackDelta(st, cur)
            }
            sys != null && cur == sys + delta -> {
                st.hookAdjustedTopPadding = cur
                logIntercept(
                    st,
                    "stack write already includes delta: value=$cur " +
                        "(system=$sys + delta=$delta), left as-is",
                )
            }
            else -> {
                when {
                    abs(cur - st.header.height) <= STACK_PLAUSIBLE_SLACK -> {
                        st.hookSystemTopPadding = cur - delta
                        st.hookAdjustedTopPadding = cur
                        logIntercept(
                            st,
                            "stack write adopted as already-adjusted: value=$cur, " +
                                "derived system=${cur - delta} (delta=$delta)",
                        )
                    }
                    abs(cur - origH) <= STACK_PLAUSIBLE_SLACK -> {
                        st.hookSystemTopPadding = cur
                        applyStackDelta(st, cur)
                    }
                    else -> {
                        st.hookSystemTopPadding = cur
                        st.hookAdjustedTopPadding = null
                        logIntercept(
                            st,
                            "stack write adopted as-is (unclassified): value=$cur delta=$delta",
                        )
                    }
                }
            }
        }
    }

    /** 把系统原值加上头部高度增量写回（增量 ≤0 时不写、保持原值）。 */
    private fun applyStackDelta(st: State, systemValue: Int) {
        if (st.originalHeight <= 0) return
        val delta = st.header.height - st.originalHeight
        if (delta <= 0) return
        val target = systemValue + delta
        // 目标值已在位：只更新跟踪，绝不调用那套更新方法（v2.0.5；
        // 面板动画期间系统逐帧写栈顶，每帧整套重排是卡顿主因之一）
        if (readTopPadding(st) == target) {
            st.hookAdjustedTopPadding = target
            return
        }
        writeTopPadding(st, target)
        st.hookAdjustedTopPadding = target
        logIntercept(
            st,
            "stack write adjusted: system=$systemValue + delta=$delta -> $target",
        )
    }

    /**
     * tick/布局兜底（v2.0.5）：写入口拦截是主路径；这里只把当前值
     * 交给同一套判定（[reconcileStackTop]）——稳态下当前值恒等于
     * 调整值，走回声分支零写入；漏网的系统复位/高度变化会被它
     * 识别并补一次，绝不叠加双加。
     */
    private fun ensureStackAdjusted(st: State) {
        if (st.suspended || st.originalHeight <= 0) return
        if (st.stackAdjusting) return
        if (!findStack(st)) return
        val cur = readTopPadding(st) ?: return
        reconcileStackTop(st, cur)
    }

    /** 恢复栈顶：只在当前值仍是我们调整过的值时写回系统原值（挂起/卸载时调用）。 */
    private fun restoreStackTop(st: State) {
        if (st.stackAdjusting) return
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

    /**
     * 页态门控一轮（tick 与横幅 hook 的即时重评共用）：返回 true
     * 表示本轮已被门控处理——挂起中（保持整组 GONE，挂起期间
     * 戳记变了仍走 attach 以便切回原头部等设置变更生效），或
     * 刚从挂起恢复并已由 attach 重新挂载；调用方不要再走稳态
     * 路径。返回 false 才是稳态（覆盖层正常挂载中）。
     */
    private fun applyGate(st: State): Boolean {
        val reason = suspendReason(st.header)
        if (reason != null) {
            if (!st.suspended || st.suspendReason != reason) {
                suspendOverlay(st.header, st, reason)
            }
            val mtimeKg = StampStore.stampMtime()
            if (st.stampMtimeSeen >= 0 && mtimeKg != st.stampMtimeSeen) {
                attach(st.header)
            }
            return true
        }
        if (st.suspended) {
            attach(st.header)
            return true
        }
        return false
    }

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
                    // 页态门控（v2.0.3 锁屏 / v2.0.4 控制中心 /
                    // v2.0.6 横幅）：与横幅 hook 的即时重评共用
                    // applyGate；挂起期保持整组 GONE，条件解除后
                    // 经 attach 恢复。
                    if (applyGate(st)) {
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
                    // 秒数重绘：面板不可见时跳过（没人看就不画，
                    // 省主线程，v2.0.5）；对齐/栈检查在上面的稳态
                    // 路径里都是零写入判定，不再是每秒重活。
                    if (wrapper.isShown) {
                        st.themeView?.invalidate()
                    }
                    // 心跳（v2.0.5）：每 10 秒一行，drawCalls 在
                    // 面板可见时应稳定每秒 +1、不可见时不涨——用
                    // 来真机验收「秒数逐秒跳、主线程不被塞满」。
                    st.tickCount++
                    if (st.tickCount % 10 == 0) {
                        logFn(
                            "overlay heartbeat #${st.tickCount / 10}: " +
                                "drawCalls=${st.themeView?.drawCalls} " +
                                "drawn=${st.themeView?.lastDrawnElements}"
                        )
                    }
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
     * 页态挂起（v2.0.3 锁屏 / v2.0.4 控制中心 / v2.0.6 横幅）：把覆盖层整组
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
                    "cc=${isControlCenterActive(header)}, hun=${isHeadsUpActive()}) " +
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

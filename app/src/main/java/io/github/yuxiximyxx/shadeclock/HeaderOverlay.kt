package io.github.yuxiximyxx.shadeclock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
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
import kotlin.math.max
import kotlin.math.min
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
 * v2.0.7 补全通知避让 + 垂直位置自定义：
 * ①写路径枚举（dexdump 全 APK 扫 `iput …AmbientState.mTopPadding`）：
 * 全包**只有一个写点**，就在已 hook 的 NotificationPanelView
 * .requestScrollerTopPaddingUpdate(Z) 内（值三分支：通知页走
 * NotifCenterInteractor.nsslTopPadding 流量=280；mBarState==2 或
 * 场景标志关时走「calculateQsTopPadding」=dimen+刘海状态栏高
 * =501；锁屏走 NSSL.mIntrinsicPadding——NSSL 本身没有
 * setTopPadding 方法）。v2.0.6 漏网的 501 不是新写路径，而是
 * **覆盖时序**：我们写回时调用的系统更新组会同步再入
 * requestScrollerTopPaddingUpdate，嵌套写（501）在我们之后落
 * 字段、其 after-hook 又被 stackAdjusting 重入保护挡掉——字段
 * 停在 501、跟踪 adjusted=1050（真机 stack check #5 实数）。
 * 对策：拦截在 stackAdjusting 期间不再静默丢弃嵌套写，改为
 * 排一次延迟复查（scheduleStackReconcile，调用栈 unwind 后跑
 * 同一套 reconcile、读回校验、未到位有限次重排）；每次拦截
 * 处理完也排一次复查兜底。另按小乐点名：每次门控恢复与
 * attach 完成时主动 reconcile 一次——挂起期系统写过的栈顶值
 * （挂起中拦截是放行的）在恢复时当场补账，不等下一次系统写。
 * reconcile 的原值判定保持状态无关：原值可能是 280/501 等
 * 多个状态相关值，一律按与已跟踪 (原值, 调整值) 及当前 delta
 * 的关系判定，不写死任何原值。
 * ②垂直偏移（设置页滑杆 -400dp～+160dp、默认 0、stamp 第 7 行；
 * v2.0.24 由 -120dp～+40dp 放宽：全屏画布类主题的簇本身在画布
 * 中部偏下，-120dp 拉满仍不够）：
 * 主题设计顶部留白（云镜簇顶 335 设计px）是给锁屏天空的，
 * 背景关掉后在通知中心显得空、整体偏低。渲染器整体平移
 * （draw 根 y 加 dy，元素与顶/底统计一起动），dy 由 dp 换算
 * 并钳制到簇顶不小于 0；跟随内容高度 = 含偏移的内容下缘，
 * 头部随之上移变矮、栈 delta 机制自动跟随。只作用于覆盖层。
 *
 * v2.0.8：①避让改「来源追踪」根治误判——v2.0.7 真机实锤：
 * reconcile 的 ±240 合理性窗口把 QS 分支值 501 误判为「已含
 * 增量的调整值」、反推出根本不存在的原值 232 并锁定，
 * topPadding 从此停在 501。猜测启发式是病根，本版废除：
 * 写入口每次经过（含嵌套再入）都把系统刚写下的原始值记为
 * lastSystemOriginal（我们自己的调整是直接写字段、不经此
 * 方法，天然可区分；唯一防回声的护栏是「值等于我们刚写的
 * 目标时不记录」），延迟复查与 tick 兜底只用
 * target = lastSystemOriginal + delta 计算，不再有任何窗口
 * 猜测。分支语义（反编译逐环核实）：流量分支的值是
 * HnNotifCenterComposeImpl 的 Nssl 盒 onPlaced 回调传的
 * localToWindow(Offset.Zero).y——栈内容起始的窗口绝对 Y
 * （= 场景状态栏条高 + 原头部高 280，展开稳态实测 501、
 * 收起/过渡态 280）；QS 分支（SceneContainerFlag 静态属性
 * sys.config.scene.switchOn 关或 mBarState==2 时）=
 * HwNotchStatusBar 高 + dimen notification_panelview_margin_top
 * （80dp=280px，v2.0.7 记的 421+80 分解有误）。两分支的值都是
 * 「原头部高」分量加上各自的状态条分量、写进同一字段按同一
 * 语义解读，故统一以最后写入为准 + delta。②验收量测改真
 * 量测：stack check 不再用 topPadding 回声拼 stackTop，改
 * 直接量第一张通知卡的实际 window Y 与 headerBottom 比
 * realGap。③按小乐点名删掉自制铃铛（克隆图标/点击反射整段
 * 摘除；原头部自己的铃铛随原头部隐藏/恢复的现状不变）。
 * ④隐藏运营商文字（默认开，stamp 第 8 行）在 CarrierHider：
 * 三处（锁屏/控制中心/通知中心）共用同一个运营商容器视图
 * HwOperatorNameParentView（布局 hw_operator_name_parent 被
 * 锁屏的 HwKeyguardStatusBarView 与下拉面板的
 * HwNotchStatusBar 各自的 HwStatusbarContents include），其
 * setOperatorVisibility(boolean) 是可见性统一入口（方法内
 * 日志原文 "show keyguard & notch operator"，且 Keyguard 与
 * panel-header 两类实例会被类型逻辑强制显示、改参数无效，
 * 故在 after 里把容器与三个运营商名 TextView 压 GONE）；
 * ShadeCarrier 经查在本构建无布局声明、无实例化，是死代码，
 * 不挂钩。
 *
 * v2.0.9：①避让收敛时序根治——v2.0.8 真机：来源追踪本身正确
 * （system=501/adjusted=770），但 stack check #2/#3 仍停在
 * 未收敛窗口（topPadding=501、realGap=−269），#4 才靠运气
 * 收敛。本版先静态定案再改：dex 全量扫 invoke，写入口
 * requestScrollerTopPaddingUpdate 的调用方只有 7 处、全在
 * NotificationPanelView 内（Lambda3 动画取消回调、$7 面板
 * 高度动画帧、onHeightChanged、onLayout、onQsHeightChanged、
 * positionClockAndNotifications、setQsExpansion）。逐个核
 * 我们写回的五个更新方法：updateAlgorithmHeightAndPadding /
 * updateContentHeight（含荣耀 Hw 实现）/ updateStackPosition$1
 * 均为纯计算，requestChildrenUpdate 只登记 pre-draw（异步），
 * 唯 **notifyHeightChangeListener 同步再入**：它直达面板
 * onHeightChanged（对任何 view 参数都执行尾部两步）→
 * PanelView.requestPanelHeightUpdate → setExpandedHeight →
 * setExpandedHeightInternal 无条件回调 onHeightUpdated →
 * NotificationPanelView.onHeightUpdated（通知页 mQsExpanded
 * =false 时）→ positionClockAndNotifications → 写入口——
 * 嵌套写把字段盖回系统原值，其 after 又被我们自己的重入
 * 保护挡掉，延迟复查/tick 每次补写都重走同一链，直到面板
 * 状态变化（收敛条件消失）才偶然粘住。改法：writeTopPadding
 * 的更新组**摘除 notifyHeightChangeListener**，只留经证实不
 * 再入的四件；调整在同一调用栈内落定、链终止于调整值，延
 * 迟复查保留作兜底。②运行时留有界证据：写入口每次经过记
 * 一行 stack pass（来源分支 flow/qs/keyguard + 是否嵌套 +
 * 字段前后值，每进程上限 40 行），配合 stack check 的真量
 * 测验收。
 *
 * v2.0.10：控制中心门控改两段（过渡开始即挂起、落定才恢复）
 * ——v2.0.9 真机录屏：通知避让已确认可用，但通知中心↔控制
 * 中心来回切换的过渡期时钟簇半透明压在控制中心瓷砖上。定案
 * （反编译逐环）：横向页面切换由 HnSwipeSwitchControllerImpl
 * 驱动——随指阶段 tracking() 直接给两页视图组设 alpha/
 * translationX，松手后 swipeToControlCenter /
 * swipeToNotificationCenter(float, boolean) 设置 mNextShowType
 * 并 start 一对 AnimatorSet 交叉淡入淡出；动画结束才经其
 * $3.onAnimationEnd → HwPanelControlImpl
 * .notifySwipeTo*Finished → HnPanelViewControllerImpl
 * .onSwipeTo* → notifyControlCenterPanelChange 分发，即现门控
 * 用的 notify 是**落定**信号，过渡全程门控未触发，覆盖层
 * （挂在头部父容器 HnColumeContainer 里、随容器一起被渐变）
 * 就成了残影。改两段：①开始段——hook 同一分发枢纽
 * HwPanelControlImpl.notifySwipeStart(int)：onTouch 字节码
 * 核实它在横向切换 tracking 成立的那次 MOVE 里分发（mIsTracking
 * 置位、mStartShowType 锁定之后，tracking() 移动页面之前），
 * 是页面移动的最早信号；再 hook 引擎的 swipeToControlCenter /
 * swipeToNotificationCenter（fling 提交与程序化切换的汇聚点，
 * 动画集在方法内才 start）作第二开始信号，before 置位
 * pageSwitching 并即时重评门控，过渡期整组 GONE、不参与
 * 滑动/渐变。②落定段——清位只认落定：既有 notify（两方向的
 * 动画结束都分发它）在其 hook 里清位并即时重评，恢复仍要求
 * 落定值为通知页；取消切换的动画结束只分发
 * notifySwipeCanceled(int)、不分发 notify，故单独 hook 它
 * 清位。tick 兜底保留，并加防卡死：pageSwitching 置位超
 * 1.5 秒且引擎既不在动画也不在 tracking 时清位记日志（面板
 * 收起中途打断切换时 $3.onAnimationEnd 会早退、不分发任何
 * 落定信号）。NotificationPanelView.setQsExpansion /
 * onQsHeightChanged 是纵向展开分数、与横向页面切换无关，
 * 不作信号。
 *
 * v2.0.11 修 v2.0.10 真机三点（录屏逐帧）：
 * ①下拉首显迟滞约 1 秒（录屏里先只有通知、时钟簇迟到，且出现
 * 的那一帧原头部内容同框闪过）。根子是冷路径全压在 attach 与
 * 1 秒 tick 上：attach 时同步读 stamp、解析 manifest、加载
 * 字体，位图又是首绘懒解码；ThemeView 首绘若赶上 env 未就绪
 * （宽 0）只录得空帧，下一次重绘要等 tick。改：install 阶段
 * 就在后台线程预热——读 stamp、load 主题、把主题目录图片逐个
 * 预解码进缓存（ThemeRenderer.warmBitmapCache），attach 时
 * 戳记吻合就整套采纳（adoptFrom）、零 IO；不吻合/预热失败仍
 * 走原同步 load 并顺手再排一次预热，预热不是唯一路径。tick
 * 首轮改立即执行（不再 +1000ms），attach 完成后按可见状态
 * 立即 invalidate；门控状态未知（进程刚起、状态机还没来过
 * 事件）不阻塞首显——各门控的未知值本就按「不挂起」兜底
 * （锁屏走 KeyguardManager 实查），预热与首绘不等任何信号。
 * ②页面切换改「跟随」而非「开始即整组 GONE」：v2.0.10 的开始
 * 即挂起太急——通知页还大半可见时样式就整组消失；回程又要等
 * 落定才出现。逐帧进度源定案（反编译 HnSwipeSwitchControllerImpl
 * 逐环）：随指 tracking() 与松手 AnimatorSet 的每一帧都经
 * setViewAlpha(view, f) 设 alpha，view 是通知头部时它不设
 * View.alpha、而是调 NotifCenterHeaderInteractor
 * .updateAnimValueCommonAlpha(f) 驱动 Compose 状态——这正是
 * 原头部内容淡入淡出的逐帧值，也是覆盖层（普通 View、不参与
 * 场景合成）此前跟不上的原因。hook 该方法（Impl 类）的 after
 * 记录 alpha 并在过渡期逐帧同步给 wrapper（alpha＝该值、
 * translationX＝头部当前 translationX，头部本体在切换中被
 * 引擎平移、覆盖层是同级视图须自己跟）。page-switch 的开始/
 * 落定信号保留作终态判定：过渡期不再整组 GONE；控制中心终态
 * 门控改为「落定（!pageSwitching）才硬挂起」——回程拖动中随
 * alpha 从挂起恢复为跟随显示，落定到控制中心的终态仍由
 * notify 后的 applyGate 整组挂起，落定回通知页在清位点统一
 * 复位 alpha=1/translationX=0。跟随 hook 装不上时的兜底：
 * OnPreDraw 里按帧采样头部 translationX 占面板宽的比例换算
 * alpha（帧回调采样、非 tick），双向对称。
 * ③同框闪烁：原头部 Compose 子视图在下拉展开/场景激活过程
 * 中会被宿主在帧频上重新置可见（或重建子视图），旧对策只有
 * attach 与 1 秒 tick 两次 hideComposeChildren，闪烁最长可
 * 达一拍 tick。改同频断言：头部注册 OnPreDrawListener——
 * 每帧绘制前（挂起期除外）重断言子视图 INVISIBLE（只在真
 * 被改回时才写，并有界记日志留证：纠正次数与子视图类名）、
 * ThemeView 尚无有效首绘（drawCalls=0 或 env 曾缺失）时顺手
 * invalidate，不用任何轮询硬压。
 *
 * v2.0.12 修 v2.0.11 真机剩余一点：通知中心↔桌面的开合过渡
 * 不跟随（打开时面板背景还没铺满、时钟簇已全不透明浮在桌面
 * 时钟组件上方；关闭时面板往上收、时钟簇滞留原位全显压在桌
 * 面上、直到面板收完才消失）。反编译定案（SystemUI 17.0.0.1
 * 全量 jadx + 定点类核对）：头部动画的全部驱动最后都汇聚到
 * NotifCenterHeaderInteractorImpl 的同一组漏斗——页面切换
 * （setViewAlpha 同帧同值写 common+time 两路 alpha）、面板
 * 开合 fling（HnStatusBarPullAnimImpl 的头部动画集：200ms
 * 两路 alpha 开→1/关→0、448/360ms 写 containerTransitionY
 * 内容根偏移）、卷帘跟踪与面板控制器动画器（两路 alpha 分
 * 写、收尾靠 updateContainerVisible(false) 藏头部且 alpha
 * 复位 1）。v2.0.11 只 hook 了 commonAlpha 一路且只在页面
 * 切换期应用，开合通道全程被忽略、稳态自愈还把 alpha 拉回
 * 1——这正是开合不跟随的成因。本版把跟随扩为常时统一合成：
 * hook 五个漏斗方法跟踪 (commonAlpha, timeAlpha, visible,
 * containerX, containerY)，wrapper 变换恒为：alpha＝
 * visible ? min(两路 alpha) : 0（取小是保守合成：两路同写
 * 时相等、分写时覆盖层永不比原头部任一元素更显眼；面板全
 * 关后靠 visible=false 或 alpha=0 保证零残留）、translation
 * ＝头部 View 自身平移＋容器根偏移（头部 View 平移无漏斗可
 * 抄、由既有 OnPreDraw 逐帧采样补齐）。页面切换与面板开合
 * 写的是同一组通道、合成只有一套，不存在两路打架；稳态跟
 * 踪值恒为恒等变换。resetAnimState 内部即调两路 alpha 写 1、
 * 方法级 hook 自然覆盖，不单独 hook。时钟单独的 timeScale/
 * pivot（卷帘里状态栏时钟形变用）与 date/mgeBtn 分元素偏移
 * 不抄：覆盖层是整簇单 View、原头部那是分元素形变，量级与
 * 场景都对不上。
 *
 * v2.0.13 修锁屏瞬间闪现：按电源键锁屏时，锁屏界面已展示、
 * setState 门控（落定信号）还没到，时钟簇会闪 1–2 帧。加
 * ACTION_SCREEN_OFF 预挂起：灭屏广播在 SystemUI 进程内即时
 * 到达、早于 keyguard 展示完成，收到即置预挂起标志并立即对
 * 全部 State 重评门控、按既有 keyguard 路径藏整组；setState
 * 门控保留作兜底对账。ACTION_SCREEN_ON 清标志并重评——灭屏
 * 不等于锁屏（无锁/智能解锁下亮屏直接回通知页），恢复走
 * applyGate 既有口径，不会把样式卡在挂起态。接收器在首次
 * attach 时以宿主 applicationContext 注册（进程级、只注册
 * 一次，回调默认在主线程，与 tick 的门控重评同路径）。
 *
 * v2.0.15 修下拉首帧空白（v2.0.14 真机：闪烁有改善，但下拉
 * 中段通知卡已进场、时钟区仍空白，全开后才整幅出现——通知
 * 先到、时钟迟到约一拍）。日志对位（probe_3 的 v2.0.14 会
 * 话段）：预热 ready 与 attach 采纳（adopted）都在进程启动
 * 时完成、不是迟到环；diag #3（下拉中段）ThemeView 已实绘
 * 19 元素（drawCalls=1）——内容也不是迟到环；同行
 * chA=0.0/1.0、effA=0.0 才是：两路 alpha 语义经反编译核实
 * 为 time=时钟本体、common=日期/流量等公共元素，宿主分级
 * 呈现（下拉跟踪期 time 恒 1、common 压 0、落定才补 1，
 * diag #4 才两路齐 1），v2.0.12 起的 min() 保守合成让整簇
 * 陪 common 一起迟到。改：有效 alpha 只跟 time 路（见
 * [effectiveShadeAlpha]），容器可见门与 v2.0.14 斜率限制不
 * 变。关闭期间 ThemeView 不重绘（tick 的 isShown 门控、
 * drawCalls 冻结）是实情但非本症：首绘在打开过程中经预抽
 * 补 invalidate 已落帧（diag #3 drawn=19 先于 alpha 到
 * 位），故 tick 口径不动、防 v2.0.5 卡顿回潮。
 *
 * v2.0.21 定案（v2.0.20 真机录屏+日志 probe_1_7uzm）：闪烁
 * 还在，但形态已变——录屏逐帧是「通知已进场、时钟区全透
 * 明，落定后整幅跳出」（二值有/无、无淡入中间帧），收起尾
 * 段有一帧面板背景已透、簇仍全不透明压在桌面上。日志侧：
 * 全部 35 条 diag 采样 wrapper alpha=1.0、effA=1.0、time
 * 路恒 1.0——**alpha 通道不是本版闪烁源**，v2.0.12–17 的
 * alpha 合成补丁已到头。本版闪烁是两件别的事：
 * ①开侧是「画没画好」的时序问题：attach 时 wrapper 按头部
 * 原高挂载（mount 日志 bounds=…,280），多个会话 diag #1
 * 时 drawCalls=0、首绘落在第 1–2 个 tick 之间——首绘只靠
 * 布局 post 与 1 秒 tick 的 invalidate 链驱动，亚秒级的下
 * 拉跟踪期里没有任何机制保证簇已按最终 bounds 绘好，落定
 * 后布局 post 的那发 invalidate 才把整幅画出来，正是录屏
 * 的「迟一拍整幅出现」。
 * ②关侧是可见性没联动：宿主关落定时把头部本体置
 * INVISIBLE 并移回关闭位（关闭态 diag 实证 header vis=4、
 * top 回 141），而 wrapper 是同级 View、不随头部本体可见
 * 性联动；关闭的平移路径又不把 time 路 alpha 写到 0（关
 * 闭中段采样 effA 仍 1.0、通知卡已越过头部下缘），于是
 * 通知栏窗口收尾的最后几帧里簇独自留在原位压桌面。
 * 改法（不动已确认好的横滑跟随与三道门控）：
 * - ThemeView 改位图呈现：内容在主题/尺寸/偏移变化与每秒
 *   先离屏渲进 Bitmap（离屏渲染不依赖窗口可见性与遍历时
 *   机），onDraw 只贴图；预抽里发现位图过期（>900ms 或尺
 *   寸不符）先同步重渲再画——任何一帧只要 wrapper 被合
 *   成，内容必然是完整成品，即小乐要的「直接加载好的」。
 * - 关侧加二值收尾门（[shadeSettledClosed]）：容器可见标
 *   志为 false 且头部本体非 VISIBLE、且不在页面切换中，
 *   判为「面板已关落定」，wrapper 帧频（预抽）联动 GONE；
 *   任一条件解除即恢复 VISIBLE 并先刷新位图。取舍：开侧
 *   若宿主在跟踪前段两个条件同时成立，簇最迟在宿主写
 *   visible=true（展开进度 0.3 处）那帧出现——仍远早于
 *   v2.0.20 的落定后跳出；页面切换期整门豁免，v2.0.11
 *   确认好的横滑 alpha/平移跟随不受影响。
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
    private const val WRAPPER_MARKER = "shadeclock_overlay_wrapper"
    private const val MAX_FAILURES = 8

    /**
     * 开门时间确认窗（v2.0.25）：收尾门从「已关」转「想开」
     * 的判定须持续成立至少这段时间（约半帧）才放行——滤掉
     * 关落定后宿主状态复位把展开比例一次性打回高值的单次
     * 采样尖峰（定案见 [updateCloseTailLatch] 注）。
     */
    private const val OPEN_CONFIRM_MS = 8L

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
    // 场景开关（v2.0.9 分支判定用）：静态布尔字段，经反编译核实
    // （sget-boolean SceneContainerFlag.isSceneEnabled）。
    private const val SCENE_FLAG_CLASS =
        "com.android.systemui.scene.shared.flag.SceneContainerFlag"

    // 控制中心门控（v2.0.4）：类名经反编译核实——HwPanelControlImpl
    // 是页面状态分发者，HnColumeContainer 是头部父容器（镜像字段宿主自维护）。
    private const val PANEL_CONTROL_IMPL_CLASS =
        "com.android.systemui.statusbar.phone.HwPanelControlImpl"
    private const val COLUME_CONTAINER_CLASS =
        "com.android.systemui.statusbar.phone.HnColumeContainer"

    // 页面切换门控（v2.0.10）：类名经反编译核实——
    // HnSwipeSwitchControllerImpl 是通知中心↔控制中心横向切换的
    // 动画引擎（一对 AnimatorSet + 随指 tracking），其
    // swipeToControlCenter / swipeToNotificationCenter 是 fling
    // 提交与程序化切换的汇聚点（动画集在方法内才 start）。
    private const val SWIPE_SWITCH_IMPL_CLASS =
        "com.android.systemui.statusbar.phone.HnSwipeSwitchControllerImpl"

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

    /**
     * 灭屏预挂起（v2.0.13）：ACTION_SCREEN_OFF 置位、
     * ACTION_SCREEN_ON 清位。setState 是落定信号、晚于锁屏
     * 界面实际展示，灭屏广播先到，先按 keyguard 口径把整组
     * 藏掉止闪；亮屏后由 setState 门控对账（真锁屏继续挂起、
     * 无锁直回通知页经 applyGate 恢复）。
     */
    @Volatile
    private var screenOffPreSuspend = false

    /** 灭屏/亮屏接收器是否已注册（进程级只注册一次）。 */
    @Volatile
    private var screenGateReceiverRegistered = false

    /** 控制中心页是否展开（HwPanelControlImpl 分发 hook 跟踪；字段读取兜底见 isControlCenterActive）。 */
    @Volatile
    private var controlCenterExpand = false

    /** 横幅是否呈现中（HeadsUpManagerImpl 聚合 pinned 状态 hook 跟踪；字段直读兜底见 isHeadsUpActive）。 */
    @Volatile
    private var headsUpPinned = false

    /** hook 回调捕获的 HeadsUpManagerImpl 实例（弱引用），供轮询直读其 mHasPinnedNotification 字段。 */
    @Volatile
    private var headsUpManagerRef: WeakReference<Any>? = null

    /**
     * 页面切换过渡中（v2.0.10 置位/清位口径不变）：切换开始信号
     * （notifySwipeStart / 引擎 swipeTo*）置位、落定信号
     * （notifyControlCenterPanelChange / notifySwipeCanceled）
     * 清位。v2.0.11 起置位期间不再挂起——覆盖层走跟随（逐帧
     * alpha/平移同步，见 [installHeaderAnimFollow]），本标志
     * 改作跟随窗口与终态门控（控制中心落定才硬挂起）的判定。
     * 落定页态仍以 controlCenterExpand 为准。
     */
    @Volatile
    private var pageSwitching = false

    /** [pageSwitching] 的置位时刻（uptimeMillis），tick 防卡死兜底用。 */
    @Volatile
    private var pageSwitchSetAtMs = 0L

    /** hook 回调捕获的 HnSwipeSwitchControllerImpl 实例（弱引用），供 tick 兜底直读其 mIsAnimationPlaying / mIsTracking。 */
    @Volatile
    private var swipeControllerRef: WeakReference<Any>? = null

    // 动画跟随（v2.0.11 切换跟随、v2.0.12 扩到面板开合）：类名
    // 经反编译核实——通知头部的全部逐帧动画量都不走 View 属性，
    // 而是经 NotifCenterHeaderInteractor 写进 Compose 状态：
    // 页面切换引擎 HnSwipeSwitchControllerImpl.setViewAlpha、
    // 下拉动画集 HnStatusBarPullAnimImpl、卷帘跟踪与面板控制器
    // 的动画器，最后一律汇聚到该 interactor 的几个漏斗方法
    // （updateAnimValueCommonAlpha / updateAnimValueTimeAlpha /
    // updateContainerVisible / updateContainerTransitionX/Y）。
    private const val HEADER_INTERACTOR_IMPL_CLASS =
        "com.android.systemui.statusbar.notification.panelheader.NotifCenterHeaderInteractorImpl"

    /**
     * 头部动画通道的跟踪值（[installHeaderAnimFollow] 的 hook 逐
     * 帧更新，与 interactor 内 CombineAlpha 的初值对齐）：
     * common/time 两路 alpha、容器可见标志、容器根偏移（px）。
     * 覆盖层是普通 View、不在 Compose 状态里，靠抄这组值合成
     * 与原头部一致的逐帧变换（见 [effectiveShadeAlpha] 与
     * [applyComposedTransform]）。
     */
    @Volatile
    private var shadeCommonAlpha = 1f

    @Volatile
    private var shadeTimeAlpha = 1f

    @Volatile
    private var shadeContainerVisible = true

    /**
     * 下拉跟踪门（v2.0.16）：桌面（Gone）出发的下拉手势正在进行
     * 中——由 [installShadePullGate] hook 的两个手势意图检测器
     * onProgressUpdate 逐帧喂进度，progress>0 即置位、回到 0 或
     * 容器可见标志翻 false（关闭动画落定）时清位。为什么需要它：
     * [shadeContainerVisible] 在首次关闭后就停在 false，而宿主在
     * 后续下拉中要等进度过 0.3 触发展开、头部动画集创建时
     * （setNtfPanelHeaderView）才把它写回 true——只认它当门，
     * 每次下拉的前段覆盖层都被压在 alpha=0（真机 v2.0.15 会话
     * 日志实证：首次下拉前无 false 翻转、后续每次关闭都落
     * false）；进程重启后第一次下拉之所以正常，是因为宿主
     * CombineAlpha 与本跟踪值的初值都是 true、还没被关过。
     * 下拉一开始（progress 从 0 起）就开门，覆盖层从第一帧跟
     * time 路亮起、随面板位置下行，与第一次下拉表现一致。
     *
     * v2.0.17 注：真机日志证明该回调在状态栏下拉路径上从未
     * 触发（见 [effectiveShadeAlpha] 注），本变量与
     * [shadeContainerVisible] 自 v2.0.17 起只作诊断记录、不再
     * 参与 alpha 合成；hook 保留装着（无害，其余手势路径若
     * 喂到仍会打一行日志供核对）。
     */
    @Volatile
    private var shadePullTracking = false

    @Volatile
    private var shadeContainerX = 0f

    @Volatile
    private var shadeContainerY = 0f

    /** 跟随主路径（interactor 漏斗 hook）是否装上；没装上时 OnPreDraw 走 v2.0.11 的 translationX 采样兜底（仅页面切换）。 */
    @Volatile
    private var animFollowHookActive = false

    /**
     * 覆盖层应有的有效 alpha：只跟 **time 路**（v2.0.15 起，
     * v2.0.12–v2.0.14 是取两路较小者；v2.0.12–v2.0.16 另有
     * 容器可见二值门压底，v2.0.17 已拆除，见本注末段）。改的
     * 依据是两路的
     * 语义（反编译 NotifCenterHeaderKt 核实）：time 路驱动的
     * 正是时钟本体（DateAndTime 收集 timeAlpha），common 路
     * 驱动日期/流量等公共元素（收集 commonAlpha 时同场读的
     * 是 dateText）。宿主对两路是**分级呈现**：下拉跟踪期间
     * time 恒 1（原时钟全程在场）、common 被压 0 直到落定才
     * 补上（v2.0.14 真机 diag #3 实数 chA=0.0/1.0、通知卡已
     * 进场而 effA=0；下一拍 #4 才 1.0/1.0）——取小合成等于让
     * 时钟簇陪公共元素一起迟到，正是「通知先到、时钟迟一拍」
     * 的成因。覆盖层整簇的主体就是时钟样式，单 View 也拆不开
     * 两路，跟 time 路即与原时钟同帧。其余过渡不受影响：页面
     * 切换 setViewAlpha 对两路同帧同值、开合 fling 动画集对
     * 两路同写，time 路一条即载全程。（v2.0.17 前此处还有
     * 「收尾 visible=false 的 0 门兜底」一说，已被真机日志
     * 证伪拆除，见本注末段。）
     *
     * v2.0.15 真机又报「重启后第一次下拉是好的、之后又先空白
     * 再闪出」：迟到的不再是 alpha 通道而是这道 visible 门
     * 本身——宿主写 visible=true 的时机（反编译逐点核实）全
     * 在下拉后段：进度过 0.3 展开触发后建头部动画集时
     * setNtfPanelHeaderView、fling 动画集 onAnimationEnd、
     * 场景落定 Idle；而写 false 在关闭动画落定。故第一次关闭
     * 之后 visible 就停在 false，后续每次下拉的前段（跟手
     * 期）整簇被门压死、等门开了才整幅闪出；第一次下拉没被
     * 关过、初值 true 才全程在场。v2.0.16 曾给门加并联的下拉
     * 跟踪门（[shadePullTracking]），v2.0.17 真机日志（重启后
     * 三次下拉）证明这条并联从未导通、且 visible 门本身就是
     * 不稳定源，故整道二值门拆除、alpha 只跟 time 路：
     * - 下拉跟踪门装上了（日志 `shade pull gate hooks
     *   installed (2/2 ...)`）但整个会话 `shade pull tracking`
     *   一次都没出现、10 条 diag 里 pullTrk 恒 false——状态栏
     *   下拉的真实路径不经过那两个检测器的 onProgressUpdate，
     *   并联门从未开过；
     * - visible 标志在该会话翻转约 15 次，含面板已开/打开中
     *   的 false 段——第二、三拉的空白与它逐段对位（二拉中段
     *   缺席、三拉面板已开头部仍空）；
     * - 决定性反证：面板全关时段的 diag（首拉前 State #1、
     *   中途重挂载后 State #2 的 #1–#5）里 wrapper 都是
     *   vis=VISIBLE、alpha=1.0、effA=1.0 且持续数十秒，而小乐
     *   录屏同期的桌面画面干净——覆盖层是通知栏窗口内的同级
     *   View，面板收起时窗口整体隐藏，alpha=1 也画不出来，
     *   v2.0.12 担心的「全关后残留桌面」在本机构建上不成立；
     *   关闭动画期间宿主动画器又把两路 alpha 同写到 0（见
     *   v2.0.12 合成注），整簇随面板淡出。
     * [shadeContainerVisible] 与 [shadePullTracking] 自此只作
     * 诊断记录（diag 的 visFlag/pullTrk 字段与翻转日志保留），
     * 不再参与合成。
     */
    private fun effectiveShadeAlpha(): Float =
        shadeTimeAlpha.coerceIn(0f, 1f)

    /** 预热好的主题（install 阶段后台线程 load + 位图预解码完成），attach 时按 (themeId, stamp) 吻合采纳。 */
    private class PreheatedTheme(
        val themeId: String,
        val stamp: Long,
        val renderer: ThemeRenderer,
    )

    @Volatile
    private var preheatedTheme: PreheatedTheme? = null

    @Volatile
    private var preheatInFlight = false

    private class State(val header: ViewGroup) {
        var wrapper: FrameLayout? = null
        var mountParent: ViewGroup? = null
        var mountBounds: IntArray? = null
        var themeView: ThemeView? = null
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
        /** 帧频断言监听（v2.0.11）：头部 OnPreDraw——同框纠正 + 首绘补 invalidate + 跟随兜底采样。 */
        var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
        /** 预抽监听当前登记在哪个观察者上（v2.0.23）：头部重挂载会换观察者，登记要跟上，见 ensurePreDrawListener。 */
        var preDrawObserver: ViewTreeObserver? = null
        /** 头部 detach 监听（v2.0.23，只装一次）：detach 时清 preDrawObserver，逼下次 ensure 重新登记。 */
        var headerAttachListener: View.OnAttachStateChangeListener? = null
        /** Compose 子视图被宿主改回可见后、我们重新压回的纠正次数（有界日志用，v2.0.11）。 */
        var childCorrectionLogCount = 0
        var diagTicks = 0
        /** 挂起中（锁屏或控制中心页）：覆盖层整组不可见、原头部保持原样，tick 继续跑以便条件解除后自动恢复。 */
        var suspended = false
        /** 挂起原因："keyguard" / "control-center" / "heads-up"，日志与恢复判断用（页面切换自 v2.0.11 起走跟随、不再挂起）。 */
        var suspendReason: String? = null
        /** 通知栈（NSSL）及其 AmbientState 的反射句柄（找到后缓存）。 */
        var stackScroller: View? = null
        var stackAmbient: Any? = null
        /**
         * 来源追踪（v2.0.8）：系统最近一次经写入口写下的 topPadding
         * 原始值（未加增量）。我们自己的调整是直接写字段、不经写
         * 入口方法，故入口 after 里读到的（且不等于我们刚写的目标
         * 的）值必然是系统原值——不再做任何窗口猜测。
         */
        var lastSystemOriginal: Int? = null
        /** 我们最近一次写下的调整目标值；恢复时只认这个值。 */
        var lastAdjustedWritten: Int? = null
        /** 写入口调整日志计数（有界，避免刷屏）。 */
        var interceptLogCount = 0
        /** 写入口经过证据日志计数（v2.0.9，上限 40 行/进程）。 */
        var stackPassLogCount = 0
        /** 延迟栈顶复查是否已在队列里（v2.0.7，防重复排队）。 */
        var stackReconcilePosted = false
        /** 延迟复查的连续重试次数（v2.0.7，上限后交给 tick 兜底）。 */
        var stackReconcileRetries = 0
        /** 已成功应用到渲染器的垂直偏移（dp）与对应主题（v2.0.7）；null=尚未应用。 */
        var appliedOffsetDp: Int? = null
        var offsetThemeId: String? = null
        /** 已成功应用到渲染器的水平偏移（dp）与对应主题（v2.0.23）；与垂直分开记，钳制随主题簇宽变、不能共用主题标记。 */
        var appliedOffsetXDp: Int? = null
        var offsetXThemeId: String? = null
        /**
         * 栈顶写回重入保护（v2.0.5）：置位期间拦截/兜底一律跳过——
         * 我们调用的系统更新组会同步再触发写入口 hook，不挡住就
         * 会级联自激（v2.0.4 真机卡死的主因之一）。
         */
        var stackAdjusting = false
        /** tick 计数（心跳日志用，v2.0.5）。 */
        var tickCount = 0
        /**
         * 跟随 alpha 的已应用值与应用时刻（v2.0.14 斜率限制用）：
         * followAlpha < 0 表示尚无应用值、下一次应用直接对齐
         * 目标（attach/恢复时复位到 -1，见 applyComposedTransform）。
         */
        var followAlpha = -1f
        var followAlphaAtMs = 0L
        /** 二值收尾门（v2.0.21）的翻转日志计数（有界，防刷屏）。 */
        var gateLogCount = 0
        /** 二值收尾门上次应用的判定（null=尚未应用），翻转才记日志/刷新。 */
        var gateClosed: Boolean? = null
        /**
         * 收尾早关 latch（v2.0.24）：关闭行程中已按「宿主先藏
         * 头部」提前关门——置位后保持关，直到展开比例回升超
         * 过触发点（手势反转）或容器可见标志回到 true 或面板
         * 落定（比例 0，此时由落定判据接管）。见
         * [updateCloseTailLatch]。
         */
        var gateTailLatched = false
        /** 早关 latch 的触发比例（反转解除的基准）。 */
        var gateTailFraction = 0f
        /**
         * 开门时间确认的起点（v2.0.25）：收尾门已关时出现
         * 第一发「想开」评估的时刻，-1=无待确认；持续成立
         * ≥ [OPEN_CONFIRM_MS] 才真开门，见
         * [applyWrapperVisibility]。
         */
        var gateOpenConfirmAtMs = -1L
        /**
         * 早关 latch 解除（手势反转）的时间确认起点
         * （v2.0.25）：比例越过解除阈值的第一发评估时刻，
         * -1=无待确认，见 [updateCloseTailLatch]。
         */
        var gateLatchReleaseAtMs = -1L
        /** 上一次采样的展开比例（判「正在下落」用），null=尚无采样。 */
        var gateLastFraction: Float? = null
        /** 面板本体（头部祖先 NotificationPanelView）与展开比例字段句柄（v2.0.22 收尾门用，懒定位、定位失败保持 null 走旧口径）。 */
        var panelView: View? = null
        var panelFractionField: java.lang.reflect.Field? = null
        var panelFractionLookupDone = false
    }

    /**
     * 面板展开比例（v2.0.22）：头部祖先 NotificationPanelView
     * 的祖类 PanelView 字段 mExpandedFraction（反编译核实存
     * 在：PanelView 是 HwPanelView 的父类、NotificationPanelView
     * 的祖父类）——0=面板完全收起、>0=展开中/已展开。它是物
     * 理量：宿主在关落定后给下次打开做的状态复位（容器可见
     * 标志写回 true、两路 alpha 写回 1）不会动它。祖先或字段
     * 读不到时返回 null，收尾门退回 v2.0.21 口径（fail-open、
     * 不劣化）。
     */
    private fun panelExpandedFraction(st: State): Float? {
        return try {
            var panel = st.panelView
            if (panel == null) {
                var v: View? = st.header.parent as? View
                while (v != null) {
                    if (v.javaClass.name == PANEL_CLASS_NAME) {
                        panel = v
                        break
                    }
                    v = v.parent as? View
                }
                if (panel == null) return null
                st.panelView = panel
            }
            if (!st.panelFractionLookupDone) {
                st.panelFractionLookupDone = true
                st.panelFractionField = try {
                    XposedHelpers.findField(panel.javaClass, "mExpandedFraction")
                } catch (t: Throwable) {
                    null
                }
            }
            val field = st.panelFractionField ?: return null
            (field.get(panel) as? Number)?.toFloat()
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 二值收尾门（v2.0.21 立、v2.0.22 换判据）：面板是否已
     * 「关落定」。v2.0.21 的判据是宿主两个落定事实的合取
     * （容器可见标志=false 且头部本体非 VISIBLE），真机日志
     * （v2.0.21 会话）证明它被宿主的关后复位击穿：每次关闭
     * 落定后宿主都把容器可见标志写回 true（给下次打开备的
     * 初值，alpha 同步复位 1），门随即按「open」把 wrapper
     * 顶回 VISIBLE——面板已关、样式却以 VISIBLE+alpha=1 留
     * 在通知栏窗口里，窗口收尾合成未完的那几帧就直接闪到
     * 桌面上（真机录屏：桌面已干净一帧后时钟簇原位全不透
     * 明重现约 6 帧、随后随窗口隐藏消失）。v2.0.22 改判物
     * 理量：展开比例读得到时，收尾门只认它——比例为 0 即
     * 关落定，复位写什么都不许重开；比例 >0（下拉第一帧面
     * 板已开始展开、窗口才会被合成）即按开处理，与
     * v2.0.21「第一帧就是成品」的开侧口径衔接（重开时
     * applyWrapperVisibility 照旧先刷新位图）。读不到比例
     * 时退回 v2.0.21 合取口径。页面切换期整门豁免（横滑跟
     * 随是 v2.0.11 确认好的行为，落定到控制中心由控制中
     * 心门控挂起、不靠这道门）。
     *
     * v2.0.24 补「收尾早关」：比例读得到时，除落定（比
     * 例 0）外，关闭行程中宿主先行隐藏头部内容的那一刻
     * 也关门——[updateCloseTailLatch] 维护的 latch；定案
     * 与开侧不认该标志的理由见该函数注。
     *
     * v2.0.25：本函数的判定不变，但从「已关」转「开」
     * 的应用要过 [applyWrapperVisibility] 的时间确认
     * （复位尖峰单次采样不许开门），latch 解除同理（见
     * [updateCloseTailLatch] 注的定案）。
     */
    private fun shadeSettledClosed(st: State): Boolean {
        if (pageSwitching) return false
        val fraction = panelExpandedFraction(st)
        if (fraction != null) return fraction <= 0f || st.gateTailLatched
        return !shadeContainerVisible &&
            st.header.visibility != View.VISIBLE
    }

    /**
     * 收尾早关 latch 的维护（v2.0.24，只在
     * [applyWrapperVisibility] 里调，预抽帧频）：真机
     * （v2.0.23 录屏+日志）定案——关闭行程里原头部的退出
     * 不是 alpha（diag 中段实数两路恒 1.0）、也不是面板落
     * 定，而是宿主中途调 updateContainerVisible(false) 把
     * 头部内容撤下（日志里该翻转落在展开比例 1.0 与 0.58
     * 两次采样之间）；覆盖层只认比例 0 才关门，于是独自以
     * 全不透明留在面板顶部、其余内容都退场后还压在桌面上
     * 约 16 帧，末段随面板高度塌缩被上移裁成残条——即小
     * 乐报的「返回闪烁」。修法：关闭行程中（比例在下落）
     * 容器可见标志一旦翻 false，与原头部同刻关门并 latch
     * 住，不等比例归零。开侧绝不认这个标志（它翻 true 迟
     * 到进度 0.3 之后，v2.0.15/16 的教训）：置位条件带
     * 「正在下落」，打开行程比例上行永不触发；面板全开静
     * 置时标志的杂翻（页面切换落定等场景、比例不动）也不
     * 触发。解除：比例回升超过触发点 0.02（手势反转上
     * 行）、标志回到 true、或比例归零（落定判据接管，
     * latch 顺手清掉）。页面切换期整门豁免、latch 不动。
     *
     * v2.0.25 定案（v2.0.24 真机录屏+日志）：latch 本身触
     * 发成功（日志实证在比例 0.82 处与原头部同刻关门），
     * 但紧接着一发评估里比例读数跳回 1.0——当时容器标志
     * 仍 false、头部本体已 GONE，面板只可能更关、不可能
     * 在一发评估内真重开：这是关落定后宿主状态复位（比
     * 例与 alpha/标志同批被写回下次打开初值，v2.0.22 已
     * 实证同批复位）打出的单次采样尖峰。旧解除条件是单
     * 次采样越过触发点+0.02 即解除，尖峰正好冒充「手势
     * 反转」把 latch 解掉、门随即按 open 把整簇以全不透
     * 明贴回已关的桌面（录屏落定后整簇重现约一帧，与该
     * 行日志逐项对位）。另一轮关闭里标志翻转时比例已
     * ≈0、门在同一调用栈内靠落定判据关掉（tail=false 属
     * 正常、无 latch 窗口）。修法：解除与开门都加时间确
     * 认——判定须持续成立 ≥ [OPEN_CONFIRM_MS]（约半帧）
     * 才算数；手势反转/真实下拉是持续过程、评估帧频密
     * 集，确认只迟约一帧；尖峰等不到第二发评估就回落、
     * 确认随之撤销。本函数管 latch 解除的确认，开门侧的
     * 确认在 [applyWrapperVisibility]。
     */
    private fun updateCloseTailLatch(st: State) {
        if (pageSwitching) return
        val fraction = panelExpandedFraction(st)
        if (fraction == null) {
            st.gateLastFraction = null
            return
        }
        val last = st.gateLastFraction
        st.gateLastFraction = fraction
        when {
            fraction <= 0f || shadeContainerVisible -> {
                st.gateTailLatched = false
                st.gateLatchReleaseAtMs = -1L
            }
            st.gateTailLatched -> {
                if (fraction > st.gateTailFraction + 0.02f) {
                    // 解除要时间确认（v2.0.25）：单次采样越过
                    // 阈值只记起点，持续 ≥ OPEN_CONFIRM_MS 的
                    // 第二发评估才真解除（定案见函数注）。
                    val now = SystemClock.uptimeMillis()
                    val since = st.gateLatchReleaseAtMs
                    if (since < 0L) {
                        st.gateLatchReleaseAtMs = now
                    } else if (now - since >= OPEN_CONFIRM_MS) {
                        st.gateTailLatched = false
                        st.gateLatchReleaseAtMs = -1L
                    }
                } else {
                    st.gateLatchReleaseAtMs = -1L
                }
            }
            last != null && fraction < last - 0.0005f -> {
                st.gateTailLatched = true
                st.gateTailFraction = fraction
            }
        }
    }

    /**
     * 按二值收尾门应用 wrapper 可见性（预抽帧频、漏斗 hook、
     * tick 三处共用）：关落定 → GONE（与头部本体同帧撤下，
     * 不再留簇压桌面）；解除 → VISIBLE 并先把位图刷新鲜、
     * 再由调用方接合成变换。挂起态不走这里（suspendOverlay
     * 已整组 GONE）。
     */
    private fun applyWrapperVisibility(st: State) {
        val wrapper = st.wrapper ?: return
        if (st.suspended) {
            if (wrapper.visibility != View.GONE) wrapper.visibility = View.GONE
            st.gateClosed = null
            return
        }
        updateCloseTailLatch(st)
        var closed = shadeSettledClosed(st)
        // 开门时间确认（v2.0.25）：门已关落定时重新打开，
        // 须「想开」判定持续成立 ≥ OPEN_CONFIRM_MS（约半
        // 帧）才放行——关落定后宿主复位打出的比例尖峰只
        // 持续一发评估（定案见 updateCloseTailLatch 注），
        // 等不到确认就回落；真实下拉/手势反转是持续过
        // 程、评估帧频密集，确认只迟约一帧，且位图早已
        // 离屏渲好、第一帧仍是成品。挂起恢复与 attach 首
        // 应用不走确认（gateClosed 非 true：挂起分支已置
        // null），行为与旧版一致。
        if (!closed && st.gateClosed == true) {
            val now = SystemClock.uptimeMillis()
            val since = st.gateOpenConfirmAtMs
            if (since < 0L) {
                st.gateOpenConfirmAtMs = now
                closed = true
            } else if (now - since < OPEN_CONFIRM_MS) {
                closed = true
            } else {
                st.gateOpenConfirmAtMs = -1L
            }
        }
        if (closed) st.gateOpenConfirmAtMs = -1L
        if (st.gateClosed == closed && wrapper.visibility ==
            if (closed) View.GONE else View.VISIBLE
        ) {
            return
        }
        st.gateClosed = closed
        val want = if (closed) View.GONE else View.VISIBLE
        if (want == View.VISIBLE && wrapper.visibility != View.VISIBLE) {
            // 开门同帧断言（v2.0.23）：wrapper 亮相的这一帧，
            // 原头部子视图必须先已是 INVISIBLE——宿主在场景
            // 激活时改回可见的窗口与开门同帧，先压再亮。
            hideComposeChildren(st.header, st, "gate")
        }
        if (wrapper.visibility != want) {
            wrapper.visibility = want
            if (st.gateLogCount < 20) {
                st.gateLogCount++
                logFn(
                    "overlay gate: ${if (closed) "settled-closed -> wrapper GONE" else "open -> wrapper VISIBLE"} " +
                        "(visFlag=$shadeContainerVisible, headerVis=${st.header.visibility}, " +
                        "expF=${panelExpandedFraction(st)}, tail=${st.gateTailLatched})"
                )
            }
        }
        if (!closed) {
            // 重新出现的第一帧就要是成品：先离屏渲好（过期才真渲）
            st.themeView?.refreshContent()
        }
    }

    /**
     * 自绘层（v2.0.21 改位图呈现）：内容先离屏渲进一张与视图
     * 同尺寸的 Bitmap，onDraw 只把位图贴出来。为什么：直绘版
     * 的首绘只能靠布局 post / tick 的 invalidate 链在窗口遍
     * 历里落帧，下拉跟踪期（亚秒、无 tick）没人保证它画好，
     * 真机表现为落定后整幅跳出（见类头 v2.0.21 注）。离屏渲
     * 染不依赖窗口可见性与遍历时机——主题装载、尺寸落定、
     * 每秒刷新都先把成品渲好，wrapper 只要被合成出来，贴的
     * 必然是完整一帧，即「直接加载好的」。
     */
    private class ThemeView(
        context: Context,
        private val envProvider: () -> RenderEnv?,
        private val renderer: ThemeRenderer,
        private val onContentHeightChanged: () -> Unit,
        private val onDrawError: (Throwable) -> Unit,
    ) : View(context) {

        private var lastContentBottom = -1f

        /** 离屏成品位图（与视图当前尺寸一致）；null=还没渲出过成品。 */
        private var bitmap: Bitmap? = null

        /** 最近一次离屏渲染的时刻（uptimeMillis），过期判定用。 */
        var lastRenderAtMs = 0L
            private set

        /** 诊断计数：onDraw（贴图）被调用次数（v2.0.2 overlay diag 用）。 */
        var drawCalls = 0
            private set

        /** 诊断计数：离屏渲染次数（v2.0.21），与 drawCalls 对照看内容是否先于呈现就绪。 */
        var renderCount = 0
            private set

        /** 最近一帧实际绘制的元素数；env 缺失时为 0。 */
        var lastDrawnElements = 0
            private set

        /** 最近一帧是否因 env 缺失（视图/头部尚无宽度）而未绘制。 */
        var lastEnvMissing = false
            private set

        /** 位图是否已过期：没渲过、尺寸与视图不符、或渲好已超过 900ms（秒数要逐秒走）。 */
        fun contentStale(): Boolean {
            val bmp = bitmap ?: return true
            if (bmp.isRecycled || bmp.width != width || bmp.height != height) return true
            return SystemClock.uptimeMillis() - lastRenderAtMs >= 900L
        }

        /**
         * 离屏渲染一帧成品（主线程调用）。force=false 时未过
         * 期直接跳过（预抽帧频调用靠它去抖）。返回是否真渲了。
         * 尺寸未定（宽高 0）或 env 未就绪时不渲、保留旧成品
         * （宁可贴上一帧，也不贴空白）。
         */
        fun refreshContent(force: Boolean = false): Boolean {
            if (!force && !contentStale()) return false
            val w = width
            val h = height
            if (w <= 0 || h <= 0) {
                lastEnvMissing = true
                return false
            }
            val env = try {
                envProvider()
            } catch (t: Throwable) {
                onDrawError(t)
                return false
            }
            if (env == null) {
                lastEnvMissing = true
                return false
            }
            return try {
                var bmp = bitmap
                if (bmp == null || bmp.isRecycled || bmp.width != w || bmp.height != h) {
                    bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bitmap = bmp
                }
                val canvas = Canvas(bmp)
                canvas.drawColor(0, PorterDuff.Mode.CLEAR)
                renderer.draw(canvas, env)
                lastEnvMissing = false
                lastDrawnElements = renderer.lastDrawnElements
                renderCount++
                lastRenderAtMs = SystemClock.uptimeMillis()
                val cb = renderer.contentBottomPx
                if (abs(cb - lastContentBottom) > 2f) {
                    lastContentBottom = cb
                    post { onContentHeightChanged() }
                }
                true
            } catch (t: Throwable) {
                onDrawError(t)
                false
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // 尺寸一变立刻按新尺寸重渲（同一布局 pass 内完成），
            // 不等 tick——wrapper 从原高长到主题高时内容同步跟上。
            if (w > 0 && h > 0 && (w != oldw || h != oldh)) {
                refreshContent(force = true)
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            drawCalls++
            // 贴图前最后一道保险：还没有成品（或尺寸对不上）
            // 就当场离屏渲一帧再贴，绝不贴空白。
            if (bitmap == null || bitmap?.isRecycled == true ||
                bitmap?.width != width || bitmap?.height != height
            ) {
                refreshContent(force = true)
            }
            val bmp = bitmap
            if (bmp != null && !bmp.isRecycled) {
                canvas.drawBitmap(bmp, 0f, 0f, null)
            }
        }

        /** 卸载时回收位图，别在 SystemUI 里留着大块内存。 */
        fun releaseBitmap() {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
            bitmap = null
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
        installPageSwitchGate(lpparam, log)
        installHeadsUpGate(lpparam, log)
        installStackWriteInterceptor(lpparam, log)
        installHeaderAnimFollow(lpparam, log)
        installShadePullGate(lpparam, log)
        // 首显预热（v2.0.11）：进程启动就在后台把 stamp 读取与
        // 主题加载（解析/字体/位图预解码）做完，attach 零 IO。
        startPreheat()
    }

    /**
     * 主题预热（v2.0.11）：后台线程读 stamp → ThemeRenderer.load
     * → warmBitmapCache，结果存 [preheatedTheme] 供 attach 按
     * (themeId, stamp) 吻合时整套采纳（adoptFrom）。失败只记
     * 一行、不存缓存，attach 走原同步 load——预热不是唯一路径；
     * attach 发现戳记不吻合（主题/设置刚改）时也会再排一次，
     * 让下一次挂载命中。同一时刻只跑一条预热线程。
     */
    private fun startPreheat() {
        if (preheatInFlight) return
        preheatInFlight = true
        val t = Thread({
            try {
                preheatWorker()
            } catch (t: Throwable) {
                logFn("overlay preheat failed: ${t.javaClass.name}: ${t.message}")
            } finally {
                preheatInFlight = false
            }
        }, "shadeclock-preheat")
        t.isDaemon = true
        t.start()
    }

    private fun preheatWorker() {
        // 戳记候选全貌留证（v2.0.20）：必须在 readStamp 的收敛
        // 删除之前取——多于一份候选时打一行，下次再出现「选
        // 了不跟随」可直接看清目录里有几份、各自的 themeId
        // 与时间戳，不用再靠猜。
        val candSummary = StampStore.stampCandidatesSummary()
        if (candSummary.isNotEmpty()) {
            logFn("overlay stamp candidates: $candSummary")
        }
        val stamp = StampStore.readStamp() ?: return
        if (stamp.topStyle != StampStore.TOP_STYLE_THEME || stamp.themeId.isEmpty()) return
        val entry = StampStore.themeEntry(stamp.themeId) ?: return
        val renderer = ThemeRenderer { message -> logFn(message) }
        renderer.showBackgroundImage = stamp.showBackground
        renderer.showWidgets = stamp.showWidgets
        if (!renderer.load(entry)) return
        renderer.warmBitmapCache()
        preheatedTheme = PreheatedTheme(stamp.themeId, stamp.stamp, renderer)
        val srcNote = StampStore.lastStampSource
            ?.takeIf { it != StampStore.STAMP_NAME }
            ?.let { ", stampSource=$it" } ?: ""
        logFn("overlay preheat ready: theme=${stamp.themeId}, stamp=${stamp.stamp}$srcNote")
    }

    /**
     * 动画跟随的逐帧进度源（v2.0.11 切换跟随、v2.0.12 扩到面板
     * 开合）：hook NotifCenterHeaderInteractorImpl 的五个漏斗
     * 方法——反编译定案（SystemUI 17.0.0.1）：
     * - 页面切换：HnSwipeSwitchControllerImpl.setViewAlpha 对
     *   头部同帧同值写 common+time 两路 alpha；
     * - 面板开合（fling/程序化）：HnStatusBarPullAnimImpl
     *   .createNotificationCenterHeaderAnimatorSet 的 200ms 动画
     *   器同帧同值写两路 alpha（开→1、关→0），448/360ms 动画器
     *   写 updateContainerTransitionY（内容根偏移，px）；
     * - 卷帘跟踪/手势收合：两路 alpha 分别被单独写，收尾靠
     *   updateContainerVisible(false) 藏头部（alpha 反而复位
     *   1）；HnPanelViewControllerImpl 的展开动画器也写两路。
     * 即所有驱动最后都过这组漏斗——hook 漏斗本身、常时跟踪，
     * 就不用逐个驱动去猜信号。after 里更新跟踪值并立即把合成
     * 变换（见 [applyComposedTransform]）同步给各 State 的
     * wrapper；挂起中的 State 跳过（终态门控优先，其 wrapper
     * 是 GONE，恢复时 attach 会按当前跟踪值接上）。hook 在宿
     * 主 UI 线程回调（动画/触摸都在主线程），写 View 属性即
     * 同帧生效。任一 hook 装失败只记一行；全没装上时
     * OnPreDraw 的 v2.0.11 采样兜底接管页面切换（见
     * ensureWrapper），面板开合跟随随之失效但不影响其余功能。
     * resetAnimState 不单独 hook：它内部就是调上面两个 alpha
     * 方法写 1，方法级 hook 自然覆盖。
     */
    private fun installHeaderAnimFollow(
        lpparam: XC_LoadPackage.LoadPackageParam,
        log: (String) -> Unit,
    ) {
        val implClass = try {
            XposedHelpers.findClass(HEADER_INTERACTOR_IMPL_CLASS, lpparam.classLoader)
        } catch (t: Throwable) {
            log(
                "header anim follow unavailable (interactor class missing), " +
                    "pre-draw fallback only: ${t.javaClass.name}: ${t.message}"
            )
            return
        }
        var installed = 0

        fun hookFloat(method: String, apply: (Float) -> Unit) {
            try {
                XposedHelpers.findAndHookMethod(
                    implClass, method, Float::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val f = (param.args.getOrNull(0) as? Float) ?: return
                            apply(f)
                            applyComposedToAllStates()
                        }
                    }
                )
                installed++
            } catch (t: Throwable) {
                log("header anim follow hook failed for $method: ${t.javaClass.name}: ${t.message}")
            }
        }

        hookFloat("updateAnimValueCommonAlpha") { f ->
            shadeCommonAlpha = f.coerceIn(0f, 1f)
        }
        hookFloat("updateAnimValueTimeAlpha") { f ->
            shadeTimeAlpha = f.coerceIn(0f, 1f)
        }
        hookFloat("updateContainerTransitionX") { f -> shadeContainerX = f }
        hookFloat("updateContainerTransitionY") { f -> shadeContainerY = f }
        try {
            XposedHelpers.findAndHookMethod(
                implClass, "updateContainerVisible", Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val v = (param.args.getOrNull(0) as? Boolean) ?: return
                        if (!v) {
                            // 关闭落定：下拉跟踪记录一并清位（v2.0.16
                            // 起如此；v2.0.17 起两者只作诊断记录、
                            // 不再参与合成，见 effectiveShadeAlpha 注）。
                            shadePullTracking = false
                        }
                        if (v != shadeContainerVisible) {
                            shadeContainerVisible = v
                            // 翻转逐次记一行供真机核对跟随链。注意
                            // 它并不低频：v2.0.12/13 真机日志里过渡
                            // 期会成串交替翻转——这正是 v2.0.14 在
                            // applyComposedTransform 加斜率限制的
                            // 原因（标志本身照常即时跟踪，砸到
                            // wrapper 上的跳变由斜率封顶吸收）。
                            logFn("header container visible -> $v")
                        }
                        if (v) {
                            // 场景激活同步断言（v2.0.23）：宿主正是
                            // 在这次翻转前后把原头部子视图重新置
                            // 可见（下拉头两帧同框漏出的源头）——
                            // 不等预抽/布局，在翻转的同一调用栈里
                            // 先压回去，再让跟随变换把 wrapper 亮
                            // 出来。
                            val snapshot = synchronized(states) { states.values.toList() }
                            for (s in snapshot) {
                                if (s.suspended) continue
                                try {
                                    hideComposeChildren(s.header, s, "funnel")
                                } catch (t: Throwable) {
                                    // 断言失败不许影响宿主动画
                                }
                            }
                        }
                        applyComposedToAllStates()
                    }
                }
            )
            installed++
        } catch (t: Throwable) {
            log(
                "header anim follow hook failed for updateContainerVisible: " +
                    "${t.javaClass.name}: ${t.message}"
            )
        }
        if (installed > 0) {
            animFollowHookActive = true
            log("header anim follow hooks installed ($installed/5 on NotifCenterHeaderInteractorImpl)")
        } else {
            log("header anim follow hooks all failed, pre-draw translationX sampling fallback")
        }
    }

    /**
     * 下拉跟踪门（v2.0.16）：hook 场景下拉/收起的两个手势意图
     * 检测器 onProgressUpdate(float)——反编译 StatusBarPullAnim
     * BinderImpl 的场景状态收集器核实：与 Gone 相关的场景过渡
     * 在跟踪与 fling 期间逐帧把 progress 喂给检测器（从桌面出
     * 发的下拉喂 PullDownGestureIntentDetector、其余 Gone 相关
     * 收合喂 CollapseGestureIntentDetector），progress 从 0 起
     * 第一帧就到，比 interactor 的 visible=true（进度 0.3 展
     * 开触发后才写）早一整段跟手期。progress>0 即开门
     * [shadePullTracking] 并立即重跑合成，覆盖层在下拉第一帧
     * 就跟 time 路亮起；progress 回到 0（取消回弹到顶）即清
     * 位。关闭落定的清位在 updateContainerVisible(false) 的
     * 同一处（见 installHeaderAnimFollow）。任一 hook 装失败
     * 只记一行，其余功能不受影响。
     *
     * v2.0.17 注：真机日志（重启后三次下拉）证明该回调在状态
     * 栏下拉路径上从未触发——两个检测器虽在类与方法层面装上
     * 了（2/2），宿主的实际下拉并不经它们。本 hook 自
     * v2.0.17 起只作诊断保留，[shadePullTracking] 不再参与
     * alpha 合成（见 [effectiveShadeAlpha] 注）。
     */
    private fun installShadePullGate(
        lpparam: XC_LoadPackage.LoadPackageParam,
        log: (String) -> Unit,
    ) {
        var installed = 0
        for (className in listOf(
            "com.android.systemui.statusbar.phone.PullDownGestureIntentDetector",
            "com.android.systemui.statusbar.phone.CollapseGestureIntentDetector",
        )) {
            try {
                val detectorClass = XposedHelpers.findClass(className, lpparam.classLoader)
                XposedHelpers.findAndHookMethod(
                    detectorClass, "onProgressUpdate", Float::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val f = (param.args.getOrNull(0) as? Float) ?: return
                            val active = f > 0f
                            if (active != shadePullTracking) {
                                shadePullTracking = active
                                logFn("shade pull tracking -> $active (progress=$f)")
                                applyComposedToAllStates()
                            }
                        }
                    }
                )
                installed++
            } catch (t: Throwable) {
                log(
                    "shade pull gate hook failed for $className: " +
                        "${t.javaClass.name}: ${t.message}"
                )
            }
        }
        if (installed > 0) {
            log("shade pull gate hooks installed ($installed/2 gesture intent detectors)")
        } else {
            log("shade pull gate hooks all failed, visible-flag gate only (v2.0.15 behavior)")
        }
    }

    /** 把合成变换同步给全部未挂起的 State（漏斗 hook 与落定复位共用）。 */
    private fun applyComposedToAllStates() {
        val snapshot = synchronized(states) { states.values.toList() }
        for (st in snapshot) {
            if (st.suspended) continue
            try {
                // 二值收尾门先于变换（v2.0.21）：容器可见标志的
                // 翻转就在这条 hook 链上进来，关落定当场联动。
                applyWrapperVisibility(st)
                applyComposedTransform(st)
            } catch (t: Throwable) {
                // 跟随失败不许影响宿主动画
            }
        }
    }

    /**
     * 合成变换（v2.0.12，两路过渡的统一合成口径）：原头部的
     * 视觉变换 = 头部 View 自身变换 ∘ Compose 内容变换——
     * 页面切换引擎把平移写在头部 View 的 translationX 上、
     * alpha 写进 interactor；面板开合把 alpha 与内容根偏移都
     * 写进 interactor、卷帘另把收尾平移写在头部 View 上。
     * 覆盖层是同级普通 View，两层都得自己抄：
     * - alpha ＝ [effectiveShadeAlpha]（v2.0.15 起只跟 time
     *   路——时钟本体通道；与驱动无关——谁在写漏斗就跟谁，
     *   页面切换与面板开合不会互相打架，因为它们写的是同一
     *   组通道）；落到 wrapper 前再过一道斜率限制（v2.0.14，
     *   见函数内注）；
     * - translationX ＝ 头部 View 平移 + 容器根偏移 X；
     * - translationY ＝ 头部 View 平移 + 容器根偏移 Y。
     * 稳态（面板全开、无切换）下跟踪值恒为 alpha 通道 1、
     * 可见、偏移 0、头部平移 0，合成结果就是恒等变换，与
     * v2.0.11 以前稳态强制复位的口径一致。只在值真变化时写。
     */
    private fun applyComposedTransform(st: State) {
        val wrapper = st.wrapper ?: return
        // alpha 斜率限制（v2.0.14，修进出通知中心闪烁）：合成目标
        // 曾有两个硬跳变输入——容器可见标志是二值门（日志实证它在
        // 过渡期会成串交替翻转，每翻一次旧口径就把 alpha 在 0 与
        // 通道值之间砸一个来回；该门已于 v2.0.17 拆除）；卷帘跟踪
        // 时两路 alpha 又是分开写的，min() 跟着先跳的那一路走
        // （真机 diag 抓到开合中段 effA 在相邻两秒间 0↔1 直跳、
        // 平移还在动；min() 合成已于 v2.0.15 改为只跟 time 路）。
        // 斜率限制保留作通道侧毛刺的兜底平滑。故已应用值
        // 不许直跳目标：按真实时间把每步移动量封顶为「全程
        // 90ms」——宿主自己的动画器都是 200ms 起、跟踪渐变是
        // 手指速度，都比封顶慢、原样通过；单帧毛刺（可见标志
        // 一帧假翻、收尾 alpha 复位 1 抢在 visible=false 前一两
        // 帧到达）只剩一个浅坑/小包，不再整幅闪。首应用与
        // attach/恢复（followAlpha 被复位）直接对齐，不留残影。
        val target = effectiveShadeAlpha()
        val now = SystemClock.uptimeMillis()
        val last = st.followAlpha
        val a = if (last < 0f) {
            target
        } else {
            val dtMs = (now - st.followAlphaAtMs).coerceIn(0L, 250L)
            val maxDelta = dtMs * (1f / 90f)
            when {
                target > last -> min(target, last + maxDelta)
                target < last -> max(target, last - maxDelta)
                else -> last
            }
        }
        st.followAlpha = a
        st.followAlphaAtMs = now
        if (wrapper.alpha != a) wrapper.alpha = a
        val tx = st.header.translationX + shadeContainerX
        if (wrapper.translationX != tx) wrapper.translationX = tx
        val ty = st.header.translationY + shadeContainerY
        if (wrapper.translationY != ty) wrapper.translationY = ty
    }

    /**
     * 跟随兜底（v2.0.11，OnPreDraw 帧回调里采样）：跟随主 hook
     * 没装上时，按头部 translationX 占面板宽的比例换算通知页
     * alpha——引擎自带的 calculateNotificationCenterAlpha 就是
     * (面板宽 − |位移|) / 面板宽，与方向无关、双向对称。面板
     * 宽取头部父容器宽（即两页滑动的总行程量级）。v2.0.12 起
     * 兜底范围仍只限页面切换（面板开合的通道在 interactor 里、
     * 采样 View 属性拿不到），但顺带抄头部 translationY。
     */
    private fun applyFallbackFollow(st: State) {
        val wrapper = st.wrapper ?: return
        val header = st.header
        val tx = header.translationX
        if (wrapper.translationX != tx) wrapper.translationX = tx
        val ty = header.translationY
        if (wrapper.translationY != ty) wrapper.translationY = ty
        val panelW = (header.parent as? View)?.width?.takeIf { it > 0 }
            ?: header.width.takeIf { it > 0 }
            ?: return
        val a = (1f - abs(tx) / panelW).coerceIn(0f, 1f)
        if (wrapper.alpha != a) wrapper.alpha = a
    }

    /**
     * 落定复位（v2.0.11 原语义为强制 alpha=1/平移 0；v2.0.12 改
     * 按合成变换重对齐）：页面切换落定/取消/防卡死清位时调——
     * 此时 interactor 通道已在终值（回通知页 alpha 通道为 1、
     * 去控制中心则紧接着被终态门控整组挂起），头部 View 平移
     * 也已归位，重跑一遍合成即收回稳态，不再写死恒等值（写死
     * 会把面板开合进行中的合成一并抹掉、两路打架）。
     */
    private fun resetFollowTransforms() {
        applyComposedToAllStates()
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
     * 灭屏预挂起（v2.0.13）：注册 ACTION_SCREEN_OFF /
     * ACTION_SCREEN_ON 接收器（宿主 applicationContext、进程级
     * 只注册一次；两参 registerReceiver 的回调默认走进程主
     * 线程，与 tick 的门控重评同线程同路径）。灭屏广播在
     * keyguard 展示完成之前就到达——收到即置 [screenOffPreSuspend]
     * 并立即对全部 State 重评门控，按既有 keyguard 路径藏整组，
     * 抢在 setState 落定信号之前止住锁屏瞬间闪现。亮屏清位并
     * 重评：真锁屏由 setState 门控继续挂起，无锁直回通知页由
     * applyGate 的既有恢复路径接上，不会卡在挂起态。注册失败
     * 只记一行，门控退回 setState 单路（行为同 v2.0.12）。
     */
    private fun ensureScreenGateReceiver(header: ViewGroup) {
        if (screenGateReceiverRegistered) return
        try {
            val appContext = header.context.applicationContext ?: header.context
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            if (screenOffPreSuspend) return
                            screenOffPreSuspend = true
                            logFn("screen off: overlay pre-suspended ahead of keyguard gate")
                            reevaluateAllStates()
                        }
                        Intent.ACTION_SCREEN_ON -> {
                            if (!screenOffPreSuspend) return
                            screenOffPreSuspend = false
                            logFn("screen on: screen-off pre-suspend cleared, gates re-evaluated")
                            reevaluateAllStates()
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            appContext.registerReceiver(receiver, filter)
            screenGateReceiverRegistered = true
            logFn("screen off/on pre-suspend receiver registered")
        } catch (t: Throwable) {
            logFn(
                "screen gate receiver register failed, keyguard gate only: " +
                    "${t.javaClass.name}: ${t.message}"
            )
        }
    }

    /**
     * 对全部已登记 State 立即重评一次门控（与 tick 内 applyGate
     * 同一路径）：该挂起的按现原因挂起、该恢复的经 attach 恢复。
     * 供灭屏/亮屏广播即时调用，不等下一轮 1 秒 tick。
     */
    private fun reevaluateAllStates() {
        val snapshot = synchronized(states) { states.values.toList() }
        for (st in snapshot) {
            try {
                applyGate(st)
            } catch (t: Throwable) {
                logFn("gate re-evaluate failed: ${t.javaClass.name}: ${t.message}")
            }
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
     * v2.0.10 起它同时是页面切换门控的**落定段**：切换动画结束
     * 才走到这里（见 [installPageSwitchGate]），after 里清过渡
     * 标志并即时重评门控。
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
                        val changed = z != controlCenterExpand
                        if (changed) {
                            controlCenterExpand = z
                            log(
                                "panel page -> " +
                                    if (z) "control-center" else "notification-center"
                            )
                        }
                        // 页面切换门控（v2.0.10）的落定段：notify 是
                        // 切换动画结束的分发点（两方向都到），在此
                        // 清过渡标志并即时重评——恢复仍由 applyGate
                        // 按落定值判定（cc=true 继续以 control-center
                        // 挂起、cc=false 才恢复挂载）。状态变化时
                        // 也即时重评，不再只等 1 秒 tick。
                        if (pageSwitching) {
                            pageSwitching = false
                            log("page switch settled (notify cc=$z)")
                            // 跟随复位（v2.0.11）：落定回通知页才
                            // 复位为完全不透明；落定到控制中心不
                            // 复位——随后的 applyGate 会整组挂起，
                            // 先复位反而可能在挂起前闪一帧全显。
                            if (!z) resetFollowTransforms()
                            reevaluateGatesNow()
                        } else if (changed) {
                            reevaluateGatesNow()
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
     * 页面切换门控的开始段（v2.0.10 定案；v2.0.11 起置位语义
     * 改为「进入跟随窗口」而非挂起，见类头 v2.0.11 段）。
     * 两个开始信号（定案见类头 v2.0.10 段）：
     * ①HwPanelControlImpl.notifySwipeStart(int)——与落定 notify
     * 同一分发枢纽，切换 tracking 成立时在页面移动之前分发，
     * 参数是 tracking 起始页（1=通知中心、2=控制中心）；两方向
     * 的拖动都置位（从控制中心拖回时本来就以 control-center
     * 挂起，置位无副作用）。
     * ②HnSwipeSwitchControllerImpl.swipeToControlCenter /
     * swipeToNotificationCenter(float, boolean)——fling 提交与
     * 程序化切换（命令队列展开设置面板、NotificationPanelView
     * .expandWithoutQs 等）的汇聚点，before 里置位（动画集在
     * 方法内才 start，置位必然早于第一帧），同时捕获引擎实例
     * 供 tick 防卡死兜底直读其动画/跟踪状态。
     * 清位只在落定段：notifyControlCenterPanelChange 的 hook
     * （动画结束分发）与这里的 notifySwipeCanceled(int)（取消
     * 切换的动画结束只分发它）。各 hook 独立 try-catch，单个
     * 失败不影响其余信号，日志写明。
     */
    private fun installPageSwitchGate(
        lpparam: XC_LoadPackage.LoadPackageParam,
        log: (String) -> Unit,
    ) {
        try {
            val implClass = XposedHelpers.findClass(PANEL_CONTROL_IMPL_CLASS, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                implClass, "notifySwipeStart",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val fromType = param.args.getOrNull(0) as? Int ?: return
                        markPageSwitchStart("swipe tracking from type=$fromType")
                    }
                }
            )
            log("page-switch start hook installed (HwPanelControlImpl.notifySwipeStart)")
        } catch (t: Throwable) {
            log(
                "page-switch start hook failed (notifySwipeStart): " +
                    "${t.javaClass.name}: ${t.message}"
            )
        }
        try {
            val implClass = XposedHelpers.findClass(PANEL_CONTROL_IMPL_CLASS, lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                implClass, "notifySwipeCanceled",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (pageSwitching) {
                            pageSwitching = false
                            log(
                                "page switch canceled " +
                                    "(startType=${param.args.getOrNull(0)})"
                            )
                            // 取消即回到起始页：起始页是通知页才
                            // 复位；起始页是控制中心时不复位（同
                            // notify 落定 cc 的理由，防挂起前闪帧）。
                            if (!controlCenterExpand) resetFollowTransforms()
                            reevaluateGatesNow()
                        }
                    }
                }
            )
            log("page-switch cancel hook installed (HwPanelControlImpl.notifySwipeCanceled)")
        } catch (t: Throwable) {
            log(
                "page-switch cancel hook failed (notifySwipeCanceled): " +
                    "${t.javaClass.name}: ${t.message}"
            )
        }
        try {
            val switchClass = XposedHelpers.findClass(SWIPE_SWITCH_IMPL_CLASS, lpparam.classLoader)
            for (method in arrayOf("swipeToControlCenter", "swipeToNotificationCenter")) {
                XposedHelpers.findAndHookMethod(
                    switchClass, method,
                    Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.thisObject?.let { swipeControllerRef = WeakReference(it) }
                            markPageSwitchStart(method)
                        }
                    }
                )
            }
            log(
                "page-switch engine hooks installed " +
                    "(HnSwipeSwitchControllerImpl.swipeToControlCenter/swipeToNotificationCenter)"
            )
        } catch (t: Throwable) {
            log(
                "page-switch engine hooks failed: " +
                    "${t.javaClass.name}: ${t.message}"
            )
        }
    }

    /** 页面切换开始：置位过渡标志（首次置位记一行开始日志）并即时重评各 State 的门控。 */
    private fun markPageSwitchStart(source: String) {
        if (!pageSwitching) {
            pageSwitching = true
            pageSwitchSetAtMs = SystemClock.uptimeMillis()
            logFn("page switch start: $source")
        }
        reevaluateGatesNow()
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

    /**
     * 挂起原因：null=可挂载；优先级 锁屏 > 控制中心 > 横幅
     * （可同时成立，取先者记日志）。
     * v2.0.11 起页面切换过渡不再是挂起原因：过渡期走跟随
     * （见 [installHeaderAnimFollow]），整组 GONE 太突兀；
     * 控制中心也只在**落定**（!pageSwitching）时才硬挂起——
     * 回程拖动中 isControlCenterExpand 仍为 true，但覆盖层要
     * 随通知页 alpha 一起淡入，故切换中放行、由 applyGate 把
     * 已挂起的 State 经 attach 恢复为跟随显示；落定信号清掉
     * pageSwitching 后，本函数即恢复 cc 硬挂起（终态不变）。
     * 锁屏与横幅是终态门控，过渡期照样硬挂起、不参与跟随。
     */
    private fun suspendReason(header: ViewGroup): String? = when {
        // 灭屏预挂起（v2.0.13）：灭屏瞬间即成立、早于 setState
        // 落定，原因记 keyguard 与锁屏挂起同路（恢复日志口径一致）。
        screenOffPreSuspend -> "keyguard"
        isKeyguardActive(header.context) -> "keyguard"
        isControlCenterActive(header) && !pageSwitching -> "control-center"
        isHeadsUpActive() -> "heads-up"
        else -> null
    }

    /**
     * 页面切换标志防卡死（v2.0.10，tick 每秒调一次）：正常路径下
     * 标志由落定 notify / 取消信号在动画结束时清掉（<1 秒）；若
     * 切换被面板收起等动作中途打断，宿主的动画结束回调会早退、
     * 不分发任何落定信号，标志会卡住、覆盖层在通知页回不来。
     * 兜底条件从严：置位已超 1.5 秒（远超切换动画时长）、引擎
     * 实例已捕获、且直读其 mIsAnimationPlaying / mIsTracking
     * 均为 false（不在动画也不在随指拖动）才清位并记日志；
     * 清位后本轮 applyGate 按落定页态决定恢复或继续挂起。
     */
    private fun maybeClearStuckPageSwitch() {
        if (!pageSwitching) return
        if (SystemClock.uptimeMillis() - pageSwitchSetAtMs < 1500L) return
        val ctrl = swipeControllerRef?.get() ?: return
        val playing = try {
            XposedHelpers.getBooleanField(ctrl, "mIsAnimationPlaying")
        } catch (t: Throwable) {
            return
        }
        val tracking = try {
            XposedHelpers.getBooleanField(ctrl, "mIsTracking")
        } catch (t: Throwable) {
            return
        }
        if (!playing && !tracking) {
            pageSwitching = false
            resetFollowTransforms()
            logFn("page switch flag cleared by tick fallback (no anim/tracking, settled state kept)")
        }
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
        // 灭屏预挂起接收器（v2.0.13）：与覆盖层是否挂载无关，
        // 首次 attach 时顺手注册（进程级一次）。
        ensureScreenGateReceiver(header)
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
            val themeEntry = StampStore.themeEntry(stamp.themeId)
            if (themeEntry == null) {
                logFn(
                    "overlay: selected theme package missing for ${stamp.themeId}, " +
                        "keeping original header"
                )
                teardown(header)
                return
            }
            val st = synchronized(states) {
                states.getOrPut(header) { State(header) }
            }
            // 页态门控（v2.0.3 锁屏 / v2.0.4 控制中心 / v2.0.6 横幅 / v2.0.10 页面切换过渡）：头部在锁屏
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
                        "sw=$pageSwitching, hun=${isHeadsUpActive()}) " +
                        "wrapperVis=${st.wrapper?.visibility}"
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
                // 首显预热采纳（v2.0.11）：install/上次 attach 已在
                // 后台把同 (themeId, stamp) 的主题 load + 位图预
                // 解码好就整套接过来、attach 零 IO；不吻合才走
                // 同步 load（原路径保留），并再排一次预热供下次
                // 命中——预热不是唯一路径，失败不影响挂载。
                val pre = preheatedTheme
                if (pre != null && pre.themeId == stamp.themeId &&
                    pre.stamp == stamp.stamp
                ) {
                    st.renderer.adoptFrom(pre.renderer)
                    preheatedTheme = null
                    logFn("overlay: theme adopted from preheat cache (zero-IO attach)")
                } else if (!st.renderer.load(themeEntry)) {
                    teardown(header)
                    return
                } else {
                    startPreheat()
                }
                st.loadedThemeId = stamp.themeId
                // 先量一遍内容下缘，跟随模式下首帧前就把高度定对
                buildEnv(st)?.let { env -> st.renderer.measureContentBottom(env) }
            }
            st.appliedStamp = stamp
            st.stampMtimeSeen = StampStore.stampMtime()
            // 垂直偏移同步（v2.0.7）：先于 applyHeight，跟随高度按
            // 含偏移的内容下缘算（量不到环境时留待布局回调补）
            applyVerticalOffset(st, stamp)
            // 水平偏移同步（v2.0.23）：与垂直同批应用
            applyHorizontalOffset(st, stamp)
            applyHeight(header, st, stamp)
            // 避让补账（v2.0.7）：门控恢复与每次 attach 完成时主动
            // reconcile 一次——挂起期系统写过的栈顶值（挂起中拦截
            // 放行）在恢复时当场补上，不等下一次系统写入；再排一
            // 次布局后复查，兜住嵌套写覆盖的时序。
            ensureStackAdjusted(st)
            scheduleStackReconcile(st)
            startTicker(st)
            // 跟随变换对齐（v2.0.11 切换跟随、v2.0.12 统一合成）：
            // 挂载/恢复时按当前跟踪值把合成变换接上——过渡中恢
            // 复挂载的接上当前帧；稳态跟踪值恒为恒等变换，等同
            // 复位（防上次跟随的残值把覆盖层留在半透明/偏移位）。
            // v2.0.14：对齐前复位斜率状态，这一次应用直接落到
            // 目标值（恢复不许从挂起前的旧 alpha 慢慢爬回来）。
            st.followAlpha = -1f
            applyComposedTransform(st)
            // 首显即时（v2.0.11；v2.0.21 改离屏成品）：挂载完成
            // 不等 tick——当场把第一帧离屏渲好（尺寸还没落定时
            // 渲不出，onSizeChanged/布局 post 会在尺寸一到就补
            // 渲），面板可见时再排一发贴图。二值收尾门也在这里
            // 先应用一次：进程启动时面板是关着的，wrapper 从
            // 第一帧就该是 GONE，别等 tick。
            st.themeView?.refreshContent(force = true)
            applyWrapperVisibility(st)
            if (st.wrapper?.isShown == true) {
                st.themeView?.invalidate()
            }
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
            st.wrapper = wrapper
            st.themeView = themeView

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
                            // 布局帧断言（v2.0.23）：场景激活伴随
                            // 布局，被改回可见的子视图在这里同帧
                            // 压回；顺带核对预抽登记还跟在当前观
                            // 察者上（头部重挂载会换观察者）。
                            if (!st.suspended) {
                                hideComposeChildren(header, st, "layout")
                            }
                            ensurePreDrawListener(header, st)
                            // 垂直偏移补同步（v2.0.7）：首次 attach 时
                            // 头部还没布局、量不到环境定不了偏移，
                            // 布局完成后在这里补；(主题, offsetDp)
                            // 没变时函数内直接返回，不空转。偏移改了
                            // 内容下缘，跟随高度随之重算。
                            val stampNow = st.appliedStamp
                            if (stampNow != null && !st.suspended) {
                                applyVerticalOffset(st, stampNow)
                                applyHorizontalOffset(st, stampNow)
                                applyHeight(header, st, stampNow)
                            }
                            alignOverlay(st)
                            ensureStackAdjusted(st)
                            // 尺寸/位置落定后把成品渲到最新一帧
                            // （过期或尺寸不符才真渲，v2.0.21）；
                            // 面板不可见时不贴图（v2.0.5 瘦身）
                            st.themeView?.refreshContent()
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
        // 帧频断言（v2.0.11；登记方式 v2.0.23 改，见
        // ensurePreDrawListener）：每帧绘制前在头部的绘制通道
        // 上做轻活（挂起期全跳过——挂起时原头部本就该可见）：
        // ①重断言 Compose 子视图 INVISIBLE；②二值收尾门联动
        // + 位图新鲜度（v2.0.21）；③动画跟随的帧级采样
        // （v2.0.12，头部 View 自身平移靠预抽逐帧采样补齐；
        // 主 hook 没装上时退回 v2.0.11 的比例换算兜底）。
        ensurePreDrawListener(header, st)
        mountOverlay(header, st)
    }

    /** 预抽监听本体（职责见 ensureWrapper 里的登记点注释）。 */
    private fun createPreDrawListener(
        header: ViewGroup,
        st: State,
    ): ViewTreeObserver.OnPreDrawListener {
        return ViewTreeObserver.OnPreDrawListener {
            try {
                // teardown 已摘状态的监听必须失效（观察者合并
                // 后 remove 可能落空，靠注册校验兜底 inert）。
                val registered = synchronized(states) { states[header] === st }
                if (registered && !st.suspended && st.wrapper != null) {
                    hideComposeChildren(header, st, "predraw")
                    // 二值收尾门帧频联动（v2.0.21）：宿主把头
                    // 部本体撤下场景的那一帧，wrapper 同帧
                    // GONE，不留收尾残影帧。
                    applyWrapperVisibility(st)
                    // 位图新鲜度（v2.0.21）：过期（>900ms 或
                    // 尺寸不符）就在绘制前同步离屏重渲，本帧
                    // 贴出的必然是完整成品；未过期零开销。
                    val tv = st.themeView
                    if (tv != null && header.width > 0 && tv.refreshContent()) {
                        tv.invalidate()
                    }
                    if (animFollowHookActive) {
                        applyComposedTransform(st)
                    } else if (pageSwitching) {
                        applyFallbackFollow(st)
                    }
                }
            } catch (t: Throwable) {
                // 断言失败不许影响宿主绘制
            }
            true
        }
    }

    /**
     * 预抽监听的登记与跟随（v2.0.23）：旧版只在 attach 时往
     * 当时的 header.viewTreeObserver 上登记一次——头部被宿主
     * 重挂载/重排父级后，当前窗口观察者可能已经换人，旧登记
     * 落在被弃置的观察者上、监听静默失效（v2.0.21 真机日志
     * 里 re-hidden 全程零出现、录屏却有实打实的同框漏出，
     * 与此互为印证）。本版：登记时记下观察者实例；每次调用
     * 时若当前观察者与登记不符（或头部刚 detach 过、引用被
     * 清），就把同一监听迁到当前观察者上。调用点：attach、
     * 布局回调、tick——都是头部确定在窗内的时机。
     */
    private fun ensurePreDrawListener(header: ViewGroup, st: State) {
        if (st.headerAttachListener == null) {
            val al = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    st.preDrawObserver = null
                }
            }
            try {
                header.addOnAttachStateChangeListener(al)
                st.headerAttachListener = al
            } catch (t: Throwable) {
                // 装不上就靠布局/tick 的观察者比对兜底
            }
        }
        val observer = try {
            header.viewTreeObserver
        } catch (t: Throwable) {
            return
        }
        if (st.preDrawListener != null && st.preDrawObserver === observer) return
        val listener = st.preDrawListener
            ?: createPreDrawListener(header, st).also { st.preDrawListener = it }
        val old = st.preDrawObserver
        if (old != null && old !== observer) {
            try {
                old.removeOnPreDrawListener(listener)
            } catch (t: Throwable) {
                // 旧观察者已失效就算了
            }
        }
        try {
            observer.addOnPreDrawListener(listener)
            st.preDrawObserver = observer
        } catch (t: Throwable) {
            // 登记失败不致命：漏斗/门/布局三处同步断言兜底
        }
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
            healWrapperVisibility(st, wrapper, parent)
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
        healWrapperVisibility(st, wrapper, parent)
    }

    /**
     * 可见性/z 序自愈：wrapper 被改 INVISIBLE 或别的子视图后加进来盖住它时恢复并顶回最前（只在状态真不对时才写）。
     * alpha/平移的目标值一律走合成变换（v2.0.12）：过渡期以
     * 跟踪到的逐帧通道值为准（自愈不许把跟随中的 wrapper 拉
     * 回不透明/原位），稳态跟踪值恒为恒等、等同复位。主 hook
     * 没装上时合成不可用，退回 v2.0.11 口径（切换期采样兜底、
     * 其余复位）。
     */
    private fun healWrapperVisibility(st: State, wrapper: View, parent: ViewGroup) {
        // 可见性以二值收尾门为准（v2.0.21）：关落定时保持
        // GONE，不许自愈把它顶回来（同 v2.0.4 挂起期硬门控
        // 的教训）；其余时候恢复 VISIBLE。
        applyWrapperVisibility(st)
        if (animFollowHookActive) {
            applyComposedTransform(st)
        } else if (pageSwitching) {
            applyFallbackFollow(st)
        } else {
            if (wrapper.alpha != 1f) wrapper.alpha = 1f
            if (wrapper.translationX != 0f) wrapper.translationX = 0f
            if (wrapper.translationY != 0f) wrapper.translationY = 0f
        }
        if (parent.indexOfChild(wrapper) != parent.childCount - 1) {
            wrapper.bringToFront()
        }
    }

    /**
     * 把头部本体（Compose 子视图）压回 INVISIBLE。[path] 标记
     * 本次纠正是哪条断言路径抓到的（predraw/layout/funnel/
     * gate）：带 path 的调用会留证——那是帧频上被宿主改回可见
     * 的实锤；attach/tick 的常规隐藏（path=null）含首次挂载与
     * 挂起恢复等正常场景，不计数、免得挤占留证名额。覆盖层已
     * 完整挂载过（appliedStamp 非空）才算数，有界记前 10 次。
     *
     * v2.0.23 为什么加这么多路径：v2.0.11 起的预抽断言在真机
     * 日志里「compose child re-hidden」全程零出现，而 v2.0.20
     * 与 v2.0.21 两份录屏里原头部时钟都在下拉头两帧实打实
     * 漏出——断言要么没在跑、要么抓不到那一帧。故本版把断言
     * 铺到场景激活的每个同步点（漏斗翻转、收尾门开门、布局
     * 回调、预抽），并给预抽登记加观察者跟随（头部重挂载会
     * 换 ViewTreeObserver，旧登记可能落在弃置的观察者上）；
     * 哪条路径抓到、日志里带 path 一看便知。
     */
    private fun hideComposeChildren(
        header: ViewGroup,
        st: State,
        path: String? = null,
    ) {
        val wrapper = st.wrapper ?: return
        for (i in 0 until header.childCount) {
            val child = header.getChildAt(i)
            if (child !== wrapper && child.visibility != View.INVISIBLE) {
                val was = child.visibility
                child.visibility = View.INVISIBLE
                if (path != null && st.appliedStamp != null &&
                    st.childCorrectionLogCount < 10
                ) {
                    st.childCorrectionLogCount++
                    logFn(
                        "compose child re-hidden #${st.childCorrectionLogCount} via $path: " +
                            "${child.javaClass.name} was vis=$was"
                    )
                }
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

    /**
     * 垂直偏移同步（v2.0.7）：把 stamp 的 offsetDp 换算成 px 写入
     * 渲染器。先以 offset=0 量一遍内容簇的顶（rawTop），生效值
     * 钳制为不小于 -rawTop（簇顶不小于 0——主题顶部留白最多吃
     * 完，不会把时钟簇顶出画面）；再以生效值量一遍下缘，跟随
     * 内容高度随后按含偏移的下缘算，头部上移变矮、通知随之上
     * 来，栈 delta 机制自动跟随。只在 (主题, offsetDp) 变化或
     * 尚未成功应用时才量测（量测是一遍空 Canvas 绘制，不能每
     * tick 空转）；量不到环境（头部尚无宽度）时直接返回、留待
     * 布局回调补。
     */
    private fun applyVerticalOffset(st: State, stamp: StampStore.Stamp) {
        if (st.appliedOffsetDp == stamp.offsetDp &&
            st.offsetThemeId == stamp.themeId
        ) {
            return
        }
        val r = st.renderer
        if (!r.isLoaded) return
        val env = buildEnv(st) ?: return
        val density = st.header.resources.displayMetrics.density
        val wantPx = stamp.offsetDp * density
        r.offsetYPx = 0f
        r.measureContentBottom(env)
        val rawTop = r.contentTopPx
        val dy = if (rawTop == Float.MAX_VALUE) {
            wantPx
        } else {
            wantPx.coerceAtLeast(-rawTop)
        }
        r.offsetYPx = dy
        r.measureContentBottom(env)
        st.appliedOffsetDp = stamp.offsetDp
        st.offsetThemeId = stamp.themeId
        logFn(
            "overlay vertical offset: setting=${stamp.offsetDp}dp " +
                "want=${wantPx}px applied=$dy rawTop=$rawTop"
        )
    }

    /**
     * 水平偏移同步（v2.0.23）：与垂直偏移同构。把 stamp 的
     * offsetXDp 换算成 px 写入渲染器：先以 offsetX=0 量一遍
     * 内容簇的左/右缘，生效值钳制为簇不移出画布——左移不超
     * 簇左缘（左缘 ≥ 0）、右移不超画布宽减簇右缘（右缘 ≤
     * 画布宽）；簇比画布还宽时右界收敛到左界、优先保左缘。
     * 只在 (主题, offsetXDp) 变化或尚未成功应用时才量测。
     */
    private fun applyHorizontalOffset(st: State, stamp: StampStore.Stamp) {
        if (st.appliedOffsetXDp == stamp.offsetXDp &&
            st.offsetXThemeId == stamp.themeId
        ) {
            return
        }
        val r = st.renderer
        if (!r.isLoaded) return
        val env = buildEnv(st) ?: return
        val density = st.header.resources.displayMetrics.density
        val wantPx = stamp.offsetXDp * density
        r.offsetXPx = 0f
        r.measureContentBottom(env)
        val rawLeft = r.contentLeftPx
        val rawRight = r.contentRightPx
        val canvasW = env.screenWidthPx.toFloat()
        val dx = if (rawLeft == Float.MAX_VALUE || canvasW <= 0f) {
            wantPx
        } else {
            val minDx = -rawLeft
            val maxDx = (canvasW - rawRight).coerceAtLeast(minDx)
            wantPx.coerceIn(minDx, maxDx)
        }
        r.offsetXPx = dx
        r.measureContentBottom(env)
        st.appliedOffsetXDp = stamp.offsetXDp
        st.offsetXThemeId = stamp.themeId
        logFn(
            "overlay horizontal offset: setting=${stamp.offsetXDp}dp " +
                "want=${wantPx}px applied=$dx rawLeft=$rawLeft rawRight=$rawRight"
        )
    }

    // ---------- 通知栈顶（v2.0.3 第 6 项 / v2.0.4 改拦截写入口） ----------
    //
    // 机制（反编译逐环核实）：通知栈内容起始位置 = NSSL 的
    // AmbientState.mTopPadding，由 NotificationPanelView
    // .requestScrollerTopPaddingUpdate(boolean) 写入，值来自
    // Compose 场景里 Nssl 盒的 window Y，由 Compose 布局量出的
    // 头部高度决定，不认 View 层改的 lp.height。v2.0.3 用 tick
    // 事后增量同步，真机未生效（通知从面板顶部压住时钟）——
    // 系统写入时机不受我们控制、tick 总慢半拍且基线捕获条件
    // 苛刻。v2.0.4 改拦截写入口：hook 该方法，after 里在同一次
    // 调用栈内读出系统刚写的值，加上（当前头部高 - 原头部高）
    // 的增量后写回字段并刷新栈状态——系统每次更新都自带
    // 偏移，不再赛跑。挂起/卸载时只在当前值仍是我们调整过的
    // 值时才恢复系统原值。
    // v2.0.5 教训：v2.0.4 的拦截不幂等且会自激（见类头 v2.0.5
    // 段）——拦截与兜底统一走一套判定，writeTopPadding 带重入
    // 保护，目标值已在位时不调更新组。
    // v2.0.7 写路径枚举（dexdump 全 APK 扫字段写指令）：AmbientState
    // .mTopPadding 是 public 字段、无 setter，全包只有**一个**
    // iput 写点，就在已 hook 的 requestScrollerTopPaddingUpdate
    // 内（jadx 源码里非锁屏/锁屏两段写块在 dex 里是共享尾巴）。
    // NSSL 没有 setTopPadding 方法。
    // v2.0.8 来源追踪（见类头 v2.0.8 段）：判定不再靠与头部
    // 高度的关系猜值的身份——写入口每次经过都记录系统原值
    // lastSystemOriginal，调整目标恒为 原值 + delta。分支语义
    // 已逐环核实：流量分支 = Nssl 盒 onPlaced 的
    // localToWindow(Offset.Zero).y（窗口绝对 Y）；QS 分支
    // （!SceneContainerFlag.isSceneEnabled || mBarState==2，
    // 前者是静态属性 sys.config.scene.switchOn）=
    // HwNotchStatusBar 高 + dimen(notification_panelview_
    // margin_top=80dp=280px)；锁屏分支（mKeyguardShowing）=
    // mIntrinsicPadding，拦截不记录、不调整。
    // v2.0.9：写回更新组摘除 notifyHeightChangeListener——它是
    // 五个更新方法里唯一同步再入写入口的（经面板
    // onHeightChanged → requestPanelHeightUpdate →
    // setExpandedHeight → onHeightUpdated →
    // positionClockAndNotifications 拐回），嵌套写盖回原值是
    // 「未收敛窗口」的根源；完整链路见类头 v2.0.9 段。

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
        // 重入保护（v2.0.5）：置位期间拦截/兜底一律跳过，防级联
        // 自激（v2.0.4 真机卡死的主因之一）。v2.0.9 起更新组已
        // 摘除唯一能同步再入写入口的 notifyHeightChangeListener
        // （链路见类头 v2.0.9 段），此保护只作并发兜底。
        if (st.stackAdjusting) return
        st.stackAdjusting = true
        try {
            XposedHelpers.setIntField(ambient, "mTopPadding", value)
            // 系统写入口写完字段后的更新组里经静态核实**不**同步
            // 再入写入口的最小子集（v2.0.9）：前三个纯计算（分别
            // 刷布局高、内容高、栈 Y），requestChildrenUpdate 只
            // 登记 pre-draw 子更新 + invalidate（异步，帧后子高
            // 度变化是新的系统写入、自有拦截处理）。
            // notifyHeightChangeListener **故意不调**：它直达
            // 面板 onHeightChanged → requestPanelHeightUpdate →
            // setExpandedHeight → onHeightUpdated →
            // positionClockAndNotifications → 写入口，嵌套写会把
            // 字段盖回系统原值且其 after 被重入保护挡掉——这正是
            // v2.0.4–v2.0.8 避让靠运气收敛的根源。面板侧的高度
            // 记账由系统自己每次写入时的尾随 notify 完成，不缺
            // 我们这一次。
            runCatching { XposedHelpers.callMethod(stack, "updateAlgorithmHeightAndPadding") }
            runCatching { XposedHelpers.callMethod(stack, "updateContentHeight") }
            runCatching { XposedHelpers.callMethod(stack, "updateStackPosition\$1") }
                .onFailure {
                    runCatching { XposedHelpers.callMethod(stack, "updateStackPosition") }
                }
            runCatching { XposedHelpers.callMethod(stack, "requestChildrenUpdate") }
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
     * 系统原值 → 加头部高度增量 → 写回并刷新栈状态（v2.0.9 起
     * 更新组已摘除会再入写入口的 notifyHeightChangeListener）。
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

    /**
     * 系统刚写完一次 mTopPadding：先做来源记录，再（非挂起时）
     * 按来源追踪调整（v2.0.8）。
     *
     * 来源记录的原理：全包唯一的 mTopPadding 写点就在这个方法
     * 里，我们自己的调整是直接写字段、从不经此方法——所以
     * after 里读到的字段值，只要不等于我们刚写下的目标值，
     * 就必然是系统本次写下的原始值，记为 lastSystemOriginal。
     * 嵌套再入时同样先记录（嵌套写落的正是系统最新原值），但
     * 不在嵌套栈里调更新组，只排延迟复查——v2.0.9 摘除
     * notify 后我们的写回已无同步再入路径，此分支只作防御
     * 兜底（系统自身经尾随 notify 链的自嵌套发生在方法体内、
     * after 之前，不经此分支）。锁屏分支（面板
     * mKeyguardShowing）写的是 mIntrinsicPadding、语义不同，
     * 不记录、不调整。
     */
    private fun onSystemTopPaddingWrite(panel: View?) {
        val st = synchronized(states) {
            states.values.firstOrNull { it.wrapper != null }
        } ?: return
        if (!findStack(st)) return
        val cur = readTopPadding(st) ?: return
        // 本次经过的来源分支与嵌套标记（v2.0.9 有界证据日志）：
        // nested=true 表示这次经过发生在我们的调整调用栈内——
        // v2.0.9 摘除 notify 后理论上不应再出现，出现即残留链路。
        val nested = st.stackAdjusting
        val branch = stackBranchOf(panel)
        // 锁屏分支写的是 mIntrinsicPadding、语义不同，不记录、
        // 不调整（与 v2.0.8 前的早退等价，只是挪到读值之后以便
        // pass 日志能带上字段值）。
        if (branch == "keyguard") {
            logStackPass(st, branch, nested, cur, cur)
            return
        }
        // 来源记录（回声护栏：值等于我们刚写的目标时，可能是方法
        // 体因「值未变」没写、after 读到的是我们的值，不记录）
        if (cur != st.lastAdjustedWritten) {
            st.lastSystemOriginal = cur
        }
        // 嵌套再入（防御兜底）：只记录 + 排延迟复查，不在嵌套栈
        // 里做调整，等调用栈 unwind 由复查按最新原值补。
        if (st.stackAdjusting) {
            scheduleStackReconcile(st)
            logStackPass(st, branch, nested, cur, readTopPadding(st) ?: cur)
            return
        }
        // 挂起（控制中心/横幅）时只记录不调整：挂起期系统写的
        // 值在门控恢复/attach 时由主动 reconcile 补账（v2.0.7）。
        if (st.suspended) {
            logStackPass(st, branch, nested, cur, cur)
            return
        }
        reconcileFromSource(st)
        // 写后复查兜底（v2.0.7）：排一次延迟复查读回校验；已在
        // 位时复查是零写入空转，不增开销。
        scheduleStackReconcile(st)
        logStackPass(st, branch, nested, cur, readTopPadding(st) ?: cur)
    }

    /**
     * 写入口本次经过的来源分支（v2.0.9）：按反编译核实的方法体
     * 分支条件判定——面板 mKeyguardShowing → "keyguard"；
     * !SceneContainerFlag.isSceneEnabled（静态布尔字段）或
     * mBarState==2 → "qs"；其余 → "flow"；反射读不到 → "unknown"。
     */
    private fun stackBranchOf(panel: View?): String {
        if (panel == null) return "unknown"
        val keyguard = try {
            XposedHelpers.getBooleanField(panel, "mKeyguardShowing")
        } catch (t: Throwable) {
            return "unknown"
        }
        if (keyguard) return "keyguard"
        val barState = try {
            XposedHelpers.getIntField(panel, "mBarState")
        } catch (t: Throwable) {
            -1
        }
        if (barState == 2) return "qs"
        val sceneOn = try {
            val cls = XposedHelpers.findClass(SCENE_FLAG_CLASS, panel.javaClass.classLoader)
            XposedHelpers.getStaticBooleanField(cls, "isSceneEnabled")
        } catch (t: Throwable) {
            null
        }
        return when (sceneOn) {
            false -> "qs"
            true -> "flow"
            null -> "unknown"
        }
    }

    /** 写入口经过的有界证据日志（v2.0.9，每进程上限 40 行）：分支 + 是否嵌套 + 字段前后值。 */
    private fun logStackPass(st: State, branch: String, nested: Boolean, before: Int, after: Int) {
        if (st.stackPassLogCount >= 40) return
        st.stackPassLogCount++
        logFn(
            "stack pass #${st.stackPassLogCount}: branch=$branch " +
                "nested=$nested field $before -> $after"
        )
    }

    /**
     * 延迟栈顶复查（v2.0.7，v2.0.8 改按来源追踪校验）：post 到
     * State 的 handler，等当前调用栈（系统写入口/我们的写回
     * 更新组）unwind 后再跑一次 [ensureStackAdjusted]。复查后
     * 读回校验：字段值与「lastSystemOriginal + 当前 delta」不
     * 一致且重试未尽时递增间隔再排一次（嵌套写只在面板活跃
     * 重排时出现，布局落定后我们的写即粘住）；上限后交给每秒
     * tick 兜底。挂起中/状态已卸载时不动作（恢复路径另有主动
     * reconcile）。
     */
    private fun scheduleStackReconcile(st: State) {
        if (st.stackReconcilePosted) return
        st.stackReconcilePosted = true
        st.handler.post {
            st.stackReconcilePosted = false
            try {
                val registered = synchronized(states) { states[st.header] === st }
                if (!registered || st.suspended) return@post
                ensureStackAdjusted(st)
                val sys = st.lastSystemOriginal
                val target = if (sys != null && st.originalHeight > 0) {
                    val delta = st.header.height - st.originalHeight
                    if (delta > 0) sys + delta else sys
                } else {
                    null
                }
                val cur = if (target != null) readTopPadding(st) else null
                if (target != null && cur != null && cur != target &&
                    st.stackReconcileRetries < 3
                ) {
                    st.stackReconcileRetries++
                    val delay = 50L * st.stackReconcileRetries
                    st.handler.postDelayed({
                        if (synchronized(states) { states[st.header] === st }) {
                            scheduleStackReconcile(st)
                        }
                    }, delay)
                } else {
                    st.stackReconcileRetries = 0
                }
            } catch (t: Throwable) {
                logFn(
                    "stack deferred reconcile failed: " +
                        "${t.javaClass.name}: ${t.message}"
                )
            }
        }
    }

    /** 拦截/兜底共用的有界日志（避免动画期间逐帧刷屏）。 */
    private fun logIntercept(st: State, message: String) {
        if (st.interceptLogCount < 8) {
            st.interceptLogCount++
            logFn(message)
        }
    }

    /**
     * 栈顶调整（v2.0.8 来源追踪，拦截/延迟复查/tick 兜底共用）：
     * 目标恒为 lastSystemOriginal + delta（delta = 当前头部高 −
     * 原头部高）——系统最后写入的原值就是它对当前状态的答案，
     * 我们的头部在同一坐标系里长高了 delta，栈顶随之加 delta，
     * 不对值的身份做任何猜测（v2.0.5–v2.0.7 的 ±240 窗口定性
     * 已废除：它曾把 501 误判成含增量的值、反推出不存在的
     * 原值 232 并锁定）。
     * - 还没记录到系统原值：不动，等写入口先经过。
     * - delta ≤ 0（头部已复原）：当前值若仍是我们写过的调整
     *   值，写回系统原值清残值。
     * - 当前值已等于目标：只更新跟踪，绝不调用系统更新组
     *   （面板动画期间逐帧整套重排是卡顿主因之一，v2.0.5）。
     */
    private fun reconcileFromSource(st: State) {
        if (st.stackAdjusting) return
        if (st.originalHeight <= 0) return
        val sys = st.lastSystemOriginal ?: return
        val delta = st.header.height - st.originalHeight
        val cur = readTopPadding(st) ?: return
        if (delta <= 0) {
            val adj = st.lastAdjustedWritten
            if (adj != null && cur == adj && cur != sys) {
                writeTopPadding(st, sys)
                logFn("stack sync: topPadding restored to system value $sys (delta back to 0)")
            }
            if (cur == sys) st.lastAdjustedWritten = null
            return
        }
        val target = sys + delta
        if (cur == target) {
            st.lastAdjustedWritten = target
            return
        }
        writeTopPadding(st, target)
        st.lastAdjustedWritten = target
        logIntercept(
            st,
            "stack write adjusted: system=$sys + delta=$delta -> $target",
        )
    }

    /**
     * tick/布局兜底（v2.0.5 起）：写入口拦截是主路径；这里只跑
     * 同一套来源追踪调整（[reconcileFromSource]）——稳态下当前
     * 值恒等于目标，零写入；系统在挂起期/漏网时机写过的值会
     * 按记录的原值补一次，绝不叠加双加（没有记录就不动）。
     */
    private fun ensureStackAdjusted(st: State) {
        if (st.suspended || st.originalHeight <= 0) return
        if (st.stackAdjusting) return
        if (!findStack(st)) return
        reconcileFromSource(st)
    }

    /** 恢复栈顶：只在当前值仍是我们调整过的值时写回系统原值（挂起/卸载时调用）。 */
    private fun restoreStackTop(st: State) {
        if (st.stackAdjusting) return
        val adjusted = st.lastAdjustedWritten ?: return
        st.lastAdjustedWritten = null
        val sys = st.lastSystemOriginal ?: return
        if (!findStack(st)) return
        val cur = readTopPadding(st) ?: return
        if (cur == adjusted) {
            writeTopPadding(st, sys)
            logFn("stack sync: topPadding restored to system value $sys")
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
            // 充电状态在覆盖层求值里恒按未充电（v2.0.20）：锁屏
            // 主题自带的充电组（visibility 靠 #battery_state 触
            // 发的帧图/充电背景/百分比）是锁屏场景的东西，小乐
            // 口径只要锁屏的时间样式、不要其他——充电触发的整
            // 组在通知中心永不出现。电量数值（battery_level）
            // 保持真值，电量小模块/图标不受影响。这是通用规
            // 则，对所有主题一视同仁；st.batteryState 本身仍记
            // 真值，只是喂给主题求值的这一处固定为 0。
            batteryState = 0,
            weather = WeatherSource.vars(),
            screenWidthPx = w,
            screenHeightPx = screenHeightPx(st.header),
            // 星期编号模式按主题下发（v2.0.23，戳记第 10 行）：
            // 当前主题在设置里切了「周一起算」才换算，默认周日
            // 起算与引擎原口径一致。
            weekdayMondayStart = st.appliedStamp?.weekdayMondayStart ?: false,
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
                    // v2.0.6 横幅；页面切换自 v2.0.11 起不挂起、
                    // 走跟随，applyGate 只负责把切换中被 cc 挂起
                    // 的 State 恢复为跟随显示）：与各 hook 的即时
                    // 重评共用 applyGate；挂起期保持整组 GONE，
                    // 条件解除后经 attach 恢复。
                    maybeClearStuckPageSwitch()
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
                        ensurePreDrawListener(st.header, st)
                        st.appliedStamp?.let { applyHeight(st.header, st, it) }
                        // 每秒把覆盖层 bounds 与头部重新对齐一次
                        alignOverlay(st)
                        // 通知栈顶兜底（主路径是写入口拦截，见
                        // installStackWriteInterceptor）
                        ensureStackAdjusted(st)
                    }
                    logDiag(st)
                    WeatherSource.maybeRefresh(st.header.context)
                    // 秒数刷新（v2.0.21 改离屏渲染）：面板可见时
                    // 每秒把下一帧成品渲好再贴图；不可见时只在
                    // 还没有成品时渲一帧备着（下拉第一帧就是成
                    // 品），已有成品就不空转（v2.0.5 瘦身口径）。
                    val tvTick = st.themeView
                    if (wrapper.isShown) {
                        tvTick?.refreshContent()
                        tvTick?.invalidate()
                    } else if (tvTick != null && tvTick.contentStale() &&
                        tvTick.lastRenderAtMs == 0L
                    ) {
                        tvTick.refreshContent()
                    }
                    // 心跳（v2.0.5）：每 10 秒一行，drawCalls 在
                    // 面板可见时应稳定每秒 +1、不可见时不涨——用
                    // 来真机验收「秒数逐秒跳、主线程不被塞满」。
                    st.tickCount++
                    if (st.tickCount % 10 == 0) {
                        logFn(
                            "overlay heartbeat #${st.tickCount / 10}: " +
                                "drawCalls=${st.themeView?.drawCalls} " +
                                "renders=${st.themeView?.renderCount} " +
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
        // 首轮立即跑（v2.0.11 首显即时）：旧版首轮也延迟 1 秒，
        // 下拉首显要等它才对齐/重绘；runnable 内部的再调度仍是
        // 1 秒节律，稳态开销不变。
        st.handler.post(runnable)
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
                "effA=${effectiveShadeAlpha()} visFlag=$shadeContainerVisible " +
                "pullTrk=$shadePullTracking " +
                "chA=$shadeCommonAlpha/$shadeTimeAlpha " +
                "cXY=${shadeContainerX}/${shadeContainerY} " +
                "hTXY=${header.translationX}/${header.translationY} " +
                "child=$childIdx/${parent?.childCount} clip=${parent?.clipChildren} | " +
                "themeView=[${tv?.width}x${tv?.height}] vis=${tv?.visibility} " +
                "alpha=${tv?.alpha} drawCalls=${tv?.drawCalls} " +
                "renders=${tv?.renderCount} " +
                "gate=${if (shadeSettledClosed(st)) "closed" else "open"} " +
                "expF=${panelExpandedFraction(st)} tail=${st.gateTailLatched} " +
                    "cfm=${st.gateOpenConfirmAtMs >= 0L} rel=${st.gateLatchReleaseAtMs >= 0L} " +
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
        // 栈顶核对（v2.0.8 改真量测）：旧口径用「栈视图 window Y +
        // topPadding + translation」拼 stackTop——那只是 topPadding
        // 的回声（历版数据可证 stackTop 恒等于 topPadding），不是
        // 独立量测，gap 数字一直在自证。改直接量第一张通知卡
        // （NSSL 第一个可见且有高度的子视图，优先类名含
        // ExpandableNotificationRow 的行）的实际 window Y，与
        // headerBottom 比 realGap：≈0 才是真对齐，负数是通知真
        // 钻进了覆盖层。system/adjusted 为来源追踪的记录值。
        val stack = st.stackScroller
        if (stack != null) {
            val hLoc = IntArray(2)
            header.getLocationOnScreen(hLoc)
            val headerBottom = hLoc[1] + header.height
            var firstCardY: Int? = null
            var firstCardClass = ""
            if (stack is ViewGroup) {
                var fallbackY: Int? = null
                var fallbackClass = ""
                for (idx in 0 until stack.childCount) {
                    val child = stack.getChildAt(idx)
                    if (child.visibility != View.VISIBLE || child.height <= 0) continue
                    val cLoc = IntArray(2)
                    child.getLocationOnScreen(cLoc)
                    if (child.javaClass.name.contains("ExpandableNotificationRow")) {
                        firstCardY = cLoc[1]
                        firstCardClass = child.javaClass.simpleName
                        break
                    }
                    if (fallbackY == null) {
                        fallbackY = cLoc[1]
                        fallbackClass = child.javaClass.simpleName
                    }
                }
                if (firstCardY == null && fallbackY != null) {
                    firstCardY = fallbackY
                    firstCardClass = fallbackClass
                }
            }
            val tp = readTopPadding(st)
            logFn(
                "overlay stack check #${st.diagTicks}: headerBottom=$headerBottom " +
                    "firstCardY=${firstCardY ?: "none"}($firstCardClass) " +
                    "realGap=${firstCardY?.let { it - headerBottom } ?: "n/a"} " +
                    "topPadding=$tp system=${st.lastSystemOriginal} " +
                    "adjusted=${st.lastAdjustedWritten} suspended=${st.suspended}"
            )
        } else {
            logFn("overlay stack check #${st.diagTicks}: stack not located yet")
        }
    }

    /**
     * 页态挂起（v2.0.3 锁屏 / v2.0.4 控制中心 / v2.0.6 横幅）：把覆盖层整组
     * 藏起来、原头部内容与高度恢复原样，但保留 State 与
     * tick——回到通知中心页后 tick 会经 attach 自动恢复挂载。
     * 与 teardown 的区别：不摘状态、不停 tick。挂起/原因变化时各
     * 记一行，带 wrapper 的可见性数值（0=VISIBLE、8=GONE），
     * 便于核对整组是否真的撤下去了。
     */
    private fun suspendOverlay(header: ViewGroup, st: State, reason: String) {
        val wasSuspended = st.suspended
        val prevReason = st.suspendReason
        st.suspended = true
        st.suspendReason = reason
        // 斜率状态复位（v2.0.14）：恢复后的第一次应用直接对齐
        st.followAlpha = -1f
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
                    "cc=${isControlCenterActive(header)}, sw=$pageSwitching, " +
                    "hun=${isHeadsUpActive()}) " +
                    "wrapperVis=${st.wrapper?.visibility}"
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
            st.preDrawListener?.let { listener ->
                try {
                    header.viewTreeObserver.removeOnPreDrawListener(listener)
                } catch (t: Throwable) {
                    // 观察者已失效就算了
                }
                st.preDrawListener = null
            }
            unregisterBatteryReceiver(st)
            st.themeView?.releaseBitmap()
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

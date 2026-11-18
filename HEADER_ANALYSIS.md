# HonorShadeClock 正式版可行性分析：通知中心头部时钟 Compose 代码

- 样本：com.android.systemui 17.0.0.1（versionCode 1700000001），aapt badging 核对无误
- 工具：jadx 1.5.6 定向反编译（classes2.dex 为主），类名/方法名**未混淆**，可读性好；Compose trace 字符串自带源码文件名与行号（NotifCenterHeader.kt）
- 反编译产物：~/workspace/systemui-decompile/src/（关键类源码）、~/workspace/systemui-decompile/resources_values.txt（全量资源值）
- 结论先行：**原样改造有缝可钻，而且是两条互相独立的缝**——「放大」走资源替换（几乎零风险），「居中」走替换一处写死 x=0 的 placement lambda（中等风险）。覆盖路线只作兜底。

---

## 1. HnNotificationCenterHeader 的初始化全貌：content 是谁塞的

### 1.1 View 本体

`com.android.systemui.statusbar.notification.header.HnNotificationCenterHeader extends com.android.systemui.common.view.BasicComposeView implements Dumpable`（classes2.dex）

- 构造器只做两件事：`mNotifCenterHeaderInteractor` 与 `mDumpManager` 两个 `LazyProvider.SafeProvider`，惰性 `Dependency.get(NotifCenterHeaderInteractor.class)` / `Dependency.get(DumpManager.class)`。**注意：interactor 的实际接口是 panelheader 包的 `NotifCenterHeaderInteractor`，不是字段名暗示的其他类。**
- `onFinishInflate()`：全文唯一的内容注入点——
```java
((NotifCenterHeaderCompose) Dependency.get(NotifCenterHeaderCompose.class)).bind(this);
```
- `onAttachedToWindow()` / `onConfigurationChanged()`：把自身 LayoutParams.height 设为 `getResources().getDimensionPixelSize(R.dimen.notification_panelview_margin_top)`（= **80dp**，资源 id 0x7f071469；探针实测 280px ÷ 密度 3.5 = 80dp，吻合）。头部高度就是这一个 dimen，同时也被 composable 内部用作容器高度（见 §2.1）。
- `setAlpha(float)` 被重写：不直接改 View alpha，而是转交 interactor 的 `updateAnimValueCommonAlpha(f)` / `updateAnimValueTimeAlpha(f)`（仅 f=0/1 时），自身 alpha 恒为 1——**头部一切视觉量（透明度/缩放/位移）都由 interactor 的 StateFlow 驱动**，这是 §4 评估动画耦合的依据。

### 1.2 bind 链：content state 的写入

`com.android.systemui.statusbar.notification.panelheader.compose.NotifCenterHeaderComposeImpl extends NotifCenterHeaderCompose`：

```java
public final void bind(HnNotificationCenterHeader header) {
ComposableLambdaImpl lambda = new ComposableLambdaImpl(927037542, true,
new NotifCenterHeaderComposeImpl$$ExternalSyntheticLambda0(this, header)); // invoke → Content(header, composer, 0)
header.shouldCreateCompositionOnAttachedToWindow = true;
((SnapshotMutableStateImpl) header.content).setValue(lambda); // ← 塞进 BasicComposeView.content
if (header.isAttachedToWindow()) header.ensureCompositionCreated();
}
```

`BasicComposeView`（classes.dex）本体极简：`content: MutableState<Function2>` 构造时 `mutableStateOf(null)`；其 `Content$1(int, InternalComposer)` 只是把 state 里的 Function2 取出来 invoke。另有 `listeners: List`，`onVisibilityAggregated` 时逐个回调 `viewModel.updateHeaderVisibility$1(z)`。

### 1.3 Content：ViewModel 的创建与真正的 UI 入口

`NotifCenterHeaderComposeImpl.Content(BasicComposeView, InternalComposer, int)`（NotifCenterHeaderComposeImpl.kt:21）：

1. `SysUiViewModelKt.rememberViewModel("NotifCenterHeader", null, factoryLambda)` → 得到 `NotifCenterHeaderViewModel`（实现类 `NotifCenterHeaderViewModelImpl`，工厂为其内部 `Factory`，由 ComposeImpl 的 `viewModelFactory` 字段注入）。
2. `DisposableEffect(Unit, Lambda2{view, viewModel})`：把 viewModel 注册进 view 的 listeners（可见性回调），dispose 时摘除。
3. `NotifCenterHeaderKt.ProvidesForHeader(Lambda3{viewModel})` → 内层即 `NotifCenterHeaderKt.NotifCenterHeader(viewModel, composer, 0)`。
4. `ProvidesForHeader`（NotifCenterHeader.kt:875）只提供一个 CompositionLocal：`LocalAppLangState`（AppLangState{isChinese, isTibetan}，按当前 Locale 语言判定），供日期排版分支使用。

**数据流总览**：ViewModel 的几乎所有 StateFlow（timeScale/timePivot/timeAlpha/dateTranslation/commonAlpha/headerContainerTransitionX/Y/mgeBtnTranslation/overrideClockColor）都直接透传自 `NotifCenterHeaderInteractor`（ViewModelImpl 构造器里逐个 `this.xxx = notifCenterHeaderInteractor.getXxx()`）；interactor 实现类 `NotifCenterHeaderInteractorImpl` 是这些动画状态与时钟文本的真正持有者。

---

## 2. 头部 composable 本体：结构、字号、对齐

全部位于 `com.android.systemui.statusbar.notification.panelheader.compose.NotifCenterHeaderKt`（classes2.dex，源码 NotifCenterHeader.kt）。子 composable 相互独立，签名如下（省略 Composer/changed 尾参）：

| composable | 签名（关键参数） | 源码行 |
|---|---|---|
| NotifCenterHeader | `(NotifCenterHeaderViewModel)` |:124 |
| DateAndTime | `(Modifier, long color, State clockText, State, State, MutableStateFlow timePositionInParent, Function0 onClickTime, Function0 onClickDate, Function1, Function1 onDateTranslationY, NotifCenterHeaderViewModel)` |:472 |
| HeaderDateTimeText | `(State text, String tag, Modifier, long fontSize, long color, State overrideColor, TextStyle, Function0 onClick, String fontFeatureSettings, AutoSizeStepBased, Function1 onTextLayout)` |:694 |
| OverrideTextColorText | `(State text, String tag, Modifier, State overrideColor, AutoSizeStepBased, TextStyle, Function1 onTextLayout, long color)` |:725 |
| TrafficUi | `(NotifCenterHeaderViewModel, TrafficState, long color, Modifier, Function1, Function0 onClickTraffic)` |:307 |
| TrafficTexts / TrafficDivider | `(String, long color, Modifier, Function1)` / `(long color, boolean)` | — |
| ManagerBtn | `(NotifCenterHeaderViewModel, Function0)` |:400 |
| ProvidesForHeader | `(ComposableLambdaImpl)` |:875 |

### 2.1 根布局：SubcomposeLayout，三块子内容写死左对齐

`NotifCenterHeader(viewModel)`：
- `collectAsState(viewModel.getClockText())` 拿时钟文本 State；`viewModel.getTrafficState()` 拿流量状态；
- 边距：手机竖屏时起始边距**代码写死 18.0f dp**（平板/横屏/大屏模式才读 `R.dimen.clockContainerParams_content_margin_start`=14dp）；流量起始边距、底部边距等读 dimen（见附表）；
- 容器：`SubcomposeLayout(Modifier.fillMaxWidth().height(R.dimen.notification_panelview_margin_top /*80dp*/), Lambda7)`。

`Lambda7`（Function2\<SubcomposeMeasureScope, Constraints, MeasureResult\>）只做 `layout(minWidth, minHeight, placement = Lambda9)`。**真正的测量与摆放全在 `NotifCenterHeaderKt$$ExternalSyntheticLambda9 implements Function1<PlacementScope, Unit>`（30 个 public synthetic 捕获字段 f$0..f$29）**，其 invoke 逻辑（已逐行核对）：

1. `subcompose("mgeBtn")` → ManagerBtn，自由约束测量；
2. 流量可见时 `subcompose("trafficUi")` → TrafficUi，maxWidth = 总宽 − 铃铛宽；**placeRelative(traffic, 0, 头高 − 流量高 − trafficMarginBottomPx − 动画偏移 − 大字体附加量)**；
3. `subcompose("dateAndTime")` → DateAndTime，maxWidth = 总宽 − 铃铛宽；**placeRelative(dateAndTime, 0, 流量顶（或头底）− 日期容器高 − (dateView_traffic_panelview_margin_bottom + 2dp))**；
4. 铃铛 **placeRelative(btn, 总宽 − 铃铛宽, 计算出的 y)**（右上角）；
5. 顺带把 dateAndTime 的位置（x = 密度 × 起始边距）写进 `timePositionInParent` 流、把 (总宽−铃铛宽, 铃铛y) 写进一个 IntSize State——供场景转场动画引用。

**关键事实：dateAndTime 与 trafficUi 的摆放 x 都是写死的 0**，起始边距是作为 padding/参数在内容内部消化的（时钟组视觉左边缘 ≈18dp）。居中改造只需改这一处 x，见 §4。

### 2.2 DateAndTime 内部：时钟+日期同行基线对齐

DateAndTime 是一个自定义 `Layout`，MeasurePolicy 为内部类
`NotifCenterHeaderKt$DateAndTime$2$1 implements MeasurePolicy`，方法
`measure-3p2s80s(MeasureScope, List<Measurable>, long): MeasureResult`（dex 方法名即 `measure-3p2s80s`）：

- 子项 [0]=时钟、[1]=日期；时钟以 (maxW × maxH) 测，日期以剩余宽度测；
- 容器宽 = 时钟宽 + 日期宽，高 = 时钟高（约束为固定高时取约束高）；
- 摆放由 `NotifCenterHeaderKt$DateAndTime$2$1$$ExternalSyntheticLambda0` 执行：`placeRelative(时钟, 0, clockY)`、`placeRelative(日期, 时钟宽, dateY)`，两者按 FirstBaseline 对齐（日期的 baseline/desc 由其 onTextLayout 回调写入 `$dateBaseline/$dateDesc` 两个 MutableState 供 policy 读取，藏文有 0.1 高度修正）；
- policy 还回写 `timePositionInParent = Offset(0, clockY)` 并回调 `onDateTranslationY(dateY 相对量)`——动画系统靠这两个值定位日期/时间，改造时必须保留语义（见 §4 备选方案的风险）。

### 2.3 时钟字号来源（重点确认：dimen 资源）

DateAndTime 内的 `clockSizeSp`（NotifCenterHeader.kt:891）：

```kotlin
val clockSizeSp = with(LocalDensity) {
resources.getDimensionPixelSize(R.dimen.notif_center_header_clock_text_size).toSp()
} * if (shouldIncreasesFontScale()) 1.1f else 1.0f
```

- **`R.dimen.notif_center_header_clock_text_size`（id 0x7f0713d4）默认配置 = 40dp**（另有一套配置 24dp，应为横屏/小宽场景）。经 px→sp 换算后（fontScale=1 时数值 ≈40sp）作为 `fontSize` 参数传给时钟的 HeaderDateTimeText，最终在 HeaderDateTimeText 里 `textStyle.merge(fontSize = 该值)` 后交给 `BasicText`（OverrideTextColorText，maxLines=1）。时钟**没有 AutoSize**（autoSize 参数传 null）。
- `shouldIncreasesFontScale()`（:677）= 简易模式 && fontScale ≥ 1.3 && 中文 && 竖屏时才 ×1.1，日常不触发。
- 时钟 TextStyle：LocalTextStyle 拷贝，fontFamily 来自 `themeAware` 主题字体，fontFeatureSettings = `R.string.sb_clock_font_feature_settings`，阴影 `Shadow(alpha=0.5, color=0x52000000, offset=(0,2))`，PlatformTextStyle(includeFontPadding=true)。文字颜色 = 根部 `R.color.hnnotification_center_color`，可被 `viewModel.getOverrideClockColor()` 流覆盖。
- 时钟的 Modifier 带 `graphicsLayer`（Lambda16 构造）：scale/pivot/alpha/颜色覆盖分别 collectAsState 自 ViewModel 的 `getTimeScale()/getTimePivot()/getTimeAlpha()/getOverrideClockColor()`——即面板展开动画会缩放/淡入时钟，这是 §4 方案 4 的耦合点。
- 时钟可点击（performanceClickable + 按压背景圆角 12dp 的 `R.color.notification_button_pressed_bg_color`），点击走 ViewModel.onClickTime()。

### 2.4 日期字号

- 字号 = `R.dimen.notif_center_header_clock_date_size`（id 0x7f0713d3）= **14dp**；
- 中文：固定 14dp（简易大字体非横屏时改用 `R.dimen.date_view_font_size`=14dp ×1.1）；非中文 + 手机 + 非大屏：AutoSizeStepBased(min=`R.dimen.date_view_font_automin_size`=10dp, max=14dp, step=0.25sp) 自适应；
- 日期文本 State 来自 DateAndTime 内部 `collectAsState(viewModel.getDateText())`；日期 Modifier 带 graphicsLayer（dateTranslation/commonAlpha 流）与 onTextLayout 基线回写。

### 2.5 时钟/日期文本怎么产生（状态流，非现场格式化）

- ViewModelImpl：`clockText = distinctUntilChanged(transformLatest(headerVisibility) { flatMapLatest → centerHeaderInteractor.getClockTime()})`，`dateText` 同理走 `getClockDate()`（可见时才订阅）。
- InteractorImpl（`NotifCenterHeaderInteractorImpl`）：
- `clockTime: ReadonlyStateFlow<String>` = `stateIn(map(clockTimeEvent))`，初值 `HwNotchUtils.getDateTime(appContext, false, false, true)`；
- `clockTimeEvent = callbackFlow(NotifCenterHeaderInteractorImpl$clockTimeEvent$1)`，内部注册时间跳变回调（`$clockTimeEvent$1$callback$1`），每分钟/时区/语言变化时推事件；
- `clockDate: ReadonlyStateFlow<String>` = `stateIn(combine(clockTimeEvent, _dataFormat) → $clockDate$1)`，初值 `getCurrentClockDate(dateFormat)`；`_dataFormat` 由 locale/配置流合成 datePattern，用 `android.icu.text.DateFormat.getInstanceForSkeleton(datePattern, locale)` 格式化 `currentDate`。
- composable 侧只 collectAsState 显示字符串，**不在绘制处格式化**。

---

## 3. 流量行数据来源

**不经头部 interactor**，来自 SystemUI 自己的流量子系统：

- `NotifCenterHeaderViewModelImpl.onActivated()` 时把 `trafficListener`（`NotifCenterHeaderViewModelImpl$trafficListener$1`，实现 `TrafficPanelManager.TrafficPanelChangeListener`，含 `refreshDataBySlot(TrafficPanelManager.TrafficInfo)`、`onNoSims()`、`onNoMealSetted()`）注册进单例 `com.android.systemui.traffic.TrafficPanelManager.getInstance().mTrafficPanelChangeListener`；onDeactivated 时摘除，并调用 `trafficPanelManager.notifyShowChanged(false)` 触发一次推送。
- `lastTrafficInfo: StateFlowImpl<TrafficInfo>` 收数据；`_trafficState = combine(showTraffic, lastTrafficInfo, 卡片状态流, NotifCenterHeaderViewModelImpl$_trafficState$1 变换)` → 产出密封接口 `panelheader.viewmodel.TrafficState`：
- `Hidden`（不显示）；
- `TipState` 子类 `NoSim`（prompt = R.string.traffic_tip_nosim）、`NoMeal(prompt)`；
- `Normal(String today, String residue, String total)`——**三段都是已格式化好的字符串**。
- TrafficUi 渲染：Normal 时一行 Row(Arrangement.Start, Alignment.Top)：TrafficTexts(today) + TrafficDivider + TrafficTexts(residue) + TrafficDivider + TrafficTexts(total)，整行可点 → ViewModel.onClickTraffic()；TipState 时只显示一行提示文案；Hidden 时不画（但仍调用 updateTrafficVis() 上报可见性）。

**对覆盖路线的意义**：数据不封闭在 Compose 里——模块代码与 SystemUI 同进程，既可反射 `TrafficPanelManager.getInstance()` 自行注册同款 listener 拿 `TrafficInfo`，也可直接读 ViewModel 的 `getTrafficState()`（已格式化字符串，省去 TrafficInfo→文案的格式化复刻，该格式化在 `$_trafficState$1` 变换里）。所以流量行**不是**覆盖路线的拦路虎；覆盖路线真正的成本在动画镜像与铃铛（见 §4 方案 5）。

---

## 4. 「居中 + 放大」可下手点清单（按可行性排序）

### 方案 1Xposed 资源替换时钟 dimen —— 风险极低

- 目标：`com.android.systemui` 的 `dimen/notif_center_header_clock_text_size`（0x7f0713d4，现值 40dp）；可同步替换 `dimen/notif_center_header_clock_date_size`（0x7f0713d3，现值 14dp）让日期等比长大。
- 做法：`IXposedHookInitPackageResources` 中对 SystemUI 包 `res.setReplacement("com.android.systemui", "dimen", "notif_center_header_clock_text_size", new XResources.DimensionReplacement(56f))`（单位 dp，运行时按密度换算）。
- 为什么干净：§2.3 已证字号是每次重组时 `resources.getDimensionPixelSize(R.dimen…)` 现读的，资源替换直接改读数值，不碰任何代码逻辑；Compose 的 LocalResources 就是宿主 Resources，替换生效。
- 效果与边界：只有放大、无居中。头部总高固定 80dp，时钟行锚定在容器下部（流量行之上），字号变大时行高向上生长——Compose Layout 默认不裁剪，56dp 左右预计无碍；再大可能与顶部状态栏/刘海区视觉冲突，需真机看。另注意该 dimen 有多套配置（默认 40dp / 另一套 24dp），替换对各配置的实际影响需在横屏下顺带验证。
- 崩溃风险：几乎为零（资源层）。版本耦合：只依赖 dimen 名，SystemUI 小版本更新大概率存活。

### 方案 2整体替换外层 placement lambda —— 中风险

- 目标：`com.android.systemui.statusbar.notification.panelheader.compose.NotifCenterHeaderKt$$ExternalSyntheticLambda9#invoke(java.lang.Object)`（Function1\<PlacementScope, Unit\>，字段 f$0=SubcomposeMeasureScope、f$1=Constraints、f$2..f$29 为捕获的边距/状态/ViewModel）。
- 做法：XC_MethodReplacement 复刻其 invoke：用捕获字段重建 Lambda8（mgeBtn 内容）、Lambda11（trafficUi 内容）、Lambda12（dateAndTime 内容）三个 ComposableLambdaImpl 并照原样 subcompose/measure 与计算全部 y；**唯一改动**：dateAndTime 的 `placeRelative(placeable, 0, y)` 改为 `placeRelative(placeable, (maxWidth − mgeBtn.width − dateAndTime.width) / 2, y)`。流量行 x 同为该 invoke 内的 0，可按需一并居中或保持左对齐。时钟+日期组内相对排版（基线对齐、间距）原样不动。
- 收益：真·整组居中，且不改变任何 measure 结果与状态回写（timePositionInParent 等仍由原代码路径写入——注意 Lambda9 本体还负责写这两个状态，复刻时必须一字不漏地保留）。
- 风险：① 复刻体量大（165 行、30 字段），字段错位 = 布局错乱或 measure 期崩溃 → SystemUI 反复重启；必须全程 try/catch 并在异常时回退 `XposedBridge.invokeOriginalMethod`。② 与 Compose 版本强耦合：依赖 SubcomposeMeasureScope/Placeable 的本机签名与该 lambda 的字段布局，Honor 改版此文件即失效——上线前用类/字段存在性自检，不匹配就整体 no-op。③ placement 在每帧动画中都会跑，复刻代码不得有额外分配/反射热路径（字段在 hook 安装时解析一次）。

### 方案 3整体替换 DateAndTime 的 MeasurePolicy —— 中风险

- 目标：`com.android.systemui.statusbar.notification.panelheader.compose.NotifCenterHeaderKt$DateAndTime$2$1#measure-3p2s80s(androidx.compose.ui.layout.MeasureScope, java.util.List, long)`（实现 MeasurePolicy，捕获字段仅 5 个：$dateBaseline、$dateDesc、$isTibetan、$onDateTranslationY、$timePositionInParent）。
- 做法：复刻其 measure（71 行）：照原样测时钟/日期与算基线 y，但 `layout(时钟宽+日期宽, 高)` 改为 `layout(constraints.maxWidth, 高)`，placement 改为 clockX = (maxWidth − 时钟宽 − 日期宽)/2、dateX = clockX + 时钟宽；`timePositionInParent` 须写入**新的** Offset(clockX, clockY)（原版写 (0, clockY)），`onDateTranslationY` 回调保留。
- 与方案 2 的取舍：体量小得多；但改了容器宽度语义——外层 Lambda9 测 dateAndTime 时给的 maxWidth = 总宽 − 铃铛宽且只用其高度做 y 计算，宽度变大不影响外层数学；副作用是 DateAndTime 的点击热区（时钟/日期的 performanceClickable）会横向铺满，以及 timePosition 的 x 语义变化可能影响场景转场动画中对时钟位置的引用（静态显示无影响）。模块侧需要调用 Compose layout API（MeasureScope.layout/Placeable.placeRelative 等），建议 compileOnly 对齐本机 Compose UI 版本或全反射实现，避免签名漂移。

### 方案 4劫持 timeScale 状态流 —— 高风险，不作主方案

- 目标：`NotifCenterHeaderInteractorImpl#updateTimeScale(float)`（及 `updateTimePivot(float)`），或 ViewModel 的 `getTimeScale()` 流。时钟 graphicsLayer 的 scale 来自此流，放大入参即可视觉放大时钟。
- 否决理由：该流由面板/场景动画系统（interactor 的 appliedAnimState/AppliedState 机制）持续写入，模块改值会与开合动画打架；缩放绕 pivot（非文本中心）会平移时钟；80dp 固定高度下大倍率必溢出；且只能放大、不能居中。仅在方案 1 失效时考虑。

### 方案 5覆盖路线 —— 工程量最大

- 形态：保留原 bind 与数据流，在 View 层中和原内容并叠加自绘内容。原内容的可中和点：HnNotificationCenterHeader 是 AbstractComposeView，其绘制实体是内部的 AndroidComposeView 子视图（探针 v0.2.1 日志实测 header 下挂 AndroidComposeView 1196×280）——把该子视图设 INVISIBLE 即可让原头部变空白（头部 80dp 盒子与面板布局不受影响），然后在 header（ViewGroup）内 addView 自绘层置顶。
- 必须复刻的元素（对照 §2/§3 实测）：
1. 大时钟：文本取 interactor `getClockTime()` StateFlow 当前值（或自注册时间广播），字号/字体/阴影/颜色按 §2.3 复刻（40sp 基准、sb_clock_font_feature_settings、Shadow(0.5, 0x52000000, (0,2))、hnnotification_center_color），点击 → 打开时钟（原走 ViewModel.onClickTime）；
2. 日期：`getClockDate()` 当前值，14sp，与时钟的相对排版自定（覆盖路线下可自由居中排版）；
3. 流量行：TrafficState 三态（Hidden/TipState/Normal 三段文案+分隔线），数据按 §3 复用（反射 TrafficPanelManager 注册 listener，或读 ViewModel trafficState）；
4. 右上铃铛：原为原生 View `com.android.systemui.statusbar.notification.panelheader.NotifMgeBtn` 经 AndroidView 嵌入（探针日志中的 ViewFactoryHolder/NotifMgeBtn 即它），点击走 ViewModel.onClickNotifMgeBtn() 并有 mgeBtnTranslation 位移动画——覆盖版需自建等效按钮并想办法触达该回调（ViewModel 实例只存在于 composition 内，外部拿取要绕 DisposableEffect 的 lambda 字段，脆弱）；
5. **动画镜像（最容易被低估）**：头部在面板开合/场景转场时由 interactor 的 commonAlpha/timeAlpha/headerContainerTransitionX/Y/dateTranslation/timeScale 等流驱动淡入/位移/缩放，自绘层必须订阅同一批流逐项镜像，否则动画期间原头部（已隐藏）与自绘层行为脱节，出现跳变/残影；
6. 边角情形：藏文日期排版、日期 AutoSize、按压背景、日期/时钟各自的点击与无障碍语义。
- 结论：能做，但复刻面是方案 1+2 的数倍，且动画镜像与铃铛回调是长期维护负担。仅当资源替换与 lambda 替换都被实测堵死时启用。

### 明确无缝可钻的点（避免后人重走）

- HnNotificationCenterHeader / BasicComposeView 控件层：无任何字号/对齐字段或 setter（v0.3.0 探针结论与本次反编译互证）；`content` MutableState 替换成模块自带 composable 的路线不成立——模块不携带 Compose 编译产物、且宿主 Compose 运行时版本不可控。
- 语义树（Semantics）层：v0.3.0 已实测反射取 rootSemanticsNode 失败，且语义树本身只读、改不了布局。
- ViewModel/Interactor 的文本流：只能改"显示什么字"，改不了字号与位置（字号在 composable 里从 dimen 现算）。

---

## 5. 推荐组合与施工顺序

1. **第一步（放大）**：方案 1 资源替换，时钟 dimen 40dp → 目标值（建议先试 54dp，日期 14dp → 16dp 等比），真机看溢出与横屏配置。
2. **第二步（居中）**：方案 2 替换 Lambda9.invoke（带原方法回退与类结构自检）；若复刻成本/稳定性不达标，降级方案 3。
3. 两步互相独立、可分别开关；任一步异常都不应影响 SystemUI 存活（资源替换天然安全；lambda 替换必须 no-op 自检 + try/catch 回退）。
4. 方案 5 仅在前两步被实测堵死时启动。

## 附：关键 dimen 现值（默认配置，aapt dump 实测）

| 资源名 | id | 现值 | 用途 |
|---|---|---|---|
| notif_center_header_clock_text_size | 0x7f0713d4 | 40dp（另一配置 24dp） | 时钟字号（方案 1 目标） |
| notif_center_header_clock_date_size | 0x7f0713d3 | 14dp | 日期字号上限 |
| date_view_font_size | 0x7f070484 | 14dp | 日期字号（简易大字体分支） |
| date_view_font_automin_size | 0x7f070483 | 10dp | 日期 AutoSize 下限 |
| notification_panelview_margin_top | 0x7f071469 | 80dp | 头部高度（View + 容器共用，慎动） |
| clockContainerParams_content_margin_start | 0x7f0702f1 | 14dp | 起始边距（仅平板/横屏/大屏；手机写死 18dp） |
| traffic_panelview_margin_bottom | 0x7f071c73 | 引用型 | 流量行底部边距 |
| dateView_traffic_panelview_margin_bottom | 0x7f070464 | — | 日期与流量行间距（代码里再 +2dp） |
| notification_manager_margin_end | 0x7f071431 | 20dp | 流量起始边距（部分分支） |
| notification_magager_hot_hegiht | 0x7f07142b | 4dp | 铃铛热区高 |
| notification_magager_margin_bottom | 0x7f07142c | 12dp | 铃铛底部边距 |

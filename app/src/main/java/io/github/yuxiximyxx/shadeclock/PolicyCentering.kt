package io.github.yuxiximyxx.shadeclock

import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

private val policyFallbackLogCount = AtomicInteger(0)
private val policyDiagLogCount = AtomicInteger(0)
private val placementFallbackLogCount = AtomicInteger(0)

private fun describeThrowable(t: Throwable): String {
    val cause = if (t is InvocationTargetException && t.cause != null) t.cause!! else t
    return "${cause.javaClass.name}: ${cause.message}"
}

/**
 * 居中（v1.1.2 起的新路线）：替换 DateAndTime 内部 MeasurePolicy 的
 * measure-3p2s80s，只给时钟/日期在盒内的摆放 x 整体加居中偏移。
 * v1.0–v1.1.1 在外层 Lambda9 挪 dateAndTime 盒子的路线就此撤销
 * （HeaderCentering 已删），外层摆放恢复系统原逻辑。
 *
 * 为什么必须下沉到这一层（v1.1.1 真机 diag 定案 + 本轮源码/字节码审计）：
 * - diag #1（流量未加载的首轮）：dateAndTime 盒测得 611x280，
 *   x=198、centered=true——外层居中公式本身没错；
 * - diag #2–#5（稳态）：盒测得 1007x252，恰 = maxW 1196 − 铃铛 189，
 *   判别条件（内容窄于可用宽）不成立 → x=0，头部纹丝不动。
 * - 满宽成因（policy 字节码逐指令核对）：policy 返回宽 =
 *   时钟宽 + 日期宽，两子均为松散约束下的自然宽，没有任何一支
 *   把宽度撑到 maxWidth；撑满的是第二个子件「日期」——日期以
 *   剩余宽（maxW − 时钟宽）测量，其自然宽（16dp 日期字号下
 *   「11月18日星期三」约 396px）不小于剩余宽时，BasicText 的
 *   测得宽被自身约束收紧到恰好剩余宽，于是总宽恰好 = maxW。
 *   首轮 611 是因为日期文本 State 初值为空串（DateAndTime 里
 *   collectAsState(getDateText(), EMPTY_STRING)），日期宽≈0，
 *   盒宽只剩时钟本体。所以在 Lambda9 层挪盒子永远居中不了：
 *   稳态下盒子本身满宽、字形只占盒内左侧。
 * - 盒内原排版（policy + 其 placement lambda 字节码）：时钟
 *   (0, clockY)、日期 (时钟宽, dateY)，水平间距 0；基线对齐——
 *   定高分支（maxHeight==minHeight）clockY = 日期基线 + dateY
 *   − 时钟 FirstBaseline，非定高分支 clockY = 1dp、dateY =
 *   时钟 FirstBaseline − 日期基线，两分支都是同一条基线。
 *   placement lambda 还回写 timePositionInParent = Offset(0, clockY)。
 *
 * 本复刻与原 measure 逐项一致：测量约束（两子均走
 * ConstraintsKt.Constraints$default(maxW 或剩余宽, maxH, 5)，
 * 字节码实测该函数 = Constraints(0, maxW, 0, maxH)，mask 5 下
 * 两参直通）、基线读数、两分支的 clockY/dateY 公式、
 * onDateTranslationY(dateY) 回调、layout(时钟宽+日期宽, 高) 的
 * 返回尺寸，一律不动。唯一改动：自备 placement（经
 * java.lang.reflect.Proxy 以宿主 Function1 身份注入，因模块与
 * 宿主的 kotlin-stdlib 不是同一个类，必须用宿主类加载器造
 * 代理），把日期摆放 x 改为 时钟宽 + offset、时钟摆放 x 改为
 * offset，其中 offset = (容器宽 − 内容总宽) / 2，容器宽 =
 * 约束 maxWidth，内容总宽 = 时钟宽 + 日期宽（即原版日期右缘），
 * clamp 到 ≥ 0。timePositionInParent 回写与原版逐字一致
 * （Offset(0f, clockY)，x 仍写 0——与 v1.0 起的既定取舍相同：
 * 静态显示无影响，场景转场动画若引用该流定位时钟仍按旧 x）。
 *
 * 保险丝（与前几版同口径）：
 * 1. 安装前结构自检：policy 类、5 个捕获字段（名/类型）、
 *    measure-3p2s80s 签名、全部 Compose 内部 API 句柄逐项核对；
 *    任一不符记一行原因后整体跳过居中 hook（放大不受影响）。
 * 2. 运行时兜底：复刻全程 try-catch，异常立即回退原方法，
 *    只记前 3 次；placement 内部异常先退回原版坐标再摆，
 *    仍失败则吞掉，绝不让异常冒泡进 SystemUI 布局。
 * 3. 有界诊断：每进程前 5 次记一行
 *    "policy diag #n: box=WxH clock=WxH date=WxH offset=N"。
 * 4. 热路径无反射查找：全部 Field/Method 在安装时解析一次。
 *
 * 全部调用走反射，是为了不给模块引入与宿主可能漂移的 Compose
 * 编译期依赖；方法名均为宿主 dex 中的真实 JVM 名，已用 dexdump
 * 对 17.0.0.1 的 APK 逐个核对。
 */
object PolicyCentering {

    private const val POLICY_NAME =
        "com.android.systemui.statusbar.notification.panelheader.compose.NotifCenterHeaderKt\$DateAndTime\$2\$1"
    private const val MEASURE_SCOPE_NAME = "androidx.compose.ui.layout.MeasureScope"
    private const val MEASURE_RESULT_NAME = "androidx.compose.ui.layout.MeasureResult"
    private const val MUTABLE_STATE_NAME = "androidx.compose.runtime.MutableState"
    private const val MUTABLE_STATE_FLOW_NAME = "kotlinx.coroutines.flow.MutableStateFlow"
    private const val FUNCTION1_NAME = "kotlin.jvm.functions.Function1"

    private const val FIELD_DATE_BASELINE = "\$dateBaseline"
    private const val FIELD_DATE_DESC = "\$dateDesc"
    private const val FIELD_IS_TIBETAN = "\$isTibetan"
    private const val FIELD_ON_DATE_TRANSLATION_Y = "\$onDateTranslationY"
    private const val FIELD_TIME_POSITION = "\$timePositionInParent"

    /**
     * 结构自检通过才安装居中 hook；任一不符只记一行原因、整体跳过
     * （放大功能不受影响）。[log] 为诊断输出通道。
     */
    fun install(lpparam: XC_LoadPackage.LoadPackageParam, log: (String) -> Unit) {
        val api = try {
            resolveApi(lpparam.classLoader)
        } catch (t: Throwable) {
            log("policy centering hook skipped: ${describeThrowable(t)}")
            return
        }
        try {
            XposedHelpers.findAndHookMethod(
                api.policyClass, "measure-3p2s80s",
                api.measureScopeClass, java.util.List::class.java, java.lang.Long.TYPE,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any? {
                        try {
                            val constraints = (param.args.getOrNull(2) as? Number)?.toLong()
                                ?: throw IllegalStateException("constraints arg is not a Number")
                            return measureCentered(
                                api, param.thisObject,
                                param.args.getOrNull(0), param.args.getOrNull(1),
                                constraints, log
                            )
                        } catch (t: Throwable) {
                            val n = policyFallbackLogCount.incrementAndGet()
                            if (n <= 3) {
                                log("policy measure failed (#$n), falling back to original: ${describeThrowable(t)}")
                            }
                            try {
                                return XposedBridge.invokeOriginalMethod(
                                    param.method, param.thisObject, param.args
                                )
                            } catch (ite: InvocationTargetException) {
                                throw ite.cause ?: ite
                            }
                        }
                    }
                }
            )
            log("policy centering hook installed: DateAndTime measure-3p2s80s replaced (structure self-check passed)")
        } catch (t: Throwable) {
            log("policy centering hook install failed: ${describeThrowable(t)}")
        }
    }

    // ================= 结构自检与句柄解析 =================

    private fun fail(reason: String): Nothing = throw IllegalStateException(reason)

    private fun resolveApi(cl: ClassLoader): PolicyApi {
        fun findClass(name: String): Class<*> = try {
            XposedHelpers.findClass(name, cl)
        } catch (t: Throwable) {
            fail("class not found: $name")
        }

        fun <T> resolve(what: String, block: () -> T): T = try {
            block()
        } catch (t: Throwable) {
            if (t is IllegalStateException) throw t
            fail("$what: ${t.javaClass.simpleName}: ${t.message}")
        }

        // ---- Compose 内部类（先解析，policy 签名核对要用） ----
        val measureScopeClass = findClass(MEASURE_SCOPE_NAME)
        val measurableClass = findClass("androidx.compose.ui.layout.Measurable")
        val measuredClass = findClass("androidx.compose.ui.layout.Measured")
        val placeableClass = findClass("androidx.compose.ui.layout.Placeable")
        val placementScopeClass = findClass("androidx.compose.ui.layout.Placeable\$PlacementScope")
        val alignmentLineClass = findClass("androidx.compose.ui.layout.AlignmentLine")
        val alignmentLineKtClass = findClass("androidx.compose.ui.layout.AlignmentLineKt")
        val constraintsClass = findClass("androidx.compose.ui.unit.Constraints")
        val constraintsKtClass = findClass("androidx.compose.ui.unit.ConstraintsKt")
        val densityClass = findClass("androidx.compose.ui.unit.Density")
        val stateClass = findClass("androidx.compose.runtime.State")
        val stateFlowImplClass = findClass("kotlinx.coroutines.flow.StateFlowImpl")
        val offsetClass = findClass("androidx.compose.ui.geometry.Offset")
        val function1Class = findClass(FUNCTION1_NAME)
        val unitClass = findClass("kotlin.Unit")

        // ---- 目标类：字段与 measure 签名 ----
        val policyClass = findClass(POLICY_NAME)
        if (policyClass.declaredFields.size != 5) {
            fail("policy declared field count ${policyClass.declaredFields.size} != 5")
        }
        fun policyField(name: String, expectedType: String): Field {
            val field = try {
                policyClass.getDeclaredField(name)
            } catch (t: Throwable) {
                fail("policy missing field $name")
            }
            if (field.type.name != expectedType) {
                fail("policy field $name type ${field.type.name} != $expectedType")
            }
            field.isAccessible = true
            return field
        }
        val fieldDateBaseline = policyField(FIELD_DATE_BASELINE, MUTABLE_STATE_NAME)
        val fieldDateDesc = policyField(FIELD_DATE_DESC, MUTABLE_STATE_NAME)
        val fieldIsTibetan = policyField(FIELD_IS_TIBETAN, "boolean")
        val fieldOnDateTranslationY = policyField(FIELD_ON_DATE_TRANSLATION_Y, FUNCTION1_NAME)
        val fieldTimePosition = policyField(FIELD_TIME_POSITION, MUTABLE_STATE_FLOW_NAME)
        resolve("policy measure-3p2s80s signature") {
            val m = policyClass.getDeclaredMethod(
                "measure-3p2s80s", measureScopeClass, java.util.List::class.java,
                java.lang.Long.TYPE
            )
            if (m.declaringClass !== policyClass) {
                fail("measure-3p2s80s declaring class is ${m.declaringClass.name}")
            }
            if (m.returnType.name != MEASURE_RESULT_NAME) {
                fail("measure-3p2s80s return type is ${m.returnType.name}")
            }
        }

        // ---- Compose 内部 API 句柄 ----
        fun constraintsGetter(name: String): Method = resolve("Constraints.$name") {
            val m = constraintsClass.getMethod(name, java.lang.Long.TYPE)
            if (!Modifier.isStatic(m.modifiers) || m.returnType !== Integer.TYPE) {
                fail("Constraints.$name is not static int")
            }
            m
        }
        val maxWidthMethod = constraintsGetter("getMaxWidth-impl")
        val maxHeightMethod = constraintsGetter("getMaxHeight-impl")
        val minHeightMethod = constraintsGetter("getMinHeight-impl")
        val constraintsDefaultMethod = resolve("ConstraintsKt.Constraints\$default") {
            val m = constraintsKtClass.getMethod(
                "Constraints\$default",
                Integer.TYPE, Integer.TYPE, Integer.TYPE
            )
            if (!Modifier.isStatic(m.modifiers) || m.returnType !== java.lang.Long.TYPE) {
                fail("Constraints\$default is not static long")
            }
            m
        }
        val measureMethod = resolve("Measurable.measure-BRTryo0") {
            val m = measurableClass.getMethod("measure-BRTryo0", java.lang.Long.TYPE)
            if (m.returnType !== placeableClass) {
                fail("measure-BRTryo0 return type is ${m.returnType.name}")
            }
            m
        }
        val measuredGetMethod = resolve("Measured.get(AlignmentLine)") {
            val m = measuredClass.getMethod("get", alignmentLineClass)
            if (m.returnType !== Integer.TYPE) {
                fail("Measured.get return type is ${m.returnType.name}")
            }
            m
        }
        val firstBaseline = resolve("AlignmentLineKt.FirstBaseline") {
            alignmentLineKtClass.getField("FirstBaseline").get(null)
                ?: fail("AlignmentLineKt.FirstBaseline is null")
        }
        val toPxMethod = resolve("Density.toPx-0680j_4") {
            val m = densityClass.getMethod("toPx-0680j_4", java.lang.Float.TYPE)
            if (m.returnType !== java.lang.Float.TYPE) {
                fail("toPx-0680j_4 return type is ${m.returnType.name}")
            }
            m
        }
        val stateGetValueMethod = resolve("State.getValue") {
            stateClass.getMethod("getValue")
        }
        val function1InvokeMethod = resolve("Function1.invoke") {
            function1Class.getMethod("invoke", Any::class.java)
        }
        val layoutDefaultMethod = resolve("MeasureScope.layout\$default") {
            val m = measureScopeClass.getMethod(
                "layout\$default",
                measureScopeClass, Integer.TYPE, Integer.TYPE, function1Class
            )
            if (!Modifier.isStatic(m.modifiers) || m.returnType.name != MEASURE_RESULT_NAME) {
                fail("layout\$default is not static MeasureResult")
            }
            m
        }
        val placeRelativeMethod = resolve("PlacementScope.placeRelative\$default") {
            val m = placementScopeClass.getMethod(
                "placeRelative\$default",
                placementScopeClass, placeableClass, Integer.TYPE, Integer.TYPE
            )
            if (!Modifier.isStatic(m.modifiers) || m.returnType !== Void.TYPE) {
                fail("placeRelative\$default is not static void")
            }
            m
        }
        val updateStateMethod = resolve("StateFlowImpl.updateState") {
            val m = stateFlowImplClass.getMethod("updateState", Any::class.java, Any::class.java)
            if (m.returnType !== java.lang.Boolean.TYPE) {
                fail("updateState return type is ${m.returnType.name}")
            }
            m
        }
        val offsetBoxMethod = resolve("Offset.box-impl") {
            val m = offsetClass.getMethod("box-impl", java.lang.Long.TYPE)
            if (!Modifier.isStatic(m.modifiers)) fail("Offset.box-impl is not static")
            m
        }
        val placeableWidthField = resolve("Placeable.width field") {
            val field = placeableClass.getField("width")
            if (field.type !== Integer.TYPE) fail("Placeable.width type is ${field.type.name}")
            field
        }
        val placeableHeightField = resolve("Placeable.height field") {
            val field = placeableClass.getField("height")
            if (field.type !== Integer.TYPE) fail("Placeable.height type is ${field.type.name}")
            field
        }
        val unitInstance = resolve("kotlin.Unit.INSTANCE") {
            unitClass.getField("INSTANCE").get(null) ?: fail("kotlin.Unit.INSTANCE is null")
        }

        return PolicyApi(
            policyClass = policyClass,
            hostClassLoader = cl,
            measureScopeClass = measureScopeClass,
            function1Class = function1Class,
            fieldDateBaseline = fieldDateBaseline,
            fieldDateDesc = fieldDateDesc,
            fieldIsTibetan = fieldIsTibetan,
            fieldOnDateTranslationY = fieldOnDateTranslationY,
            fieldTimePosition = fieldTimePosition,
            constraintsMaxWidth = maxWidthMethod,
            constraintsMaxHeight = maxHeightMethod,
            constraintsMinHeight = minHeightMethod,
            constraintsDefault = constraintsDefaultMethod,
            measureMeasurable = measureMethod,
            measuredGet = measuredGetMethod,
            firstBaseline = firstBaseline,
            toPx = toPxMethod,
            stateGetValue = stateGetValueMethod,
            function1Invoke = function1InvokeMethod,
            layoutDefault = layoutDefaultMethod,
            placeRelative = placeRelativeMethod,
            updateState = updateStateMethod,
            offsetBox = offsetBoxMethod,
            placeableWidth = placeableWidthField,
            placeableHeight = placeableHeightField,
            unitInstance = unitInstance,
        )
    }

    // ================= measure-3p2s80s 复刻 =================

    /**
     * 与原 measure 逐指令对照的复刻（对照 dexdump 字节码）。
     * 唯一改动点以「★改动」标明：placement 里两个子件的摆放 x
     * 整体加 offset；其余测量/基线/y 公式/回调/返回尺寸与原版一致。
     */
    private fun measureCentered(
        api: PolicyApi,
        self: Any,
        measureScope: Any?,
        measurables: Any?,
        constraints: Long,
        log: (String) -> Unit,
    ): Any? {
        val scope = measureScope ?: throw NullPointerException("measureScope == null")
        val list = measurables as? List<*>
            ?: throw IllegalStateException("measurables is not a List")
        val maxWidth = api.constraintsMaxWidth.invoke(null, constraints) as Int
        val maxHeight = api.constraintsMaxHeight.invoke(null, constraints) as Int
        val minHeight = api.constraintsMinHeight.invoke(null, constraints) as Int
        val fixedHeight = maxHeight == minHeight
        if (list.size != 2) {
            // 原版在此走 Check failed 抛错；此处抛 IllegalStateException，
            // 由安装层兜底回退原方法，行为等价。
            throw IllegalStateException("DateAndTime measurable count ${list.size} != 2")
        }
        val clockMeasurable = list[0] ?: throw NullPointerException("clock measurable == null")
        val dateMeasurable = list[1] ?: throw NullPointerException("date measurable == null")

        // ---- 时钟：以 (maxW, maxH) 松散约束测量（mask 5，与原版一致） ----
        val clockConstraints = api.constraintsDefault.invoke(null, maxWidth, maxHeight, 5) as Long
        val clockPlaceable = api.measureMeasurable.invoke(clockMeasurable, clockConstraints)
            ?: throw NullPointerException("clock measure returned null")
        val clockBaseline = api.measuredGet.invoke(clockPlaceable, api.firstBaseline) as Int
        val clockWidth = api.placeableWidth.getInt(clockPlaceable)
        val clockHeight = api.placeableHeight.getInt(clockPlaceable)

        // ---- 日期：以剩余宽测量（剩余量 clamp 到 ≥0，与原版一致） ----
        var remaining = maxWidth - clockWidth
        if (remaining < 0) remaining = 0
        val dateConstraints = api.constraintsDefault.invoke(null, remaining, maxHeight, 5) as Long
        val datePlaceable = api.measureMeasurable.invoke(dateMeasurable, dateConstraints)
            ?: throw NullPointerException("date measure returned null")
        val dateWidth = api.placeableWidth.getInt(datePlaceable)
        val dateHeight = api.placeableHeight.getInt(datePlaceable)

        // ---- 返回尺寸：与原版一致（宽 = 两子宽之和，不随居中改变） ----
        val layoutWidth = clockWidth + dateWidth
        val layoutHeight = if (fixedHeight) maxHeight else clockHeight

        // ---- clockY / dateY：两分支公式与原版逐项一致 ----
        val dateBaseline = readIntState(api, api.fieldDateBaseline.get(self))
        val clockY: Int
        val dateY: Int
        if (fixedHeight) {
            val dateDesc = readIntState(api, api.fieldDateDesc.get(self))
            val isTibetan = api.fieldIsTibetan.getBoolean(self)
            val tibetanExtra = if (isTibetan) (dateHeight * 0.1f).toInt() else 0
            val t = (api.toPx.invoke(scope, 16.0f) as Float).toInt() - dateDesc + tibetanExtra
            dateY = maxHeight - dateHeight - t
            clockY = dateBaseline + dateY - clockBaseline
        } else {
            clockY = (api.toPx.invoke(scope, 1.0f) as Float).toInt()
            dateY = clockBaseline - dateBaseline
        }

        // ---- onDateTranslationY 回调：与原版一致（传 dateY） ----
        val onDateTranslationY = api.fieldOnDateTranslationY.get(self)
        if (onDateTranslationY != null) {
            api.function1Invoke.invoke(onDateTranslationY, dateY)
        }

        // ★改动：居中偏移 = (容器宽 − 内容总宽) / 2，clamp ≥ 0。
        // 容器宽 = 约束 maxWidth；内容总宽 = 时钟宽 + 日期宽
        // （原版日期 x = 时钟宽，故内容右缘 = 时钟宽 + 日期宽）。
        val contentWidth = clockWidth + dateWidth
        var offset = (maxWidth - contentWidth) / 2
        if (offset < 0) offset = 0

        // 有界诊断：每进程前 5 次记一行。
        val diagN = policyDiagLogCount.incrementAndGet()
        if (diagN <= 5) {
            try {
                log(
                    "policy diag #$diagN: box=${layoutWidth}x${layoutHeight} " +
                        "clock=${clockWidth}x${clockHeight} " +
                        "date=${dateWidth}x${dateHeight} offset=$offset"
                )
            } catch (t: Throwable) {
                // 诊断失败不影响测量
            }
        }

        // ---- placement：自备宿主 Function1 代理（见类注释） ----
        val timePositionFlow = api.fieldTimePosition.get(self)
            ?: throw NullPointerException("timePositionInParent == null")
        val handler = PolicyPlacementHandler(
            api = api,
            datePlaceable = datePlaceable,
            clockPlaceable = clockPlaceable,
            clockWidth = clockWidth,
            dateY = dateY,
            clockY = clockY,
            offset = offset,
            timePositionFlow = timePositionFlow,
            log = log,
        )
        val placement = Proxy.newProxyInstance(
            api.hostClassLoader, arrayOf(api.function1Class), handler
        )
        return api.layoutDefault.invoke(null, scope, layoutWidth, layoutHeight, placement)
    }

    /** 读 MutableState<Int> 的当前值（原版经 State.getValue + Number.intValue）。 */
    private fun readIntState(api: PolicyApi, state: Any?): Int {
        val value = api.stateGetValue.invoke(state)
            ?: throw NullPointerException("baseline/desc state value == null")
        return (value as Number).toInt()
    }
}

/** 安装时一次性解析的全部反射句柄。 */
private class PolicyApi(
    val policyClass: Class<*>,
    val hostClassLoader: ClassLoader,
    val measureScopeClass: Class<*>,
    val function1Class: Class<*>,
    val fieldDateBaseline: Field,
    val fieldDateDesc: Field,
    val fieldIsTibetan: Field,
    val fieldOnDateTranslationY: Field,
    val fieldTimePosition: Field,
    val constraintsMaxWidth: Method,
    val constraintsMaxHeight: Method,
    val constraintsMinHeight: Method,
    val constraintsDefault: Method,
    val measureMeasurable: Method,
    val measuredGet: Method,
    val firstBaseline: Any,
    val toPx: Method,
    val stateGetValue: Method,
    val function1Invoke: Method,
    val layoutDefault: Method,
    val placeRelative: Method,
    val updateState: Method,
    val offsetBox: Method,
    val placeableWidth: Field,
    val placeableHeight: Field,
    val unitInstance: Any,
)

/**
 * 自备 placement：复刻原 placement lambda
 * （NotifCenterHeaderKt$DateAndTime$2$1$$ExternalSyntheticLambda0）
 * 的 invoke，唯一改动是两个 placeRelative 的 x 加 offset：
 * 日期 (时钟宽 + offset, dateY)、时钟 (offset, clockY)；
 * timePositionInParent 回写与原版逐字一致（Offset(0f, clockY)）。
 * 经 Proxy 以宿主 Function1 身份交给 MeasureScope.layout$default。
 * 摆放异常时先退回原版坐标再摆一次，仍失败则吞掉——布局阶段
 * 的异常绝不冒泡进 SystemUI。
 */
private class PolicyPlacementHandler(
    private val api: PolicyApi,
    private val datePlaceable: Any,
    private val clockPlaceable: Any,
    private val clockWidth: Int,
    private val dateY: Int,
    private val clockY: Int,
    private val offset: Int,
    private val timePositionFlow: Any,
    private val log: (String) -> Unit,
) : InvocationHandler {

    override fun invoke(proxy: Any?, method: Method?, args: Array<Any?>?): Any? {
        if (method == null || method.name != "invoke") {
            return when (method?.name) {
                "toString" -> "PolicyCenteringPlacement"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.getOrNull(0)
                else -> api.unitInstance
            }
        }
        val scope = args?.getOrNull(0)
            ?: throw NullPointerException("placementScope == null")
        try {
            api.placeRelative.invoke(null, scope, datePlaceable, clockWidth + offset, dateY)
            api.placeRelative.invoke(null, scope, clockPlaceable, offset, clockY)
        } catch (t: Throwable) {
            val n = placementFallbackLogCount.incrementAndGet()
            if (n <= 3) {
                log("policy placement failed (#$n), retrying original positions: ${describeThrowable(t)}")
            }
            try {
                api.placeRelative.invoke(null, scope, datePlaceable, clockWidth, dateY)
                api.placeRelative.invoke(null, scope, clockPlaceable, 0, clockY)
            } catch (t2: Throwable) {
                // 摆放彻底失败也不让异常冒泡进宿主布局
            }
        }
        try {
            val packed = (0.0f.toRawBits().toLong() shl 32) or
                (clockY.toFloat().toRawBits().toLong() and 0xFFFF_FFFFL)
            val boxed = api.offsetBox.invoke(null, packed)
            api.updateState.invoke(timePositionFlow, null, boxed)
        } catch (t: Throwable) {
            val n = placementFallbackLogCount.incrementAndGet()
            if (n <= 3) {
                log("policy timePosition write failed (#$n): ${describeThrowable(t)}")
            }
        }
        return api.unitInstance
    }
}

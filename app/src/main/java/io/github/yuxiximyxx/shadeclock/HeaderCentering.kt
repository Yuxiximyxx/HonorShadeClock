package io.github.yuxiximyxx.shadeclock

import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger

/**
 * 居中：替换 NotifCenterHeaderKt$$ExternalSyntheticLambda9.invoke 的复刻实现。
 *
 * 复刻蓝本（逐行对照过反编译源码，见仓库 HEADER_ANALYSIS.md §2.1 与
 * ~/workspace/systemui-decompile/src/ 下 Lambda7/8/9/11/12 源码）：
 * 原 invoke 是头部 SubcomposeLayout 的摆放逻辑，依次 subcompose/measure
 * mgeBtn、trafficUi（可见时）、dateAndTime，再计算三者的摆放 y 与铃铛 x，
 * 并回写两个状态：timePositionInParent（f$9，Offset）与铃铛位置
 * IntSize State（f$17）。原版 dateAndTime 的摆放 x 写死 0。
 *
 * 本复刻与原方法逐项一致，唯一改动：dateAndTime 的摆放 x 改为
 * (总宽 - 铃铛宽 - dateAndTime 宽) / 2，并 clamp 到 [0, 总宽 - dateAndTime 宽]。
 * timePositionInParent 的回写值与原版逐字一致（x = density * f$10，
 * y = dateY），不随居中改动——这是分析文件的既定口径。
 *
 * 保险丝：
 * 1. 安装前结构自检：目标类、invoke 签名、三个内容 lambda 的字段
 *    数量/类型、全部 Compose 内部 API 的反射句柄逐项核对；任一不符
 *    抛 IllegalStateException，install 记录原因后整体跳过居中 hook。
 * 2. 运行时兜底：复刻逻辑全程 try-catch，任何异常立即回退调用原方法，
 *    异常细节只记前 3 次，避免逐帧刷屏；绝不让异常冒泡进 SystemUI。
 * 3. 热路径无反射查找：全部 Field/Method/Constructor 在安装时解析一次。
 *
 * 全部调用走反射，是为了不给模块引入与宿主可能漂移的 Compose 编译期依赖；
 * 方法名（如 measure-BRTryo0、copy-Zbe2FdA$default）均为宿主 dex 中的
 * 真实 JVM 名，已用 jadx 对 17.0.0.1 的 APK 逐个核对。
 */
object HeaderCentering {

    private const val VIEW_MODEL_NAME =
        "com.android.systemui.statusbar.notification.panelheader.viewmodel.NotifCenterHeaderViewModel"
    private const val TRAFFIC_STATE_NAME =
        "com.android.systemui.statusbar.notification.panelheader.viewmodel.TrafficState"
    private const val MUTABLE_STATE_NAME = "androidx.compose.runtime.MutableState"
    private const val SNAPSHOT_INT_STATE_NAME =
        "androidx.compose.runtime.SnapshotMutableIntStateImpl"
    private const val MUTABLE_STATE_FLOW_NAME = "kotlinx.coroutines.flow.MutableStateFlow"
    private const val FUNCTION1_NAME = "kotlin.jvm.functions.Function1"

    private const val LAMBDA9_NAME =
        "com.android.systemui.statusbar.notification.panelheader.compose.NotifCenterHeaderKt\$\$ExternalSyntheticLambda9"
    private const val LAMBDA8_NAME =
        "com.android.systemui.statusbar.notification.panelheader.compose.NotifCenterHeaderKt\$\$ExternalSyntheticLambda8"
    private const val LAMBDA11_NAME =
        "com.android.systemui.statusbar.notification.panelheader.compose.NotifCenterHeaderKt\$\$ExternalSyntheticLambda11"
    private const val LAMBDA12_NAME =
        "com.android.systemui.statusbar.notification.panelheader.compose.NotifCenterHeaderKt\$\$ExternalSyntheticLambda12"

    /** Lambda9 的 30 个捕获字段期望类型（下标即字段序号）；f$1 允许装箱/未装箱两种表示。 */
    private val LAMBDA9_FIELD_TYPES: List<Set<String>> = listOf(
        setOf("androidx.compose.ui.layout.SubcomposeMeasureScope"), // f$0
        setOf("long", "androidx.compose.ui.unit.Constraints"), // f$1
        setOf("boolean"), // f$2  traffic 可见
        setOf("float"), // f$3
        setOf(SNAPSHOT_INT_STATE_NAME), // f$4
        setOf("boolean"), // f$5
        setOf("boolean"), // f$6
        setOf("int"), // f$7  容器总高(px)
        setOf("float"), // f$8
        setOf(MUTABLE_STATE_FLOW_NAME), // f$9  timePositionInParent
        setOf("float"), // f$10 起始边距(dp)
        setOf(SNAPSHOT_INT_STATE_NAME), // f$11
        setOf(SNAPSHOT_INT_STATE_NAME), // f$12
        setOf(SNAPSHOT_INT_STATE_NAME), // f$13
        setOf("float"), // f$14
        setOf("float"), // f$15
        setOf("float"), // f$16
        setOf(MUTABLE_STATE_NAME), // f$17 铃铛位置 IntSize State
        setOf(VIEW_MODEL_NAME), // f$18
        setOf(TRAFFIC_STATE_NAME), // f$19
        setOf("long"), // f$20 颜色
        setOf("float"), // f$21
        setOf(MUTABLE_STATE_NAME), // f$22
        setOf(MUTABLE_STATE_NAME), // f$23
        setOf(MUTABLE_STATE_NAME), // f$24
        setOf(FUNCTION1_NAME), // f$25
        setOf(MUTABLE_STATE_NAME), // f$26
        setOf(MUTABLE_STATE_FLOW_NAME), // f$27
        setOf(FUNCTION1_NAME), // f$28
        setOf(FUNCTION1_NAME), // f$29
    )

    private val LAMBDA11_FIELD_TYPES: List<Set<String>> = listOf(
        setOf(VIEW_MODEL_NAME), // f$0
        setOf(TRAFFIC_STATE_NAME), // f$1
        setOf("long"), // f$2 颜色
        setOf("float"), // f$3
        setOf(MUTABLE_STATE_NAME), // f$4
        setOf(MUTABLE_STATE_NAME), // f$5
        setOf(MUTABLE_STATE_NAME), // f$6
        setOf(FUNCTION1_NAME), // f$7
    )

    private val LAMBDA12_FIELD_TYPES: List<Set<String>> = listOf(
        setOf("boolean"), // f$0
        setOf("float"), // f$1
        setOf(VIEW_MODEL_NAME), // f$2
        setOf("long"), // f$3 颜色
        setOf(MUTABLE_STATE_NAME), // f$4
        setOf(MUTABLE_STATE_NAME), // f$5
        setOf(MUTABLE_STATE_NAME), // f$6
        setOf(MUTABLE_STATE_FLOW_NAME), // f$7
        setOf(FUNCTION1_NAME), // f$8
        setOf(FUNCTION1_NAME), // f$9
    )

    /** 安装时一次性解析的全部反射句柄。 */
    private class Api(
        val lambda9Class: Class<*>,
        val lambda9Fields: Array<Field>,
        val constraintsValueField: Field?,
        val lambda8Constructor: Constructor<*>,
        val lambda8F0: Field,
        val lambda11Constructor: Constructor<*>,
        val lambda11Fields: Array<Field>,
        val lambda12Constructor: Constructor<*>,
        val lambda12Fields: Array<Field>,
        val composableLambdaConstructor: Constructor<*>,
        val subcomposeMethod: Method,
        val measureMethod: Method,
        val constraintsCopyDefault: Method,
        val constraintsMaxWidth: Method,
        val placeRelativeMethod: Method,
        val getDensityMethod: Method,
        val roundToPxMethod: Method,
        val getIntValueMethod: Method,
        val updateStateMethod: Method,
        val offsetBoxMethod: Method,
        val intSizeBoxMethod: Method,
        val setValueMethod: Method,
        val placeableWidthField: Field,
        val placeableHeightField: Field,
        val unitInstance: Any,
    )

    private val fallbackLogCount = AtomicInteger(0)

    /**
     * 结构自检通过才安装居中 hook；任一不符只记一行原因、整体跳过
     * （放大功能不受影响）。[log] 为诊断输出通道。
     */
    fun install(lpparam: XC_LoadPackage.LoadPackageParam, log: (String) -> Unit) {
        val api = try {
            resolveApi(lpparam.classLoader)
        } catch (t: Throwable) {
            log("centering hook skipped: ${describe(t)}")
            return
        }
        try {
            XposedHelpers.findAndHookMethod(
                api.lambda9Class, "invoke", Any::class.java,
                object : XC_MethodReplacement() {
                    override fun replaceHookedMethod(param: MethodHookParam): Any? {
                        try {
                            return invokeCentered(api, param.thisObject, param.args.getOrNull(0))
                        } catch (t: Throwable) {
                            val n = fallbackLogCount.incrementAndGet()
                            if (n <= 3) {
                                log("centering invoke failed (#$n), falling back to original: ${describe(t)}")
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
            log("centering hook installed: Lambda9.invoke replaced (structure self-check passed)")
        } catch (t: Throwable) {
            log("centering hook install failed: ${describe(t)}")
        }
    }

    private fun describe(t: Throwable): String {
        val cause = if (t is InvocationTargetException && t.cause != null) t.cause!! else t
        return "${cause.javaClass.name}: ${cause.message}"
    }

    // ================= 结构自检与句柄解析 =================

    private fun fail(reason: String): Nothing = throw IllegalStateException(reason)

    private fun resolveApi(cl: ClassLoader): Api {
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

        // ---- 目标类与 invoke 签名 ----
        val lambda9Class = findClass(LAMBDA9_NAME)
        val lambda9Fields = checkedFields(lambda9Class, LAMBDA9_FIELD_TYPES, "Lambda9")
        resolve("Lambda9.invoke signature") {
            val m = lambda9Class.getMethod("invoke", Any::class.java)
            if (m.declaringClass !== lambda9Class) {
                fail("Lambda9.invoke declaring class is ${m.declaringClass.name}")
            }
            if (m.returnType !== Any::class.java) {
                fail("Lambda9.invoke return type is ${m.returnType.name}")
            }
        }

        // ---- f$1 若为装箱 Constraints，需要读其 value 字段 ----
        val constraintsClass = findClass("androidx.compose.ui.unit.Constraints")
        val constraintsValueField: Field? =
            if (lambda9Fields[1].type === java.lang.Long.TYPE) {
                null
            } else {
                resolve("Constraints.value field") {
                    val field = constraintsClass.getDeclaredField("value")
                    if (field.type !== java.lang.Long.TYPE) {
                        fail("Constraints.value type is ${field.type.name}")
                    }
                    field.isAccessible = true
                    field
                }
            }

        // ---- 内容 lambda：Lambda8（铃铛） ----
        val lambda8Class = findClass(LAMBDA8_NAME)
        if (lambda8Class.declaredFields.size != 2) {
            fail("Lambda8 declared field count ${lambda8Class.declaredFields.size} != 2")
        }
        resolve("Lambda8.\$r8\$classId field") {
            val field = lambda8Class.getDeclaredField("\$r8\$classId")
            if (field.type !== Integer.TYPE) {
                fail("Lambda8.\$r8\$classId type is ${field.type.name}")
            }
        }
        val lambda8F0 = resolve("Lambda8.f\$0 field") {
            val field = lambda8Class.getDeclaredField("f\$0")
            if (field.type.name != VIEW_MODEL_NAME) {
                fail("Lambda8.f\$0 type is ${field.type.name}")
            }
            field.isAccessible = true
            field
        }
        val lambda8Constructor = resolve("Lambda8 constructor") {
            lambda8Class.getDeclaredConstructor(Integer.TYPE).apply { isAccessible = true }
        }

        // ---- 内容 lambda：Lambda11（流量）/ Lambda12（时钟+日期） ----
        val lambda11Class = findClass(LAMBDA11_NAME)
        val lambda11Fields = checkedFields(lambda11Class, LAMBDA11_FIELD_TYPES, "Lambda11")
        val lambda11Constructor = resolve("Lambda11 constructor") {
            lambda11Class.getDeclaredConstructor().apply { isAccessible = true }
        }
        val lambda12Class = findClass(LAMBDA12_NAME)
        val lambda12Fields = checkedFields(lambda12Class, LAMBDA12_FIELD_TYPES, "Lambda12")
        val lambda12Constructor = resolve("Lambda12 constructor") {
            lambda12Class.getDeclaredConstructor().apply { isAccessible = true }
        }

        // ---- Compose 内部 API 句柄 ----
        val function2Class = findClass("kotlin.jvm.functions.Function2")
        val subcomposeScopeClass = findClass("androidx.compose.ui.layout.SubcomposeMeasureScope")
        val measurableClass = findClass("androidx.compose.ui.layout.Measurable")
        val placeableClass = findClass("androidx.compose.ui.layout.Placeable")
        val placementScopeClass = findClass("androidx.compose.ui.layout.Placeable\$PlacementScope")
        val densityClass = findClass("androidx.compose.ui.unit.Density")
        val mutableStateClass = findClass(MUTABLE_STATE_NAME)
        val snapshotIntClass = findClass(SNAPSHOT_INT_STATE_NAME)
        val stateFlowImplClass = findClass("kotlinx.coroutines.flow.StateFlowImpl")
        val offsetClass = findClass("androidx.compose.ui.geometry.Offset")
        val intSizeClass = findClass("androidx.compose.ui.unit.IntSize")
        val composableLambdaClass =
            findClass("androidx.compose.runtime.internal.ComposableLambdaImpl")
        val unitClass = findClass("kotlin.Unit")

        val subcomposeMethod = resolve("SubcomposeMeasureScope.subcompose") {
            val m = subcomposeScopeClass.getMethod("subcompose", Any::class.java, function2Class)
            if (!java.util.List::class.java.isAssignableFrom(m.returnType)) {
                fail("subcompose return type is ${m.returnType.name}")
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
        val copyDefaultMethod = resolve("Constraints.copy-Zbe2FdA\$default") {
            val m = constraintsClass.getMethod(
                "copy-Zbe2FdA\$default",
                java.lang.Long.TYPE,
                Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE
            )
            if (!Modifier.isStatic(m.modifiers) || m.returnType !== java.lang.Long.TYPE) {
                fail("copy-Zbe2FdA\$default is not static long")
            }
            m
        }
        val maxWidthMethod = resolve("Constraints.getMaxWidth-impl") {
            val m = constraintsClass.getMethod("getMaxWidth-impl", java.lang.Long.TYPE)
            if (!Modifier.isStatic(m.modifiers) || m.returnType !== Integer.TYPE) {
                fail("getMaxWidth-impl is not static int")
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
        val getDensityMethod = resolve("Density.getDensity") {
            val m = densityClass.getMethod("getDensity")
            if (m.returnType !== java.lang.Float.TYPE) {
                fail("getDensity return type is ${m.returnType.name}")
            }
            m
        }
        val roundToPxMethod = resolve("Density.roundToPx-0680j_4") {
            try {
                val m = densityClass.getMethod("roundToPx-0680j_4", java.lang.Float.TYPE)
                if (m.returnType !== Integer.TYPE) {
                    fail("roundToPx-0680j_4 return type is ${m.returnType.name}")
                }
                m
            } catch (e: NoSuchMethodException) {
                // 兜底：在 PlacementScope 类层级里找同名声明
                var cls: Class<*>? = placementScopeClass
                var found: Method? = null
                while (cls != null && found == null) {
                    found = try {
                        cls.getDeclaredMethod("roundToPx-0680j_4", java.lang.Float.TYPE)
                    } catch (t: Throwable) {
                        null
                    }
                    cls = cls.superclass
                }
                found ?: throw e
            }
        }
        val getIntValueMethod = resolve("SnapshotMutableIntStateImpl.getIntValue") {
            val m = snapshotIntClass.getMethod("getIntValue")
            if (m.returnType !== Integer.TYPE) {
                fail("getIntValue return type is ${m.returnType.name}")
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
        val intSizeBoxMethod = resolve("IntSize.box-impl") {
            val m = intSizeClass.getMethod("box-impl", java.lang.Long.TYPE)
            if (!Modifier.isStatic(m.modifiers)) fail("IntSize.box-impl is not static")
            m
        }
        val setValueMethod = resolve("MutableState.setValue") {
            mutableStateClass.getMethod("setValue", Any::class.java)
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
        val composableLambdaConstructor = resolve("ComposableLambdaImpl constructor") {
            composableLambdaClass.getDeclaredConstructor(
                Integer.TYPE, java.lang.Boolean.TYPE, Any::class.java
            ).apply { isAccessible = true }
        }
        val unitInstance = resolve("kotlin.Unit.INSTANCE") {
            unitClass.getField("INSTANCE").get(null) ?: fail("kotlin.Unit.INSTANCE is null")
        }

        return Api(
            lambda9Class = lambda9Class,
            lambda9Fields = lambda9Fields,
            constraintsValueField = constraintsValueField,
            lambda8Constructor = lambda8Constructor,
            lambda8F0 = lambda8F0,
            lambda11Constructor = lambda11Constructor,
            lambda11Fields = lambda11Fields,
            lambda12Constructor = lambda12Constructor,
            lambda12Fields = lambda12Fields,
            composableLambdaConstructor = composableLambdaConstructor,
            subcomposeMethod = subcomposeMethod,
            measureMethod = measureMethod,
            constraintsCopyDefault = copyDefaultMethod,
            constraintsMaxWidth = maxWidthMethod,
            placeRelativeMethod = placeRelativeMethod,
            getDensityMethod = getDensityMethod,
            roundToPxMethod = roundToPxMethod,
            getIntValueMethod = getIntValueMethod,
            updateStateMethod = updateStateMethod,
            offsetBoxMethod = offsetBoxMethod,
            intSizeBoxMethod = intSizeBoxMethod,
            setValueMethod = setValueMethod,
            placeableWidthField = placeableWidthField,
            placeableHeightField = placeableHeightField,
            unitInstance = unitInstance,
        )
    }

    /**
     * 核对一个 synthetic lambda 类的捕获字段：声明字段总数须与期望一致，
     * 且 f$0..f$n-1 逐个存在、类型命中期望集合。返回已开访问权的字段数组。
     */
    private fun checkedFields(
        clazz: Class<*>,
        expected: List<Set<String>>,
        label: String,
    ): Array<Field> {
        if (clazz.declaredFields.size != expected.size) {
            fail("$label declared field count ${clazz.declaredFields.size} != expected ${expected.size}")
        }
        return Array(expected.size) { i ->
            val field = try {
                clazz.getDeclaredField("f\$" + i)
            } catch (t: Throwable) {
                fail("$label missing field f\$$i")
            }
            val typeName = field.type.name
            if (typeName !in expected[i]) {
                fail("$label field f\$$i type $typeName not in ${expected[i]}")
            }
            field.isAccessible = true
            field
        }
    }

    // ================= Lambda9.invoke 复刻 =================

    private fun firstMeasurable(subcomposeResult: Any?): Any {
        val list = subcomposeResult as? List<*>
            ?: throw IllegalStateException("subcompose did not return a List")
        if (list.isEmpty()) throw NoSuchElementException("subcompose returned empty list")
        return list[0] ?: throw NullPointerException("subcompose returned null first element")
    }

    private fun roundToPx(api: Api, scope: Any, dp: Float): Int =
        api.roundToPxMethod.invoke(scope, dp) as Int

    private fun getIntValue(api: Api, state: Any?): Int =
        api.getIntValueMethod.invoke(state) as Int

    /**
     * 与原 invoke 逐项对照的复刻（变量注释标注原字段号）。
     * 唯一改动点以「★改动」标明，其余 subcompose/measure/y 计算/状态回写
     * 与原方法一致。
     */
    private fun invokeCentered(api: Api, self: Any, placementScope: Any?): Any? {
        // 原版首行对 placementScope 做 Intrinsics 空检查；此处以显式 NPE 等价复刻，
        // 异常会被上层兜底捕获并回退原方法。
        val scope = placementScope ?: throw NullPointerException("placementScope == null")
        val f = api.lambda9Fields

        val measureScope = f[0].get(self)
        val constraintsRaw = f[1].get(self)
        val j3: Long = when {
            constraintsRaw is Number -> constraintsRaw.toLong()
            api.constraintsValueField != null && constraintsRaw != null ->
                api.constraintsValueField.getLong(constraintsRaw)
            else -> throw IllegalStateException(
                "unexpected f\$1 representation: ${constraintsRaw?.javaClass?.name}"
            )
        }
        val z = f[2].getBoolean(self) // traffic 可见
        val fTraffic = f[3].getFloat(self) // 原局部 f（流量 y 计算的 roundToPx 入参）
        val snap1 = f[4].get(self)
        val z2 = f[5].getBoolean(self)
        val z3 = f[6].getBoolean(self)
        val i3 = f[7].getInt(self) // 容器总高(px)
        val f2 = f[8].getFloat(self)
        val timePosFlow = f[9].get(self) // timePositionInParent
        val fMargin = f[10].getFloat(self) // 原局部 f3：起始边距(dp)
        val snap2 = f[11].get(self)
        val snap3 = f[12].get(self)
        val snap4 = f[13].get(self)
        val f4 = f[14].getFloat(self)
        val f5 = f[15].getFloat(self)
        val f6 = f[16].getFloat(self)
        val intSizeState = f[17].get(self) // 铃铛位置 IntSize State
        val viewModel = f[18].get(self)
        val trafficState = f[19].get(self)
        val color = f[20].getLong(self)
        val f7 = f[21].getFloat(self)
        val ms2 = f[22].get(self)
        val ms3 = f[23].get(self)
        val ms4 = f[24].get(self)
        val fn1 = f[25].get(self)
        val ms5 = f[26].get(self)
        val flow2 = f[27].get(self)
        val fn2 = f[28].get(self)
        val fn3 = f[29].get(self)

        // ---- mgeBtn：subcompose + 自由约束测量（copy mask=10，与原版一致） ----
        val lambda8 = api.lambda8Constructor.newInstance(1)
        api.lambda8F0.set(lambda8, viewModel)
        val btnContent = api.composableLambdaConstructor.newInstance(1978395573, true, lambda8)
        val btnMeasurable = firstMeasurable(
            api.subcomposeMethod.invoke(measureScope, "mgeBtn", btnContent)
        )
        val btnConstraints = api.constraintsCopyDefault.invoke(null, j3, 0, 0, 0, 0, 10) as Long
        val btnPlaceable = api.measureMethod.invoke(btnMeasurable, btnConstraints)
        val btnWidth = api.placeableWidthField.getInt(btnPlaceable)
        val btnHeight = api.placeableHeightField.getInt(btnPlaceable)
        val maxWidth = api.constraintsMaxWidth.invoke(null, j3) as Int

        // ---- trafficUi：可见时 subcompose/measure 并摆放（x=0、y 与原版一致） ----
        var trafficTop = 0 // 原局部 i：流量行顶部 y；不可见时为 0
        if (z) {
            val lambda11 = api.lambda11Constructor.newInstance()
            val lf = api.lambda11Fields
            lf[0].set(lambda11, viewModel)
            lf[1].set(lambda11, trafficState)
            lf[2].setLong(lambda11, color)
            lf[3].setFloat(lambda11, f7)
            lf[4].set(lambda11, ms2)
            lf[5].set(lambda11, ms3)
            lf[6].set(lambda11, ms4)
            lf[7].set(lambda11, fn1)
            val trafficContent =
                api.composableLambdaConstructor.newInstance(-305758715, true, lambda11)
            val trafficMeasurable = firstMeasurable(
                api.subcomposeMethod.invoke(measureScope, "trafficUi", trafficContent)
            )
            val trafficConstraints = api.constraintsCopyDefault.invoke(
                null, j3, 0, maxWidth - btnWidth, 0, 0, 8
            ) as Long
            val trafficPlaceable = api.measureMethod.invoke(trafficMeasurable, trafficConstraints)
            val trafficHeight = api.placeableHeightField.getInt(trafficPlaceable)
            val t = roundToPx(api, scope, fTraffic) - getIntValue(api, snap1)
            val extra = if (z2) Math.round(trafficHeight * (if (z3) 0.1f else 0.2f)) else 0
            trafficTop = ((i3 - trafficHeight) - t) - extra
            api.placeRelativeMethod.invoke(null, scope, trafficPlaceable, 0, trafficTop)
        }

        // ---- dateAndTime：subcompose/measure（copy mask=8，与原版一致） ----
        val lambda12 = api.lambda12Constructor.newInstance()
        val df = api.lambda12Fields
        df[0].setBoolean(lambda12, z)
        df[1].setFloat(lambda12, fMargin)
        df[2].set(lambda12, viewModel)
        df[3].setLong(lambda12, color)
        df[4].set(lambda12, ms5)
        df[5].set(lambda12, ms3)
        df[6].set(lambda12, ms4)
        df[7].set(lambda12, flow2)
        df[8].set(lambda12, fn2)
        df[9].set(lambda12, fn3)
        val dateContent = api.composableLambdaConstructor.newInstance(-431306260, true, lambda12)
        val dateMeasurable = firstMeasurable(
            api.subcomposeMethod.invoke(measureScope, "dateAndTime", dateContent)
        )
        val dateConstraints = api.constraintsCopyDefault.invoke(
            null, j3, 0, maxWidth - btnWidth, 0, 0, 8
        ) as Long
        val datePlaceable = api.measureMethod.invoke(dateMeasurable, dateConstraints)
        val dateWidth = api.placeableWidthField.getInt(datePlaceable)
        val dateHeight = api.placeableHeightField.getInt(datePlaceable)
        val density = api.getDensityMethod.invoke(scope) as Float
        // 原版：(int)(density * f2) 为 Java 强转（截断），Kotlin Float.toInt() 同义
        val dateY = if (trafficTop == 0) {
            (i3 - dateHeight) - (density * f2).toInt()
        } else {
            (trafficTop - dateHeight) - (density * f2).toInt()
        }
        // ★改动：摆放 x 由写死 0 改为居中 (总宽 - 铃铛宽 - 容器宽)/2，并 clamp 到 [0, 总宽 - 容器宽]
        var centeredX = (maxWidth - btnWidth - dateWidth) / 2
        val maxCenteredX = maxWidth - dateWidth
        centeredX = when {
            maxCenteredX < 0 -> 0
            centeredX < 0 -> 0
            centeredX > maxCenteredX -> maxCenteredX
            else -> centeredX
        }
        api.placeRelativeMethod.invoke(null, scope, datePlaceable, centeredX, dateY)
        // timePositionInParent 回写：与原版逐字一致（x = density * f$10，y = dateY 的 float 位模式）
        val offsetPacked = ((density * fMargin).toRawBits().toLong() shl 32) or
            (dateY.toFloat().toRawBits().toLong() and 0xFFFF_FFFFL)
        val offsetBoxed = api.offsetBoxMethod.invoke(null, offsetPacked)
        api.updateStateMethod.invoke(timePosFlow, null, offsetBoxed)

        // ---- mgeBtn 摆放：y 计算分支与 IntSize 回写与原版一致 ----
        val btnY: Int = if (z && z3) {
            (i3 / 2) - (btnHeight / 2)
        } else if (z) {
            val a = (getIntValue(api, snap4) +
                (getIntValue(api, snap2) + (getIntValue(api, snap3) + dateY))) -
                (if (z2) (getIntValue(api, snap2) * 0.8f).toInt() else 0)
            val b = btnHeight - roundToPx(api, scope, f4)
            a - b
        } else {
            val a = roundToPx(api, scope, f5) - btnHeight
            val b = roundToPx(api, scope, f6)
            a - b
        }
        val btnX = maxWidth - btnWidth
        val intSizePacked = (btnX.toLong() shl 32) or (btnY.toLong() and 0xFFFF_FFFFL)
        val intSizeBoxed = api.intSizeBoxMethod.invoke(null, intSizePacked)
        api.setValueMethod.invoke(intSizeState, intSizeBoxed)
        api.placeRelativeMethod.invoke(null, scope, btnPlaceable, btnX, btnY)

        return api.unitInstance
    }
}

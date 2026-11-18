package io.github.yuxiximyxx.shadeclock.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import java.io.File
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** 一次绘制的环境量：当前时间、电量、真实天气、屏幕尺寸。 */
class RenderEnv(
    val now: Calendar,
    val batteryLevel: Int,
    /** 荣耀引擎口径：1=充电中，3=已充满，其余=0。 */
    val batteryState: Int,
    val weather: Map<String, EVal>,
    /** 覆盖层宽度 px（= #screen_width）。 */
    val screenWidthPx: Int,
    /** 设备屏幕高度 px（= #screen_height，取面板所在窗口高度）。 */
    val screenHeightPx: Int,
)

/**
 * 变量解析上下文（每次绘制新建）：内建变量 → Weather.* 真实值 →
 * 主题 Var/VarArray（懒求值 + 本轮缓存）→ 未知一律 0。
 * month 按引擎惯例 0 起（VarArray 月份表靠它索引）；day_of_week 为
 * 周一=1…周日=7（两份样本的星期图集实测：x_1=周一、x_7=周日）。
 */
class EvalContext(
    private val model: ThemeModel,
    env: RenderEnv,
) : VarResolver {

    private val builtins: Map<String, EVal>
    private val weather: Map<String, EVal> = env.weather
    private val cache = HashMap<String, EVal>()
    private val resolving = HashSet<String>()

    /** Weather.* 无真实值被引用过的次数（Text 据此整段留空）。 */
    var missingWeatherTouches = 0
        private set

    init {
        val cal = env.now
        val isoDow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
        builtins = mapOf(
            "hour24" to EVal.Num(cal.get(Calendar.HOUR_OF_DAY).toDouble()),
            "hour" to EVal.Num(cal.get(Calendar.HOUR).toDouble()),
            "minute" to EVal.Num(cal.get(Calendar.MINUTE).toDouble()),
            "second" to EVal.Num(cal.get(Calendar.SECOND).toDouble()),
            "year" to EVal.Num(cal.get(Calendar.YEAR).toDouble()),
            "month" to EVal.Num(cal.get(Calendar.MONTH).toDouble()),
            "date" to EVal.Num(cal.get(Calendar.DAY_OF_MONTH).toDouble()),
            "day_of_week" to EVal.Num(isoDow.toDouble()),
            "battery_level" to EVal.Num(env.batteryLevel.toDouble()),
            "battery_state" to EVal.Num(env.batteryState.toDouble()),
            "screen_width" to EVal.Num(env.screenWidthPx.toDouble()),
            "screen_height" to EVal.Num(env.screenHeightPx.toDouble()),
            "lunar_calendar" to EVal.Str(""),
        )
    }

    override fun resolve(name: String): EVal {
        builtins[name]?.let { return it }
        if (name.startsWith("Weather.")) {
            weather[name]?.let { return it }
            missingWeatherTouches++
            return EVal.Missing
        }
        cache[name]?.let { return it }
        if (!resolving.add(name)) return EVal.Num(0.0)
        val value = compute(name)
        resolving.remove(name)
        cache[name] = value
        return value
    }

    private fun compute(name: String): EVal {
        for (arr in model.varArrays) {
            if (arr.varName == name) {
                val idx = Expr.eval(arr.indexExpr, this).asNum().toInt()
                val item = arr.items.getOrNull(idx) ?: return EVal.Num(0.0)
                return if (arr.numeric) {
                    EVal.Num(item.toDoubleOrNull() ?: 0.0)
                } else {
                    EVal.Str(item)
                }
            }
        }
        return when (val def = model.vars[name]) {
            is VarDef.ExprDef -> Expr.eval(def.expression, this)
            is VarDef.AnimDef -> EVal.Num(def.finalValue)
            null -> EVal.Num(0.0)
        }
    }
}

/**
 * 数据驱动的锁屏主题渲染器：运行时解析选中主题的 manifest.xml，
 * 按原始坐标 1:1 绘制（1 设计单位 = 1px，不缩放）。
 * 子集：Group/Image/Text/DateTime/Time + Var/VarArray/VariableAnimation
 * （动画一律取终态静态渲染）。缺图跳过并记一行日志，未知变量容错为 0。
 */
class ThemeRenderer(private val log: (String) -> Unit) {

    private var themeDir: File? = null
    private var model: ThemeModel? = null
    private var typeface: Typeface? = null
    private val bitmaps = HashMap<String, Bitmap?>()
    private val missingLogged = HashSet<String>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 最近一次绘制的内容最下缘（根坐标 px），「跟随内容」高度用。 */
    var contentBottomPx: Float = 0f
        private set

    /** 最近一次 draw 实际绘制的可见元素数（覆盖层诊断用）。 */
    var lastDrawnElements: Int = 0
        private set
    private var drawnCounter = 0

    /**
     * 是否绘制主题的整幅背景图（设置页「主题背景图」开关，v2.0.3）。
     * false 时：文档顺序里第一个 dst 覆盖画布 ≥95% 的 Image 视为
     * 背景、跳过不画（云镜的蓝天整图 xr.png 即此），其余元素照常；
     * 通知栏透出原背景，不再是一块硬边矩形。true 时与 v2.0.2 一致。
     * 由 HeaderOverlay 按 stamp 在每次挂载时赋值。
     */
    @Volatile
    var showBackgroundImage: Boolean = true
    private var backgroundHandled = false
    private var backgroundSkipLogged = false

    fun load(dir: File): Boolean {
        themeDir = dir
        bitmaps.clear()
        missingLogged.clear()
        model = try {
            ThemeParser.load(dir)
        } catch (t: Throwable) {
            log("theme parse failed in $dir: ${t.javaClass.name}: ${t.message}")
            null
        }
        typeface = loadTypeface(dir)
        backgroundSkipLogged = false
        if (model == null) log("theme load failed: no valid manifest.xml in $dir")
        return model != null
    }

    val isLoaded: Boolean get() = model != null

    private fun loadTypeface(dir: File): Typeface? {
        val fontsDir = File(dir, "fonts")
        val fontFile = fontsDir.listFiles()
            ?.firstOrNull { it.isFile && (it.name.endsWith(".ttf", true) || it.name.endsWith(".otf", true)) }
            ?: return null
        return try {
            Typeface.createFromFile(fontFile)
        } catch (t: Throwable) {
            log("theme font load failed: ${fontFile.name}: ${t.message}")
            null
        }
    }

    /** 用一块空 Canvas 跑一遍量测，得到内容下缘（不真正绘制）。 */
    fun measureContentBottom(env: RenderEnv): Float {
        draw(Canvas(), env)
        return contentBottomPx
    }

    fun draw(canvas: Canvas, env: RenderEnv) {
        val m = model ?: return
        val ctx = EvalContext(m, env)
        contentBottomPx = 0f
        drawnCounter = 0
        backgroundHandled = false
        for (root in m.roots) {
            drawNode(root, canvas, ctx, env, 0f, 0f, 1f, 1f)
        }
        lastDrawnElements = drawnCounter
    }

    // ---------- 递归绘制 ----------

    private fun drawNode(
        node: XNode,
        canvas: Canvas,
        ctx: EvalContext,
        env: RenderEnv,
        ox: Float,
        oy: Float,
        scaleAcc: Float,
        alphaAcc: Float,
    ) {
        val visExpr = node.attr("visibility")
        if (visExpr != null) {
            val v = Expr.eval(visExpr, ctx)
            if (!v.truthy()) return
        }
        var alpha = alphaAcc
        val alphaExpr = node.attr("alpha")
        if (alphaExpr != null) {
            alpha = (alpha * alphaFactor(Expr.eval(alphaExpr, ctx))).coerceIn(0f, 1f)
            if (alpha <= 0f) return
        }
        if (node.tag in VISUAL_TAGS) drawnCounter++
        when (node.tag) {
            "Group" -> {
                val dx = numAttr(node, "x", ctx)
                val dy = numAttr(node, "y", ctx)
                var groupScale = 1f
                val scaleExpr = node.attr("scale")
                if (scaleExpr != null) {
                    groupScale = Expr.eval(scaleExpr, ctx).asNum().toFloat()
                    if (groupScale == 0f) return
                }
                val nx = ox + dx * scaleAcc
                val ny = oy + dy * scaleAcc
                val ns = scaleAcc * groupScale
                for (child in node.children) {
                    if (child.tag in VISUAL_TAGS) {
                        drawNode(child, canvas, ctx, env, nx, ny, ns, alpha)
                    }
                }
            }
            "Image" -> drawImage(node, canvas, ctx, env, ox, oy, scaleAcc, alpha)
            "Text" -> drawText(node, canvas, ctx, env, ox, oy, scaleAcc, alpha)
            "DateTime" -> {
                val format = node.attr("format") ?: "yyyy-MM-dd"
                val content = formatDate(format, env.now)
                drawTextLike(node, canvas, ctx, env, ox, oy, scaleAcc, alpha, content)
            }
            "Time" -> drawTime(node, canvas, ctx, env, ox, oy, scaleAcc, alpha)
        }
    }

    private fun alphaFactor(v: EVal): Float {
        val a = v.asNum()
        val f = if (a <= 1.0) a else a / 255.0
        return f.toFloat().coerceIn(0f, 1f)
    }

    /** 属性数值：纯数字直读，否则走表达式；缺失/Missing 给 default。 */
    private fun numAttr(node: XNode, name: String, ctx: EvalContext, default: Float = 0f): Float {
        val raw = node.attr(name) ?: return default
        raw.toDoubleOrNull()?.let { return it.toFloat() }
        val v = Expr.eval(raw, ctx)
        return if (v === EVal.Missing) default else v.asNum().toFloat()
    }

    private fun hasNumAttr(node: XNode, name: String): Boolean = node.attr(name) != null

    // ---------- 位图 ----------

    private fun bitmap(relPath: String): Bitmap? {
        if (bitmaps.containsKey(relPath)) return bitmaps[relPath]
        val dir = themeDir ?: return null
        val file = File(dir, relPath)
        var bmp: Bitmap? = null
        if (file.isFile) {
            bmp = try {
                BitmapFactory.decodeFile(file.absolutePath)
            } catch (t: Throwable) {
                null
            }
        }
        if (bmp == null && missingLogged.add(relPath)) {
            log("theme image missing or undecodable: $relPath")
        }
        bitmaps[relPath] = bmp
        return bmp
    }

    /** srcid 变体路径：time/time.png + 3 -> time/time_3.png。 */
    private fun variantPath(src: String, id: String): String {
        val dot = src.lastIndexOf('.')
        val slash = src.lastIndexOf('/')
        return if (dot > slash) {
            src.substring(0, dot) + "_" + id + src.substring(dot)
        } else {
            src + "_" + id
        }
    }

    private fun resolveImagePath(node: XNode, ctx: EvalContext): String? {
        var path: String? = null
        val srcExp = node.attr("srcExp")
        if (srcExp != null) {
            val v = Expr.eval(srcExp, ctx)
            if (v === EVal.Missing) return null
            path = v.asStr()
        } else {
            path = node.attr("src")
        }
        if (path.isNullOrEmpty()) return null
        val srcid = node.attr("srcid")
        if (srcid != null) {
            val idVal = Expr.eval(srcid, ctx)
            if (idVal === EVal.Missing) return null
            val id = if (idVal is EVal.Num) formatNum(idVal.v) else idVal.asStr()
            path = variantPath(path, id)
        }
        return path
    }

    // ---------- Image ----------

    private fun drawImage(
        node: XNode,
        canvas: Canvas,
        ctx: EvalContext,
        env: RenderEnv,
        ox: Float,
        oy: Float,
        s: Float,
        alpha: Float,
    ) {
        val path = resolveImagePath(node, ctx) ?: return
        // 约定俗成的假状态栏图不渲染（通知栏有真状态栏）
        if (path.substringAfterLast('/') == "statusbar.png") return
        val bmp = bitmap(path) ?: return

        val wE = if (hasNumAttr(node, "w")) numAttr(node, "w", ctx)
        else if (hasNumAttr(node, "width")) numAttr(node, "width", ctx) else null
        val hE = if (hasNumAttr(node, "h")) numAttr(node, "h", ctx)
        else if (hasNumAttr(node, "height")) numAttr(node, "height", ctx) else null

        var dw = bmp.width.toFloat()
        var dh = bmp.height.toFloat()
        if (wE != null && hE != null) {
            dw = wE; dh = hE
        } else if (wE != null) {
            dw = wE; dh = wE * bmp.height / bmp.width
        } else if (hE != null) {
            dh = hE; dw = hE * bmp.width / bmp.height
        }
        if (dw <= 0f || dh <= 0f) return

        val rw = dw * s
        val rh = dh * s
        val anchorX = ox + numAttr(node, "x", ctx) * s
        val anchorY = oy + numAttr(node, "y", ctx) * s
        val left = when (node.attr("align")) {
            "center" -> anchorX - rw / 2f
            "right" -> anchorX - rw
            else -> anchorX
        }
        val top = when (node.attr("alignV")) {
            "center" -> anchorY - rh / 2f
            "bottom" -> anchorY - rh
            else -> anchorY
        }
        val dst = RectF(left, top, left + rw, top + rh)
        // 主题背景图开关（v2.0.3）：关时跳过文档顺序里第一个覆盖画布
        // ≥95% 的 Image（整幅背景图）。覆盖判定：dst 罩住画布
        // （#screen_width × #screen_height）的 95% 区域。云镜的
        // xr.png 即此；hwt 的 b.png 全屏拼贴也会被跳过（已知取舍，
        // 开关打开即恢复）。跳过后其余全屏图仍照常画。
        if (!showBackgroundImage && !backgroundHandled) {
            val cw = env.screenWidthPx.toFloat()
            val ch = env.screenHeightPx.toFloat()
            if (cw > 0f && ch > 0f &&
                dst.left <= cw * 0.05f && dst.top <= ch * 0.05f &&
                dst.right >= cw * 0.95f && dst.bottom >= ch * 0.95f
            ) {
                backgroundHandled = true
                drawnCounter--
                if (!backgroundSkipLogged) {
                    backgroundSkipLogged = true
                    log("theme background image skipped (switch off): $path")
                }
                return
            }
        }
        paint.reset()
        paint.isAntiAlias = true
        paint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
        if (node.attr("scaleType") == "center_crop") {
            val scale = max(rw / bmp.width, rh / bmp.height)
            val srcW = rw / scale
            val srcH = rh / scale
            val srcL = ((bmp.width - srcW) / 2f).roundToInt().coerceIn(0, bmp.width)
            val srcT = ((bmp.height - srcH) / 2f).roundToInt().coerceIn(0, bmp.height)
            val srcR = (srcL + srcW.roundToInt()).coerceAtMost(bmp.width)
            val srcB = (srcT + srcH.roundToInt()).coerceAtMost(bmp.height)
            canvas.drawBitmap(bmp, Rect(srcL, srcT, srcR, srcB), dst, paint)
        } else {
            canvas.drawBitmap(bmp, null, dst, paint)
        }
        trackContent(env, top, top + rh, rh)
    }

    // ---------- Text / DateTime ----------

    private fun drawText(
        node: XNode,
        canvas: Canvas,
        ctx: EvalContext,
        env: RenderEnv,
        ox: Float,
        oy: Float,
        s: Float,
        alpha: Float,
    ) {
        val content = resolveTextContent(node, ctx) ?: return
        if (content.isEmpty()) return
        drawTextLike(node, canvas, ctx, env, ox, oy, s, alpha, content)
    }

    private fun resolveTextContent(node: XNode, ctx: EvalContext): String? {
        node.attr("text")?.let { return it }
        val before = ctx.missingWeatherTouches
        node.attr("textExp")?.let { exp ->
            val v = Expr.eval(exp, ctx)
            if (v === EVal.Missing || ctx.missingWeatherTouches != before) return null
            return v.asStr()
        }
        val format = node.attr("format") ?: return null
        val parasRaw = node.attr("paras")
        val args = ArrayList<EVal>()
        if (!parasRaw.isNullOrBlank()) {
            for (token in parasRaw.split(',')) {
                val t = token.trim()
                if (t.isEmpty()) continue
                val v = when {
                    t.startsWith("#") -> ctx.resolve(t.substring(1))
                    t.startsWith("@") -> ctx.resolve(t.substring(1))
                    else -> t.toDoubleOrNull()?.let { EVal.Num(it) } ?: ctx.resolve(t)
                }
                if (v === EVal.Missing || ctx.missingWeatherTouches != before) return null
                args.add(v)
            }
        }
        return safeFormat(format, args)
    }

    private fun safeFormat(format: String, args: List<EVal>): String {
        val converted: List<Any> = args.map { v ->
            when (v) {
                is EVal.Num -> if (v.v == Math.floor(v.v)) v.v.toLong() else v.v
                is EVal.Str -> v.s
                EVal.Missing -> ""
            }
        }
        try {
            @Suppress("SpreadOperator")
            return String.format(Locale.ROOT, format, *converted.toTypedArray())
        } catch (t: Throwable) {
            // 兜底：顺序替换 %d/%s/%f，%% 还原为 %
            val sb = StringBuilder()
            var argIdx = 0
            var i = 0
            while (i < format.length) {
                val c = format[i]
                if (c == '%' && i + 1 < format.length) {
                    val n = format[i + 1]
                    if (n == '%') {
                        sb.append('%')
                        i += 2
                        continue
                    }
                    if ((n == 'd' || n == 's' || n == 'f') && argIdx < args.size) {
                        sb.append(args[argIdx].asStr())
                        argIdx++
                        i += 2
                        continue
                    }
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }
    }

    private fun drawTextLike(
        node: XNode,
        canvas: Canvas,
        ctx: EvalContext,
        env: RenderEnv,
        ox: Float,
        oy: Float,
        s: Float,
        alpha: Float,
        content: String,
    ) {
        val size = numAttr(node, "size", ctx, 30f)
        if (size <= 0f) return
        val bold = node.attr("bold") == "true"
        paint.reset()
        paint.isAntiAlias = true
        paint.typeface = makeTypeface(bold)
        paint.textSize = size * s
        paint.color = parseColor(node.attr("color"))
        paint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)

        val textWidth = paint.measureText(content)
        val anchorX = ox + numAttr(node, "x", ctx) * s
        val anchorY = oy + numAttr(node, "y", ctx) * s
        val drawX = when (node.attr("align")) {
            "center" -> anchorX - textWidth / 2f
            "right" -> anchorX - textWidth
            else -> anchorX
        }
        val fm = paint.fontMetrics
        val baseline = when (node.attr("alignV")) {
            "center" -> anchorY - (fm.ascent + fm.descent) / 2f
            "bottom" -> anchorY - fm.descent
            else -> anchorY - fm.ascent
        }
        canvas.drawText(content, drawX, baseline, paint)
        // 内容下缘统计口径（v2.0.3）：Text/DateTime 按「y + 字号实测
        // 行高（descent - ascent）」计下缘。v2.0.2 按 baseline+descent
        // 计，alignV=center 时只算半行高，跟随内容模式下头部高度少算
        // 约半行——云镜三小模块文字（y=888、size 15）下缘实际约 906、
        // 旧口径只算到约 898，文字被盒子下缘切掉一半（真机实证）。
        val lineHeight = fm.descent - fm.ascent
        trackContent(env, anchorY, anchorY + lineHeight, lineHeight)
    }

    private fun makeTypeface(bold: Boolean): Typeface {
        val base = typeface
        return when {
            base != null && bold -> Typeface.create(base, Typeface.BOLD)
            base != null -> base
            bold -> Typeface.DEFAULT_BOLD
            else -> Typeface.DEFAULT
        }
    }

    private fun parseColor(raw: String?): Int {
        if (raw.isNullOrBlank()) return Color.WHITE
        return try {
            Color.parseColor(raw.trim())
        } catch (t: Throwable) {
            Color.WHITE
        }
    }

    // ---------- Time ----------

    private fun drawTime(
        node: XNode,
        canvas: Canvas,
        ctx: EvalContext,
        env: RenderEnv,
        ox: Float,
        oy: Float,
        s: Float,
        alpha: Float,
    ) {
        val src = node.attr("src") ?: return
        // srcid 显式选图：单张数字图（充电界面那种只画一位的用法）
        if (node.attr("srcid") != null) {
            val path = resolveImagePath(node, ctx) ?: return
            val bmp = bitmap(path) ?: return
            drawBitmapAnchored(node, canvas, bmp, ox, oy, s, alpha, env, ctx)
            return
        }
        val hour24 = env.now.get(Calendar.HOUR_OF_DAY)
        val minute = env.now.get(Calendar.MINUTE)
        val digits = intArrayOf(hour24 / 10, hour24 % 10, minute / 10, minute % 10)
        val firstBmp = bitmap(variantPath(src, digits[0].toString())) ?: return
        // 步进与绘制宽都用位图自然宽，不取 XML 的 w：云镜 Time 写
        // w=170 但 time_*.png 实宽 140，引擎按位图原宽排位；按 170
        // 步进会把时钟撑宽，压住右侧固定位置的秒数图集（x≈663 起）
        // 与星期图（x≈661 起）——v2.0.2 真机实证，v2.0.3 改。
        // 按 140 步进：时钟右缘 = x 起点 + 560，与两者都不重叠。
        val cellW = firstBmp.width.toFloat()
        val cellH = if (hasNumAttr(node, "h")) numAttr(node, "h", ctx) else firstBmp.height.toFloat()
        if (cellW <= 0f || cellH <= 0f) return
        val dotBmp = bitmap(variantPath(src, "dot"))
        val dotW = if (dotBmp != null && dotBmp.height > 0) {
            dotBmp.width * (cellH / dotBmp.height)
        } else {
            0f
        }
        val totalW = cellW * 4f + dotW
        val anchorX = ox + numAttr(node, "x", ctx) * s
        val anchorY = oy + numAttr(node, "y", ctx) * s
        var curX = when (node.attr("align")) {
            "center" -> anchorX - totalW * s / 2f
            "right" -> anchorX - totalW * s
            else -> anchorX
        }
        val top = when (node.attr("alignV")) {
            "center" -> anchorY - cellH * s / 2f
            "bottom" -> anchorY - cellH * s
            else -> anchorY
        }
        paint.reset()
        paint.isAntiAlias = true
        paint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
        digits.forEachIndexed { idx, d ->
            if (idx == 2 && dotBmp != null) {
                val dst = RectF(curX, top, curX + dotW * s, top + cellH * s)
                canvas.drawBitmap(dotBmp, null, dst, paint)
                curX += dotW * s
            }
            val bmp = bitmap(variantPath(src, d.toString())) ?: return@forEachIndexed
            val dst = RectF(curX, top, curX + cellW * s, top + cellH * s)
            canvas.drawBitmap(bmp, null, dst, paint)
            curX += cellW * s
        }
        trackContent(env, top, top + cellH * s, cellH * s)
    }

    /** 按 Image 的锚点规则画一张位图（Time 的 srcid 单图模式复用）。 */
    private fun drawBitmapAnchored(
        node: XNode,
        canvas: Canvas,
        bmp: Bitmap,
        ox: Float,
        oy: Float,
        s: Float,
        alpha: Float,
        env: RenderEnv,
        ctx: EvalContext,
    ) {
        val wE = if (hasNumAttr(node, "w")) numAttr(node, "w", ctx) else null
        val hE = if (hasNumAttr(node, "h")) numAttr(node, "h", ctx) else null
        var dw = bmp.width.toFloat()
        var dh = bmp.height.toFloat()
        if (wE != null && hE != null) {
            dw = wE; dh = hE
        } else if (wE != null) {
            dw = wE; dh = wE * bmp.height / bmp.width
        } else if (hE != null) {
            dh = hE; dw = hE * bmp.width / bmp.height
        }
        val rw = dw * s
        val rh = dh * s
        val anchorX = ox + numAttr(node, "x", ctx) * s
        val anchorY = oy + numAttr(node, "y", ctx) * s
        val left = when (node.attr("align")) {
            "center" -> anchorX - rw / 2f
            "right" -> anchorX - rw
            else -> anchorX
        }
        val top = when (node.attr("alignV")) {
            "center" -> anchorY - rh / 2f
            "bottom" -> anchorY - rh
            else -> anchorY
        }
        paint.reset()
        paint.isAntiAlias = true
        paint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
        canvas.drawBitmap(bmp, null, RectF(left, top, left + rw, top + rh), paint)
        trackContent(env, top, top + rh, rh)
    }

    /**
     * 内容下缘统计（「跟随内容」高度用）：整屏级背景（高 ≥ 屏高一半）
     * 与屏幕底部区域的元素（锁屏底部按钮等）不计入。
     */
    private fun trackContent(env: RenderEnv, top: Float, bottom: Float, height: Float) {
        if (height >= env.screenHeightPx * 0.5f) return
        if (top >= env.screenHeightPx * 0.75f) return
        if (bottom > contentBottomPx) contentBottomPx = bottom
    }

    companion object {
        private val VISUAL_TAGS = setOf("Group", "Image", "Text", "DateTime", "Time")
        private val WEEKDAY_CN = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

        /**
         * DateTime 的 format 格式化：yyyy/MM/M/dd/d/HH/hh/mm/ss/EE 与
         * 中文等字符直通。hh 按 Java 惯例为 12 小时制（01-12）。
         */
        fun formatDate(format: String, cal: Calendar): String {
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH) + 1
            val day = cal.get(Calendar.DAY_OF_MONTH)
            val hour24 = cal.get(Calendar.HOUR_OF_DAY)
            val hour12raw = cal.get(Calendar.HOUR)
            val hour12 = if (hour12raw == 0) 12 else hour12raw
            val minute = cal.get(Calendar.MINUTE)
            val second = cal.get(Calendar.SECOND)
            val isoDow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
            val sb = StringBuilder()
            var i = 0
            fun two(v: Int) = v.toString().padStart(2, '0')
            while (i < format.length) {
                when {
                    format.startsWith("yyyy", i) -> { sb.append(year); i += 4 }
                    format.startsWith("MM", i) -> { sb.append(two(month)); i += 2 }
                    format.startsWith("dd", i) -> { sb.append(two(day)); i += 2 }
                    format.startsWith("HH", i) -> { sb.append(two(hour24)); i += 2 }
                    format.startsWith("hh", i) -> { sb.append(two(hour12)); i += 2 }
                    format.startsWith("mm", i) -> { sb.append(two(minute)); i += 2 }
                    format.startsWith("ss", i) -> { sb.append(two(second)); i += 2 }
                    format.startsWith("EE", i) -> { sb.append(WEEKDAY_CN[isoDow - 1]); i += 2 }
                    format[i] == 'M' -> { sb.append(month); i++ }
                    format[i] == 'd' -> { sb.append(day); i++ }
                    format[i] == 'H' -> { sb.append(hour24); i++ }
                    format[i] == 'h' -> { sb.append(hour12); i++ }
                    format[i] == 'm' -> { sb.append(minute); i++ }
                    format[i] == 's' -> { sb.append(second); i++ }
                    format[i] == 'E' -> { sb.append(WEEKDAY_CN[isoDow - 1]); i++ }
                    format[i] == 'a' -> { sb.append(if (hour24 < 12) "上午" else "下午"); i++ }
                    else -> { sb.append(format[i]); i++ }
                }
            }
            return sb.toString()
        }
    }
}

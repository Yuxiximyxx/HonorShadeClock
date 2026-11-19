package io.github.yuxiximyxx.shadeclock

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Process
import java.io.File
import kotlin.math.roundToInt

/**
 * 头部卡片宿主（v2.0.32）：在 SystemUI 进程里自建标准
 * AppWidgetHost，把用户自选的应用服务卡片（标准
 * AppWidgetProvider）托管进通知中心头部覆盖层。
 *
 * 地基是 v2.0.31 探针实锤：SystemUI 进程 BIND_APPWIDGET
 * 已 GRANTED、allocate 实测可成功；荣耀自己的锁屏小部件
 * （com.hihonor.keyguard.util.AppWidgetHelper）在同进程走的
 * 正是 allocate -> bindAppWidgetIdIfAllowed -> createView
 * 全流程，本类照同一条路走，宿主名换成我们自己的标识。
 *
 * 通用化口径（小乐追加需求）：按「当前选中的 provider
 * 组件」绑定，不写死任何一张卡；默认选中天气小卡
 * （[StampStore.DEFAULT_CARD_PROVIDER]）。
 * appWidgetId 按 provider 组件分别持久化在
 * /sdcard/Download/HonorShadeClock/card_ids.txt（每行
 * 「flatten组件=id」，走 StampStore 同目录的普通文件 IO
 * 通道，与戳记/重启请求同机制）：来回切换复用各自的 id
 * ——provider 侧的实例与配置（如文字卡的自选文字）跟着
 * id 走；宿主视图每次切换时重建，provider 会把当前
 * RemoteViews 重新下发给新视图。
 *
 * 带 configure 活动的卡本版只绑不配：绑上出默认内容，
 * configure 的有无照旧记日志，配置流程留待后续版本。
 *
 * 失败口径：任何一步失败都记 [cardhost] 日志并返回 null，
 * 调用方（HeaderOverlay）回退显示主题时钟簇；同一组件
 * 失败后 [RETRY_INTERVAL_MS] 内不重试，避免坏卡在每次
 * attach 时反复撞绑定。全部异常吞掉，不许影响模块其余
 * 功能。
 */
object CardHost {

    private const val HOST_ID = 0x5344 // 'SD'；探针用的是 0x5343，互不干扰
    private const val HOST_NAME = "HonorShadeClock"
    private const val RETRY_INTERVAL_MS = 30_000L

    /** id 映射文件（StampStore.BASE_DIR 下，普通文件 IO）。 */
    val ID_FILE = File(StampStore.BASE_DIR, "card_ids.txt")

    /** provider 没声明尺寸（minW/minH=0，探针里图库多张如此）时的兜底原生尺寸（px）。 */
    private const val FALLBACK_NATIVE_W = 700
    private const val FALLBACK_NATIVE_H = 350

    class CardResult(
        val view: AppWidgetHostView,
        val info: AppWidgetProviderInfo,
        val nativeWidthPx: Int,
        val nativeHeightPx: Int,
    )

    private var host: AppWidgetHost? = null
    private var hostListening = false

    /** 组件 flatten 串 -> 最近一次失败时刻；成功即清除。 */
    private val failedAtMs = HashMap<String, Long>()

    /**
     * 为 [component] 取得一个已绑定的宿主视图。成功返回
     * 视图与 provider 原生尺寸（px 原值）；失败返回 null
     * （原因已记日志）。必须在主线程调用（视图创建）。
     */
    @Synchronized
    fun obtainView(
        ctx: Context,
        component: ComponentName,
        log: (String) -> Unit,
    ): CardResult? {
        val key = component.flattenToString()
        val lastFail = failedAtMs[key]
        if (lastFail != null &&
            System.currentTimeMillis() - lastFail < RETRY_INTERVAL_MS
        ) {
            log("[cardhost] skip (failed ${(System.currentTimeMillis() - lastFail) / 1000}s ago): $key")
            return null
        }
        return try {
            obtainViewInner(ctx, component, key, log)
        } catch (t: Throwable) {
            fail(key, log, "exception ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun obtainViewInner(
        ctx: Context,
        component: ComponentName,
        key: String,
        log: (String) -> Unit,
    ): CardResult? {
        val awm = AppWidgetManager.getInstance(ctx)
            ?: return fail(key, log, "AppWidgetManager is null")
        val info = awm.installedProviders.firstOrNull { it.provider == component }
            ?: return fail(key, log, "provider not installed: $key")
        val label = try {
            info.loadLabel(ctx.packageManager) ?: ""
        } catch (t: Throwable) {
            ""
        }
        log(
            "[cardhost] provider: $key label=$label " +
                "minW=${info.minWidth} minH=${info.minHeight} " +
                "cellW=${info.targetCellWidth} cellH=${info.targetCellHeight} " +
                "configure=${info.configure}"
        )
        if (info.configure != null) {
            log(
                "[cardhost] note: provider has configure activity " +
                    "(${info.configure}), this build binds without configuring"
            )
        }
        val h = host ?: AppWidgetHost(ctx, HOST_ID).also { host = it }
        if (!hostListening) {
            h.startListening()
            hostListening = true
            log("[cardhost] host startListening (hostId=$HOST_ID)")
        }
        val density = ctx.resources.displayMetrics.density
        val options = Bundle().apply {
            val wDp = (info.minWidth / density).roundToInt()
            val hDp = (info.minHeight / density).roundToInt()
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wDp)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hDp)
            // 宿主名标识（照 AppWidgetHelper 的 updateAppWidgetOptions 标法）
            putString("hostName", HOST_NAME)
        }
        // id：先用持久化映射里的一份，验明仍绑在本组件上才复用
        var id = persistedId(component)
        if (id != null) {
            val boundInfo = try {
                awm.getAppWidgetInfo(id)
            } catch (t: Throwable) {
                null
            }
            if (boundInfo == null || boundInfo.provider != component) {
                log(
                    "[cardhost] persisted id=$id stale " +
                        "(bound=${boundInfo?.provider}), reallocating"
                )
                try {
                    h.deleteAppWidgetId(id)
                } catch (t: Throwable) {
                    // 删不掉就算了，新 id 照样能绑
                }
                forgetId(component)
                id = null
            } else {
                log("[cardhost] reuse persisted id=$id for $key")
            }
        }
        if (id == null) {
            id = h.allocateAppWidgetId()
            persistId(component, id)
            log("[cardhost] allocated id=$id for $key")
        }
        var boundOk = awm.bindAppWidgetIdIfAllowed(
            id, Process.myUserHandle(), component, options,
        )
        log("[cardhost] bind id=$id -> $boundOk")
        if (!boundOk) {
            // 持久 id 可能已被别家宿主占用/失效：删掉重分配一次再绑
            try {
                h.deleteAppWidgetId(id)
            } catch (t: Throwable) {
                // 同上
            }
            forgetId(component)
            val freshId = h.allocateAppWidgetId()
            persistId(component, freshId)
            boundOk = awm.bindAppWidgetIdIfAllowed(
                freshId, Process.myUserHandle(), component, options,
            )
            log("[cardhost] rebind fresh id=$freshId -> $boundOk")
            if (!boundOk) {
                return fail(key, log, "bindAppWidgetIdIfAllowed denied for $key")
            }
            id = freshId
        }
        try {
            awm.updateAppWidgetOptions(id, options)
        } catch (t: Throwable) {
            log("[cardhost] updateAppWidgetOptions failed: ${t.javaClass.name}: ${t.message}")
        }
        val view = h.createView(ctx, id, info)
        log("[cardhost] createView ok id=$id view=${view.javaClass.name}")
        failedAtMs.remove(key)
        val nativeW = if (info.minWidth > 0) info.minWidth else FALLBACK_NATIVE_W
        val nativeH = if (info.minHeight > 0) info.minHeight else FALLBACK_NATIVE_H
        if (info.minWidth <= 0 || info.minHeight <= 0) {
            log(
                "[cardhost] provider declares no size " +
                    "(min=${info.minWidth}x${info.minHeight}), " +
                    "fallback native=${nativeW}x${nativeH}"
            )
        }
        return CardResult(view, info, nativeW, nativeH)
    }

    /** 卡片模式关闭时停掉宿主监听（省电/停更）；下次 obtainView 会重新 startListening。 */
    @Synchronized
    fun stopListening(log: (String) -> Unit) {
        val h = host ?: return
        if (!hostListening) return
        try {
            h.stopListening()
            hostListening = false
            log("[cardhost] host stopListening")
        } catch (t: Throwable) {
            log("[cardhost] stopListening failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun fail(key: String, log: (String) -> Unit, reason: String): CardResult? {
        failedAtMs[key] = System.currentTimeMillis()
        log("[cardhost] FAILED $key: $reason")
        return null
    }

    // ---- appWidgetId 按组件持久化（card_ids.txt，每行 flatten=id）----

    private fun loadIdMap(): MutableMap<String, Int> {
        val out = HashMap<String, Int>()
        try {
            if (ID_FILE.isFile) {
                for (line in ID_FILE.readLines()) {
                    val eq = line.lastIndexOf('=')
                    if (eq <= 0) continue
                    val id = line.substring(eq + 1).trim().toIntOrNull() ?: continue
                    out[line.substring(0, eq).trim()] = id
                }
            }
        } catch (t: Throwable) {
            // 读不出就当没有，allocate 会重建
        }
        return out
    }

    private fun saveIdMap(map: Map<String, Int>) {
        try {
            StampStore.BASE_DIR.mkdirs()
            ID_FILE.writeText(
                map.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n"
            )
        } catch (t: Throwable) {
            // 落盘失败不致命：本次进程内仍可用，下次重启重新 allocate
        }
    }

    private fun persistedId(component: ComponentName): Int? =
        loadIdMap()[component.flattenToString()]

    private fun persistId(component: ComponentName, id: Int) {
        val map = loadIdMap()
        map[component.flattenToString()] = id
        saveIdMap(map)
    }

    private fun forgetId(component: ComponentName) {
        val map = loadIdMap()
        if (map.remove(component.flattenToString()) != null) saveIdMap(map)
    }
}

package io.github.yuxiximyxx.shadeclock

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.xmlpull.v1.XmlPullParser

/**
 * 真卡片探针（v2.0.31）：只加日志、零行为改变。
 *
 * 目的：回答「荣耀桌面应用服务卡片能不能搬进通知中心头部」。
 * A 路线（标准 AppWidget）：SystemUI 自己的锁屏小部件
 * （com.hihonor.keyguard.util.AppWidgetHelper$KgAppWidgetHost）
 * 已在同进程走通 allocate -> bindAppWidgetIdIfAllowed ->
 * createView 全流程；本探针实测三件事：十个来源应用有没有
 * 标准 provider、SystemUI 进程有没有 BIND_APPWIDGET、本进程
 * new AppWidgetHost + allocateAppWidgetId 是否放行。
 * B 路线（荣耀 form 框架）：静态分析在 SystemUI 四个 dex 与
 * framework-magic.jar 里都没找到 form 宿主入口（类名/服务均
 * 无实锤），按「无已知入口」记一行并跳过，不猜类名乱试。
 *
 * 触发两次（进程内只真正跑一次）：hook 初始化、首次开门。
 * 全部输出经 log 回调写既有 shadeclock_probe.log，前缀
 * [cardprobe]；异常全吞，不许影响模块任何现有功能。
 */
object CardProbe {

    private const val HOST_ID = 0x5343 // 'SC'
    private const val MAX_PROVIDER_LINES = 400

    private val ran = AtomicBoolean(false)

    /** 目标来源应用的 label 关键词（按 label 匹配并标注，包名以枚举结果为准）。 */
    private val LABEL_KEYWORDS = listOf(
        "日历", "天气", "时钟", "联系人", "图库", "钱包", "健康",
        "文字卡片", "语录", "智慧空间", "平板",
        "Calendar", "Weather", "Clock", "Contacts", "Gallery", "Wallet", "Health"
    )

    /** hook 初始化触发点。context 拿不到时静默跳过，等首次开门再跑。 */
    fun runAtInit(log: (String) -> Unit) {
        val ctx = currentApplicationContext()
        if (ctx == null) {
            log("[cardprobe] init trigger: no application context yet, defer to first gate open")
            return
        }
        runOnce(ctx, "init", log)
    }

    /** 首次开门触发点（HeaderOverlay 在 wrapper 转 VISIBLE 时调用）。 */
    fun runAtFirstGateOpen(context: Context?, log: (String) -> Unit) {
        if (context == null) return
        runOnce(context, "first-gate-open", log)
    }

    private fun currentApplicationContext(): Context? {
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            val app = activityThread.getMethod("currentApplication").invoke(null)
            (app as? Context)
        } catch (t: Throwable) {
            null
        }
    }

    private fun runOnce(ctx: Context, source: String, log: (String) -> Unit) {
        if (!ran.compareAndSet(false, true)) return
        try {
            log("[cardprobe] ===== card probe start (trigger=$source, pid=${Process.myPid()}, uid=${Process.myUid()}) =====")
            probePermission(ctx, log)
            probeProviders(ctx, log)
            probeXmlDumpAsync(ctx, log)
            probeHostAllocate(ctx, log)
            log("[cardprobe] form-host: no known entry point (static analysis: no Honor form host service/class found in SystemUI dex or framework-magic.jar); skipped, no guessing")
            log("[cardprobe] ===== card probe end =====")
        } catch (t: Throwable) {
            log("[cardprobe] probe aborted: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun probePermission(ctx: Context, log: (String) -> Unit) {
        try {
            val perm = "android.permission.BIND_APPWIDGET"
            val self = ctx.checkPermission(perm, Process.myPid(), Process.myUid())
            log(
                "[cardprobe] permission BIND_APPWIDGET self-check: " +
                    if (self == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED($self)"
            )
            val byPkg = try {
                ctx.packageManager.checkPermission(perm, ctx.packageName)
            } catch (t: Throwable) {
                -99
            }
            log(
                "[cardprobe] permission BIND_APPWIDGET for package ${ctx.packageName}: " +
                    if (byPkg == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED($byPkg)"
            )
        } catch (t: Throwable) {
            log("[cardprobe] permission check failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun probeProviders(ctx: Context, log: (String) -> Unit) {
        try {
            val awm = AppWidgetManager.getInstance(ctx)
            if (awm == null) {
                log("[cardprobe] providers: AppWidgetManager is null")
                return
            }
            val providers = awm.installedProviders
            log("[cardprobe] providers: total=${providers.size}")
            var matched = 0
            var lines = 0
            for (info in providers) {
                val comp = info.provider
                val label = try {
                    info.loadLabel(ctx.packageManager) ?: ""
                } catch (t: Throwable) {
                    ""
                }
                val hit = LABEL_KEYWORDS.firstOrNull { label.contains(it) }
                if (hit != null) matched++
                if (lines >= MAX_PROVIDER_LINES) continue
                lines++
                log(
                    "[cardprobe] provider${if (hit != null) " [MATCH:$hit]" else ""}: " +
                        "pkg=${comp.packageName} cls=${comp.className} label=$label " +
                        "minW=${info.minWidth} minH=${info.minHeight} " +
                        "cellW=${info.targetCellWidth} cellH=${info.targetCellHeight} " +
                        "resizeMode=${info.resizeMode} widgetCategory=${info.widgetCategory}"
                )
            }
            if (providers.size > lines) {
                log("[cardprobe] providers: list truncated at $lines lines (total=${providers.size})")
            }
            log("[cardprobe] providers: label-matched=$matched of ${providers.size}")
        } catch (t: Throwable) {
            log("[cardprobe] providers enumeration failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    /**
     * 服务卡片 XML 声明 dump（v2.0.34）：小乐纠正——系统目录里
     * 「服务卡片」与「经典小工具」是两个分区，区分标记与规范
     * 尺寸档位不在运行时 API 里，最大嫌疑在每个 receiver 的
     * appwidget-provider XML 原始声明（荣耀命名空间自定义属性）。
     * 本探针在 SystemUI 侧（全量 554 可见、不受普通应用可见性
     * 过滤）逐个 loadXmlMetaData 读原始 XML，把全部属性
     * （命名空间+名+值，资源引用另记原始整数形态）写到
     * /sdcard/Download/shadeclock_cardxml.txt，供下一轮逐属性
     * 比对定案。解析 554 份 XML 较重，放后台线程跑；只写文件、
     * 零行为改变，异常全吞。
     */
    private fun probeXmlDumpAsync(ctx: Context, log: (String) -> Unit) {
        try {
            val appCtx = ctx.applicationContext ?: ctx
            val t = Thread { dumpProviderXml(appCtx, log) }
            t.name = "shadeclock-cardxml"
            t.isDaemon = true
            t.start()
            log("[cardprobe] xml-dump: scheduled on background thread -> /sdcard/Download/shadeclock_cardxml.txt")
        } catch (t: Throwable) {
            log("[cardprobe] xml-dump: schedule failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun dumpProviderXml(ctx: Context, log: (String) -> Unit) {
        val outFile = File("/sdcard/Download/shadeclock_cardxml.txt")
        var ok = 0
        var failed = 0
        try {
            val pm = ctx.packageManager
            val awm = AppWidgetManager.getInstance(ctx)
            if (awm == null) {
                log("[cardprobe] xml-dump: AppWidgetManager is null")
                return
            }
            val providers = awm.installedProviders
            outFile.bufferedWriter().use { w ->
                w.write("# HonorShadeClock cardxml dump v2.0.34 providers=${providers.size}\n")
                for (info in providers) {
                    val comp = info.provider
                    val label = try {
                        info.loadLabel(pm) ?: ""
                    } catch (t: Throwable) {
                        ""
                    }
                    w.write("=== ${comp.flattenToString()} label=$label\n")
                    try {
                        val ri = pm.getReceiverInfo(comp, PackageManager.GET_META_DATA)
                        val parser = ri.loadXmlMetaData(pm, "android.appwidget.provider")
                        if (parser == null) {
                            w.write("  <no xml metadata>\n")
                            failed++
                            continue
                        }
                        try {
                            var event = parser.eventType
                            while (event != XmlPullParser.END_DOCUMENT) {
                                if (event == XmlPullParser.START_TAG) {
                                    val sb = StringBuilder()
                                    sb.append("  <").append(parser.name)
                                    for (i in 0 until parser.attributeCount) {
                                        val ns = parser.getAttributeNamespace(i)
                                        val name = parser.getAttributeName(i)
                                        val value = try {
                                            parser.getAttributeValue(i)
                                        } catch (t: Throwable) {
                                            null
                                        }
                                        sb.append('\n').append("    ")
                                        if (!ns.isNullOrEmpty()) {
                                            sb.append('{').append(ns).append('}')
                                        }
                                        sb.append(name).append("=\"").append(value).append('"')
                                        val rawInt = try {
                                            parser.getAttributeIntValue(i, Int.MIN_VALUE)
                                        } catch (t: Throwable) {
                                            Int.MIN_VALUE
                                        }
                                        if (rawInt != Int.MIN_VALUE) {
                                            sb.append(" rawInt=").append(rawInt)
                                                .append("/0x")
                                                .append(Integer.toHexString(rawInt))
                                        }
                                    }
                                    w.write(sb.toString())
                                    w.write("\n")
                                }
                                event = parser.next()
                            }
                        } finally {
                            try {
                                parser.close()
                            } catch (t: Throwable) {
                                // 关不掉就算了
                            }
                        }
                        ok++
                    } catch (t: Throwable) {
                        failed++
                        w.write("  <error ${t.javaClass.name}: ${t.message}>\n")
                    }
                }
                w.write("# end ok=$ok failed=$failed\n")
            }
            log("[cardprobe] xml-dump: done ok=$ok failed=$failed -> ${outFile.absolutePath}")
        } catch (t: Throwable) {
            log("[cardprobe] xml-dump: aborted: ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun probeHostAllocate(ctx: Context, log: (String) -> Unit) {
        var host: AppWidgetHost? = null
        var id = -1
        try {
            host = AppWidgetHost(ctx, HOST_ID)
            log("[cardprobe] host: new AppWidgetHost(hostId=$HOST_ID) ok")
            id = host.allocateAppWidgetId()
            log("[cardprobe] host: allocateAppWidgetId -> $id (no bind attempted)")
        } catch (t: Throwable) {
            log("[cardprobe] host: allocate failed: ${t.javaClass.name}: ${t.message}")
        } finally {
            // 只做卫生清理：把刚分配的 id 删掉，不绑任何真卡片
            try {
                if (host != null && id > 0) {
                    host.deleteAppWidgetId(id)
                    log("[cardprobe] host: cleanup deleteAppWidgetId($id) done")
                }
            } catch (t: Throwable) {
                log("[cardprobe] host: cleanup failed: ${t.javaClass.name}: ${t.message}")
            }
        }
    }
}

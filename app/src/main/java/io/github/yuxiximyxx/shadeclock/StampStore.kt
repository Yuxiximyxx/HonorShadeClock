package io.github.yuxiximyxx.shadeclock

import java.io.File

/**
 * 覆盖层设置的跨进程通道：App 侧把「选中主题/戳记/高度档/顶部样式」
 * 写成一个小文件 /sdcard/Download/HonorShadeClock/stamp.txt（经
 * MediaStore 写入，普通应用对公共 Download 目录的合规写路径）；
 * SystemUI 侧用普通文件 IO 读（它对 Download 的读写已被探针日志
 * 实证可行）。主题资产同样放在 /sdcard/Download/HonorShadeClock/
 * themes/<id>/（manifest.xml + 资产 + fonts/）。
 *
 * stamp.txt 格式（8 行）：themeId / stamp / heightMode / topStyle /
 * showBackground（1=画主题整幅背景图、0=不画，v2.0.3 加；旧 4 行
 * 文件缺此行时按 0 处理，与设置页开关默认关一致）/
 * showWidgets（1=画温度/风向/电量数据小模块、0=不画，v2.0.5 加；
 * 旧 5 行文件缺此行时按 0 处理，与设置页开关默认关一致）/
 * offsetDp（覆盖层垂直偏移，整数 dp，负值上移，v2.0.7 加；
 * 旧 6 行文件缺此行时按 0 处理）/
 * hideCarrier（1=隐藏运营商文字、0=不隐藏，v2.0.8 加；设置页
 * 开关默认开，旧 7 行文件缺此行时按 1 处理，与默认一致）。
 * SystemUI 侧每次覆盖层挂载与每秒 tick 比对文件 mtime 与内容，
 * 变了就重解析主题、重载位图——切换主题免强停系统界面。
 */
object StampStore {

    const val TOP_STYLE_ORIGINAL = "original"
    const val TOP_STYLE_THEME = "theme"

    const val HEIGHT_FOLLOW = 0
    const val HEIGHT_THIRD = 1
    const val HEIGHT_HALF = 2

    /** 覆盖层垂直偏移范围（dp）：负值上移、正值下移（v2.0.7）。 */
    const val OFFSET_DP_MIN = -120
    const val OFFSET_DP_MAX = 40

    /** MediaStore 相对目录（无首尾斜杠）。 */
    const val STAMP_REL_DIR = "Download/HonorShadeClock"
    const val THEMES_REL_DIR = "Download/HonorShadeClock/themes"
    const val STAMP_NAME = "stamp.txt"

    val BASE_DIR = File("/sdcard/Download/HonorShadeClock")
    val THEMES_DIR = File(BASE_DIR, "themes")
    val STAMP_FILE = File(BASE_DIR, "stamp.txt")

    data class Stamp(
        val themeId: String,
        val stamp: Long,
        val heightMode: Int,
        val topStyle: String,
        /** 是否绘制主题的整幅背景图；默认 false（设置页开关默认关）。 */
        val showBackground: Boolean = false,
        /** 是否绘制温度/风向/电量数据小模块；默认 false（设置页开关默认关，v2.0.5）。 */
        val showWidgets: Boolean = false,
        /** 覆盖层垂直偏移（dp，负值上移）；默认 0（v2.0.7）。 */
        val offsetDp: Int = 0,
        /** 是否隐藏运营商文字（锁屏/控制中心/通知中心三处）；默认 true（设置页开关默认开，v2.0.8）。 */
        val hideCarrier: Boolean = true,
    )

    fun themeDir(id: String): File = File(THEMES_DIR, id)

    fun formatStamp(
        themeId: String,
        stamp: Long,
        heightMode: Int,
        topStyle: String,
        showBackground: Boolean,
        showWidgets: Boolean,
        offsetDp: Int,
        hideCarrier: Boolean,
    ): String =
        "$themeId\n$stamp\n$heightMode\n$topStyle\n" +
            "${if (showBackground) 1 else 0}\n${if (showWidgets) 1 else 0}\n" +
            "${offsetDp.coerceIn(OFFSET_DP_MIN, OFFSET_DP_MAX)}\n" +
            "${if (hideCarrier) 1 else 0}\n"

    /** SystemUI 侧读取；文件不存在/内容不完整返回 null。 */
    fun readStamp(): Stamp? {
        return try {
            if (!STAMP_FILE.isFile) return null
            val lines = STAMP_FILE.readText().split('\n').map { it.trim() }
            if (lines.size < 4) return null
            Stamp(
                themeId = lines[0],
                stamp = lines[1].toLongOrNull() ?: 0L,
                heightMode = lines[2].toIntOrNull() ?: HEIGHT_FOLLOW,
                topStyle = lines[3].ifEmpty { TOP_STYLE_ORIGINAL },
                // 第 5 行缺失（旧版 stamp）按关处理，与开关默认一致
                showBackground = lines.getOrNull(4) == "1",
                // 第 6 行缺失（v2.0.4 及更旧 stamp）按关处理，与开关默认一致
                showWidgets = lines.getOrNull(5) == "1",
                // 第 7 行缺失（v2.0.6 及更旧 stamp）按 0 处理
                offsetDp = (lines.getOrNull(6)?.toIntOrNull() ?: 0)
                    .coerceIn(OFFSET_DP_MIN, OFFSET_DP_MAX),
                // 第 8 行缺失（v2.0.7 及更旧 stamp）按开处理，与开关默认开一致；
                // 只有明确写 0 才是不隐藏
                hideCarrier = lines.getOrNull(7) != "0",
            )
        } catch (t: Throwable) {
            null
        }
    }

    fun stampMtime(): Long = try {
        if (STAMP_FILE.isFile) STAMP_FILE.lastModified() else 0L
    } catch (t: Throwable) {
        0L
    }
}

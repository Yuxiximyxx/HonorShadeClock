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
 * stamp.txt 格式（4 行）：themeId / stamp / heightMode / topStyle。
 * SystemUI 侧每次覆盖层挂载与每秒 tick 比对文件 mtime 与内容，
 * 变了就重解析主题、重载位图——切换主题免强停系统界面。
 */
object StampStore {

    const val TOP_STYLE_ORIGINAL = "original"
    const val TOP_STYLE_THEME = "theme"

    const val HEIGHT_FOLLOW = 0
    const val HEIGHT_THIRD = 1
    const val HEIGHT_HALF = 2

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
    )

    fun themeDir(id: String): File = File(THEMES_DIR, id)

    fun formatStamp(themeId: String, stamp: Long, heightMode: Int, topStyle: String): String =
        "$themeId\n$stamp\n$heightMode\n$topStyle\n"

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

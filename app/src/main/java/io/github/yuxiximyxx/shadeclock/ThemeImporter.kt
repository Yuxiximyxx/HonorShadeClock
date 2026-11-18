package io.github.yuxiximyxx.shadeclock

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.zip.ZipFile

/** 已导入主题的一条登记。 */
data class ThemeEntry(val id: String, val name: String)

/**
 * 公共 Download 目录的合规读写（MediaStore）：普通应用对
 * /sdcard/Download 无直接文件写权限，经 MediaStore.Files 创建的
 * 自有文件可免权限写入/覆写/删除；SystemUI 侧用普通文件 IO 读。
 */
object PublicStore {

    private val FILES_URI: Uri = MediaStore.Files.getContentUri("external")

    /** relDir 形如 "Download/HonorShadeClock/themes/<id>/time"（无首尾斜杠）。 */
    fun writeFile(context: Context, relDir: String, name: String, data: ByteArray): Boolean {
        val cr = context.contentResolver
        val rel = relDir.trim('/') + "/"
        return try {
            val uri = queryUri(cr, rel, name) ?: insertFile(cr, rel, name) ?: return false
            val out = cr.openOutputStream(uri, "wt") ?: return false
            out.use { it.write(data) }
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 删除 relDir 前缀下的全部自有文件，返回删除条数。 */
    fun deleteTree(context: Context, relDir: String): Int {
        val cr = context.contentResolver
        val prefix = relDir.trim('/') + "/"
        var deleted = 0
        try {
            cr.query(
                FILES_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$prefix%"),
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val ids = ArrayList<Long>()
                while (cursor.moveToNext()) ids.add(cursor.getLong(idIdx))
                for (id in ids) {
                    deleted += cr.delete(ContentUris.withAppendedId(FILES_URI, id), null, null)
                }
            }
        } catch (t: Throwable) {
            // 尽力而为
        }
        return deleted
    }

    private fun queryUri(
        cr: android.content.ContentResolver,
        rel: String,
        name: String,
    ): Uri? {
        return try {
            cr.query(
                FILES_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(rel, name),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    ContentUris.withAppendedId(FILES_URI, cursor.getLong(0))
                } else {
                    null
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun insertFile(
        cr: android.content.ContentResolver,
        rel: String,
        name: String,
    ): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(name))
            }
            cr.insert(FILES_URI, values)
        } catch (t: Throwable) {
            null
        }
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "xml" -> "text/xml"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}

/** 主题登记表（存 settings 偏好）与 stamp.txt 的写入。 */
object ThemeStore {

    const val KEY_THEMES_JSON = "themes_json"
    const val KEY_SELECTED_THEME = "selected_theme_id"
    const val KEY_TOP_STYLE = "top_style"
    const val KEY_HEIGHT_MODE = "height_mode"
    const val KEY_SHOW_BACKGROUND = "show_background"
    const val KEY_SHOW_WIDGETS = "show_widgets"
    const val KEY_OFFSET_DP = "offset_dp"
    const val KEY_HIDE_CARRIER = "hide_carrier"

    fun loadThemes(prefs: SharedPreferences): List<ThemeEntry> {
        val raw = prefs.getString(KEY_THEMES_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<ThemeEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isNotEmpty()) out.add(ThemeEntry(id, obj.optString("name", id)))
            }
            out
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun saveThemes(prefs: SharedPreferences, themes: List<ThemeEntry>) {
        val arr = JSONArray()
        for (t in themes) {
            arr.put(JSONObject().put("id", t.id).put("name", t.name))
        }
        prefs.edit().putString(KEY_THEMES_JSON, arr.toString()).apply()
    }

    /** 按当前偏好重写 stamp.txt（调用方需在 IO 线程执行）。 */
    fun writeStamp(context: Context, prefs: SharedPreferences) {
        val selected = prefs.getString(KEY_SELECTED_THEME, "") ?: ""
        val topStyle = prefs.getString(KEY_TOP_STYLE, StampStore.TOP_STYLE_THEME)
            ?: StampStore.TOP_STYLE_THEME
        val heightMode = prefs.getInt(KEY_HEIGHT_MODE, StampStore.HEIGHT_FOLLOW)
        val showBackground = prefs.getBoolean(KEY_SHOW_BACKGROUND, false)
        val showWidgets = prefs.getBoolean(KEY_SHOW_WIDGETS, false)
        val offsetDp = prefs.getInt(KEY_OFFSET_DP, 0)
        val hideCarrier = prefs.getBoolean(KEY_HIDE_CARRIER, true)
        val content = StampStore.formatStamp(
            selected, System.currentTimeMillis(), heightMode, topStyle,
            showBackground, showWidgets, offsetDp, hideCarrier,
        )
        PublicStore.writeFile(
            context, StampStore.STAMP_REL_DIR, StampStore.STAMP_NAME,
            content.toByteArray(Charsets.UTF_8),
        )
    }

    /** 删除一个主题：清资产、摘登记、若是当前选中则清选中，最后重写 stamp。 */
    fun deleteTheme(context: Context, prefs: SharedPreferences, id: String) {
        PublicStore.deleteTree(context, "${StampStore.THEMES_REL_DIR}/$id")
        val themes = loadThemes(prefs).filter { it.id != id }
        saveThemes(prefs, themes)
        if (prefs.getString(KEY_SELECTED_THEME, "") == id) {
            prefs.edit().putString(KEY_SELECTED_THEME, "").apply()
        }
        writeStamp(context, prefs)
    }
}

/** .hnt/.hwt 主题包导入：解包、定位锁屏目录、把资产抽到公共目录。 */
object ThemeImporter {

    sealed interface Result {
        class Ok(val entry: ThemeEntry, val fileCount: Int) : Result
        class Err(val message: String) : Result
    }

    fun importTheme(context: Context, uri: Uri): Result {
        val cacheZip = File(context.cacheDir, "theme_import_${System.currentTimeMillis()}.zip")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return Result.Err("读不到所选文件")
            input.use { ins -> cacheZip.outputStream().use { out -> ins.copyTo(out) } }

            ZipFile(cacheZip).use { zip ->
                val entries = Collections.list(zip.entries())
                val title = readTitle(zip, entries)
                    ?: displayName(context, uri)?.substringBeforeLast('.')
                    ?: "未命名主题"

                var dynamicPath = "lockscreen"
                val themeXml = entries.firstOrNull { it.name == "unlock/theme.xml" }
                if (themeXml != null) {
                    val text = zip.getInputStream(themeXml).readBytes().toString(Charsets.UTF_8)
                    val m = Regex("""dynamicPath\s*=\s*"([^"]+)"""").find(text)
                    if (m != null) dynamicPath = m.groupValues[1]
                }
                val prefix = "unlock/$dynamicPath/"
                if (entries.none { it.name == prefix + "manifest.xml" }) {
                    return Result.Err("包里找不到 ${prefix}manifest.xml，不是可用的锁屏主题")
                }

                val id = "t" + System.currentTimeMillis().toString(36)
                var count = 0
                for (entry in entries) {
                    if (entry.isDirectory) continue
                    val name = entry.name
                    when {
                        name.startsWith(prefix) -> {
                            val rel = name.removePrefix(prefix)
                            if (rel.isEmpty()) continue
                            val dirPart = rel.substringBeforeLast('/', "")
                            val fileName = rel.substringAfterLast('/')
                            val relDir = if (dirPart.isEmpty()) {
                                "${StampStore.THEMES_REL_DIR}/$id"
                            } else {
                                "${StampStore.THEMES_REL_DIR}/$id/$dirPart"
                            }
                            val data = zip.getInputStream(entry).readBytes()
                            if (PublicStore.writeFile(context, relDir, fileName, data)) count++
                        }
                        name.startsWith("fonts/") &&
                            (name.endsWith(".ttf", true) || name.endsWith(".otf", true)) -> {
                            val data = zip.getInputStream(entry).readBytes()
                            val fileName = name.substringAfterLast('/')
                            if (PublicStore.writeFile(
                                    context,
                                    "${StampStore.THEMES_REL_DIR}/$id/fonts",
                                    fileName, data,
                                )
                            ) {
                                count++
                            }
                        }
                    }
                }
                if (count == 0) {
                    return Result.Err("资产写入失败（公共存储不可写）")
                }
                return Result.Ok(ThemeEntry(id, title), count)
            }
        } catch (t: Throwable) {
            return Result.Err("解析失败：${t.javaClass.simpleName}: ${t.message}")
        } finally {
            cacheZip.delete()
        }
    }

    private fun readTitle(zip: ZipFile, entries: List<java.util.zip.ZipEntry>): String? {
        val desc = entries.firstOrNull { it.name == "description.xml" } ?: return null
        return try {
            val text = zip.getInputStream(desc).readBytes().toString(Charsets.UTF_8)
            Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
                .find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            null
        }
    }

    private fun displayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (t: Throwable) {
            null
        }
    }
}

package io.github.yuxiximyxx.shadeclock

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import io.github.yuxiximyxx.shadeclock.theme.ThemePackage
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

    /** relDir 形如 "Download/HonorShadeClock/themes/<id>"（无首尾斜杠）。 */
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

    /** 流式写入（主题包本体可达几十 MB，不整读进内存）。 */
    fun writeStream(
        context: Context,
        relDir: String,
        name: String,
        input: java.io.InputStream,
    ): Boolean = writeStreamUri(context, relDir, name, input) != null

    /** 流式写入并返回落盘条目的 Uri（迁移校验回读要用）；失败返回 null。 */
    fun writeStreamUri(
        context: Context,
        relDir: String,
        name: String,
        input: java.io.InputStream,
    ): Uri? {
        val cr = context.contentResolver
        val rel = relDir.trim('/') + "/"
        return try {
            val uri = queryUri(cr, rel, name) ?: insertFile(cr, rel, name) ?: return null
            val out = cr.openOutputStream(uri, "wt") ?: return null
            out.use { input.copyTo(it) }
            uri
        } catch (t: Throwable) {
            null
        }
    }

    /** 一条已登记文件的句柄：uri + 完整相对路径（形如 Download/HonorShadeClock/themes/<id>/time/time_0.png）。 */
    class StoredFile(val uri: Uri, val fullPath: String)

    /** 枚举 relDir 前缀下的全部自有文件（含子目录）。 */
    fun listFiles(context: Context, relDir: String): List<StoredFile> {
        val cr = context.contentResolver
        val prefix = relDir.trim('/') + "/"
        val out = ArrayList<StoredFile>()
        try {
            cr.query(
                FILES_URI,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                ),
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                arrayOf("$prefix%"),
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val rel = cursor.getString(relIdx) ?: continue
                    val name = cursor.getString(nameIdx) ?: continue
                    out.add(
                        StoredFile(
                            ContentUris.withAppendedId(FILES_URI, cursor.getLong(idIdx)),
                            rel + name,
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            // 尽力而为
        }
        return out
    }

    /** 删除一条已登记文件（文件本体与 MediaStore 条目一并消失）。 */
    fun deleteFile(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.delete(uri, null, null) > 0
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

    /**
     * 兜底（v2.0.18）：在 HonorShadeClock 目录根放 .nomedia，
     * 媒体扫描器跳过整棵子树——即便将来又落了图片文件也不进
     * 图库。写入失败尽力而为（本体按非媒体登记才是主防线）。
     */
    fun ensureNoMedia(context: Context) {
        PublicStore.writeFile(
            context, StampStore.STAMP_REL_DIR, StampStore.NOMEDIA_NAME, ByteArray(0),
        )
    }

    /**
     * 存量迁移（v2.0.18）：旧版导入把主题解压成一堆散图存在
     * themes/<id>/ 下、还以图片条目登记进 MediaStore，全被图库
     * 显示出来。迁移把每个登记主题的散文件就地打包回一个
     * theme.pkg（条目结构与新导入一致：锁屏资产在
     * unlock/lockscreen/ 下、字体保持包根 fonts/），再逐条删掉
     * 散文件——删 MediaStore 条目即删文件本体，图库条目随之
     * 消失。返回成功迁移的主题数；无散文件的主题跳过；打包
     * 后必须回读校验通过才删散文件（v2.0.19，防再现 2.0.18
     * 迁移把 103 图打成 2 图还删原件的数据丢失）。
     * 调用方在设置页打开时于 IO 线程跑一次，幂等；迁移后主题
     * id 不变、登记不变，不需要用户重新导入。
     */
    fun migrateLegacyThemes(context: Context, prefs: SharedPreferences): Int {
        ensureNoMedia(context)
        var migrated = 0
        for (entry in loadThemes(prefs)) {
            if (migrateOneTheme(context, entry.id)) migrated++
        }
        return migrated
    }

    private fun migrateOneTheme(context: Context, id: String): Boolean {
        val relDir = "${StampStore.THEMES_REL_DIR}/$id"
        val prefix = "$relDir/"
        val pkgPath = prefix + StampStore.THEME_PACKAGE_NAME
        val files = PublicStore.listFiles(context, relDir)
        val legacy = files.filter { it.fullPath != pkgPath }
        if (legacy.isEmpty()) return false
        // v2.0.19 安全性重写：v2.0.18 的迁移在真机上把一个
        // 103 图的主题打成了只剩 2 图的包、还把散图全删了，
        // 数据不可逆丢失（枚举/读流任一环静默少件都会如此）。
        // 本版口径：只要还有散文件就一律重新打包（不因「已有
        // 包」就跳过打包只删散文件——那会把坏包坐实）；任一
        // 散文件读不出来就整题放弃、散文件原样保留；包写完
        // 必须回读校验（manifest 可定位、条目数与图片数与打
        // 包计数一致）通过后才删散文件，校验不过删掉新包、
        // 散文件保留，渲染侧继续走解压目录兜底。
        val tmp = File(context.cacheDir, "theme_migrate_$id.zip")
        var packedEntries = 0
        var packedImages = 0
        try {
            java.util.zip.ZipOutputStream(tmp.outputStream().buffered()).use { zos ->
                for (f in legacy) {
                    val rel = f.fullPath.removePrefix(prefix)
                    // 旧解压布局：锁屏资产在 <id>/<rel>、字体在
                    // <id>/fonts/；回包时锁屏资产归位到
                    // unlock/lockscreen/ 下（渲染器缺省
                    // dynamicPath 即 lockscreen，与原包是否
                    // 写过 theme.xml 无关、自洽），字体保持
                    // 包根 fonts/。
                    val entryName = if (rel.startsWith("fonts/")) {
                        rel
                    } else {
                        "unlock/lockscreen/$rel"
                    }
                    val input = context.contentResolver.openInputStream(f.uri)
                        ?: return false
                    zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                    input.use { it.copyTo(zos) }
                    zos.closeEntry()
                    packedEntries++
                    if (isImageName(entryName)) packedImages++
                }
            }
            val pkgUri = tmp.inputStream().buffered().use { ins ->
                PublicStore.writeStreamUri(
                    context, relDir, StampStore.THEME_PACKAGE_NAME, ins,
                )
            }
            if (pkgUri == null) {
                tmp.delete()
                return false
            }
            if (!verifyPackage(context, pkgUri, packedEntries, packedImages)) {
                PublicStore.deleteFile(context, pkgUri)
                tmp.delete()
                return false
            }
        } catch (t: Throwable) {
            tmp.delete()
            return false
        }
        tmp.delete()
        // 包已校验就位：删尽散文件（连同其 MediaStore 条目）
        for (f in legacy) {
            PublicStore.deleteFile(context, f.uri)
        }
        return true
    }

    private fun isImageName(name: String): Boolean =
        name.endsWith(".png", true) || name.endsWith(".jpg", true) ||
            name.endsWith(".jpeg", true) || name.endsWith(".webp", true)

    /** 回读刚写的主题包做完整性校验：manifest 可定位、条目数与锁屏图片数与打包计数一致。 */
    private fun verifyPackage(
        context: Context,
        uri: Uri,
        expectEntries: Int,
        expectImages: Int,
    ): Boolean {
        val tmp = File(context.cacheDir, "theme_verify_${System.currentTimeMillis()}.zip")
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return false
            input.use { ins -> tmp.outputStream().use { out -> ins.copyTo(out) } }
            ZipFile(tmp).use { zip ->
                val prefix = ThemePackage.lockscreenPrefix(zip) ?: return false
                val entries = Collections.list(zip.entries()).filter { !it.isDirectory }
                if (entries.size != expectEntries) return false
                val images = entries.count {
                    it.name.startsWith(prefix) && isImageName(it.name)
                }
                images == expectImages
            }
        } catch (t: Throwable) {
            false
        } finally {
            tmp.delete()
        }
    }
}

/**
 * .hnt/.hwt 主题包导入（v2.0.18 改存包本体）：校验包里有锁屏
 * manifest 后，只把主题文件原样复制一份存进 themes/<id>/（文件
 * 名 theme.pkg，按 application/octet-stream 登记、不是图片条
 * 目，图库索引不到），渲染器运行时直接读这份 zip。不再解压
 * 散图——旧版解压出的散图全进了图库，就是这版要改掉的。
 */
object ThemeImporter {

    sealed interface Result {
        class Ok(val entry: ThemeEntry) : Result
        class Err(val message: String) : Result
    }

    fun importTheme(context: Context, uri: Uri): Result {
        val cacheZip = File(context.cacheDir, "theme_import_${System.currentTimeMillis()}.zip")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return Result.Err("读不到所选文件")
            input.use { ins -> cacheZip.outputStream().use { out -> ins.copyTo(out) } }

            val title = ZipFile(cacheZip).use { zip ->
                if (ThemePackage.lockscreenPrefix(zip) == null) {
                    return Result.Err("包里找不到锁屏 manifest.xml，不是可用的锁屏主题")
                }
                val entries = Collections.list(zip.entries())
                readTitle(zip, entries)
                    ?: displayName(context, uri)?.substringBeforeLast('.')
                    ?: "未命名主题"
            }

            val id = "t" + System.currentTimeMillis().toString(36)
            val ok = cacheZip.inputStream().buffered().use { ins ->
                PublicStore.writeStream(
                    context,
                    "${StampStore.THEMES_REL_DIR}/$id",
                    StampStore.THEME_PACKAGE_NAME,
                    ins,
                )
            }
            if (!ok) {
                return Result.Err("主题文件写入失败（公共存储不可写）")
            }
            ThemeStore.ensureNoMedia(context)
            return Result.Ok(ThemeEntry(id, title))
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

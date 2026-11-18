package io.github.yuxiximyxx.shadeclock

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 设置页（v2.0.3 起）：顶部样式（原系统头部 / 锁屏主题样式）、主题
 * 管理（导入/切换/删除）、区域高度三档、字号滑杆（原头部模式）；
 * v2.0.7 加「垂直偏移」滑杆（-120dp～+40dp，覆盖层整体上下移，
 * 经 stamp 第 7 行传 SystemUI 侧）；v2.0.8 加「隐藏运营商文字」
 * 开关（默认开，stamp 第 8 行，锁屏/控制中心/通知中心三处统管）；
 * v2.0.12 加「重启系统界面」按钮（写 restart.txt 请求标记，
 * SystemUI 侧看守读到后结束自己进程被系统拉起，等同强停、
 * 不依赖 root，省得每次验版手动强停）。
 * 覆盖层相关设置经 stamp.txt（公共 Download 目录）传给 SystemUI
 * 侧，切换免强停；字号仍走 SharedPreferences「settings」，由 hook
 * 侧在 SystemUI 进程启动时经 XSharedPreferences 读取。
 * 样式照 MagicPods v2.11 的 MIUIX 写法。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        run { window.statusBarColor = 0x00000000 }
        // 已有设置文件时先确保 hook 侧（SystemUI 进程）能读到
        makePrefsReadable(this)
        setContent {
            val dark = isSystemInDarkTheme()
            val view = LocalView.current
            SideEffect {
                runCatching {
                    val act = view.context as? Activity
                    val w = act?.window ?: return@runCatching
                    val ctl = WindowCompat.getInsetsController(w, view)
                    ctl.isAppearanceLightStatusBars = !dark
                    ctl.isAppearanceLightNavigationBars = !dark
                }
            }
            val controller = remember(dark) {
                ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light)
            }
            MiuixTheme(controller = controller) { SettingsScreen() }
        }
    }
}

private const val PREFS_NAME = "settings"
private const val KEY_CLOCK_SIZE = "clock_size_dp"
private const val KEY_DATE_SIZE = "date_size_dp"
private const val CLOCK_DP_MIN = 36
private const val CLOCK_DP_MAX = 68
private const val DATE_DP_MIN = 12
private const val DATE_DP_MAX = 24

/**
 * 让 SystemUI 进程能读到本应用的 settings.xml：XSharedPreferences 是
 * 跨进程直读文件，需要 shared_prefs 目录可穿越、文件可读。尽力而为，
 * 失败时 hook 侧会回退默认值并在日志里写明。
 */
private fun makePrefsReadable(context: Context) {
    try {
        val dir = File(context.filesDir.parentFile, "shared_prefs")
        dir.setExecutable(true, false)
        dir.setReadable(true, false)
        File(dir, "$PREFS_NAME.xml").setReadable(true, false)
    } catch (t: Throwable) {
        // 忽略：hook 侧有默认值兜底与诊断日志
    }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var topStyleTheme by remember {
        mutableStateOf(
            prefs.getString(ThemeStore.KEY_TOP_STYLE, StampStore.TOP_STYLE_THEME) !=
                StampStore.TOP_STYLE_ORIGINAL
        )
    }
    var heightMode by remember {
        mutableIntStateOf(prefs.getInt(ThemeStore.KEY_HEIGHT_MODE, StampStore.HEIGHT_FOLLOW))
    }
    var showBackground by remember {
        mutableStateOf(prefs.getBoolean(ThemeStore.KEY_SHOW_BACKGROUND, false))
    }
    var showWidgets by remember {
        mutableStateOf(prefs.getBoolean(ThemeStore.KEY_SHOW_WIDGETS, false))
    }
    var hideCarrier by remember {
        mutableStateOf(prefs.getBoolean(ThemeStore.KEY_HIDE_CARRIER, true))
    }
    var offsetDp by remember {
        mutableIntStateOf(
            prefs.getInt(ThemeStore.KEY_OFFSET_DP, 0)
                .coerceIn(StampStore.OFFSET_DP_MIN, StampStore.OFFSET_DP_MAX)
        )
    }
    var restartStatus by remember { mutableStateOf("") }
    var themes by remember { mutableStateOf(ThemeStore.loadThemes(prefs)) }
    var selectedThemeId by remember {
        mutableStateOf(prefs.getString(ThemeStore.KEY_SELECTED_THEME, "") ?: "")
    }
    var importStatus by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }

    var clockSize by remember {
        mutableIntStateOf(
            prefs.getInt(KEY_CLOCK_SIZE, 54).coerceIn(CLOCK_DP_MIN, CLOCK_DP_MAX)
        )
    }
    var dateSize by remember {
        mutableIntStateOf(
            prefs.getInt(KEY_DATE_SIZE, 16).coerceIn(DATE_DP_MIN, DATE_DP_MAX)
        )
    }

    fun syncStamp() {
        scope.launch(Dispatchers.IO) { ThemeStore.writeStamp(context, prefs) }
    }

    // 存量主题迁移（v2.0.18）：旧版解压在公共目录里的散图会被
    // 图库显示出来；设置页每次打开时在 IO 线程幂等跑一遍——把
    // 散图打包回主题包本体、删散图与其 MediaStore 条目。有主题
    // 被迁移时重写一次 stamp，让 SystemUI 侧随之改从包本体
    // 重载（迁移前它经旧目录兼容读法照常渲染，不受影响）。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val migrated = withContext(Dispatchers.IO) {
            ThemeStore.migrateLegacyThemes(context, prefs)
        }
        if (migrated > 0) {
            themes = ThemeStore.loadThemes(prefs)
            importStatus = "已把 $migrated 个旧主题打包成单文件（图库里的散图已清）"
            syncStamp()
        }
    }

    fun saveTopStyle(theme: Boolean) {
        topStyleTheme = theme
        prefs.edit()
            .putString(
                ThemeStore.KEY_TOP_STYLE,
                if (theme) StampStore.TOP_STYLE_THEME else StampStore.TOP_STYLE_ORIGINAL,
            )
            .apply()
        syncStamp()
    }

    fun saveHeightMode(mode: Int) {
        heightMode = mode
        prefs.edit().putInt(ThemeStore.KEY_HEIGHT_MODE, mode).apply()
        syncStamp()
    }

    fun saveShowBackground(value: Boolean) {
        showBackground = value
        prefs.edit().putBoolean(ThemeStore.KEY_SHOW_BACKGROUND, value).apply()
        syncStamp()
    }

    fun saveShowWidgets(value: Boolean) {
        showWidgets = value
        prefs.edit().putBoolean(ThemeStore.KEY_SHOW_WIDGETS, value).apply()
        syncStamp()
    }

    fun saveHideCarrier(value: Boolean) {
        hideCarrier = value
        prefs.edit().putBoolean(ThemeStore.KEY_HIDE_CARRIER, value).apply()
        syncStamp()
    }

    fun saveOffsetDp(value: Int) {
        if (value == offsetDp) return
        offsetDp = value
        prefs.edit().putInt(ThemeStore.KEY_OFFSET_DP, value).apply()
        syncStamp()
    }

    fun selectTheme(id: String) {
        selectedThemeId = id
        prefs.edit().putString(ThemeStore.KEY_SELECTED_THEME, id).apply()
        syncStamp()
    }

    fun deleteTheme(id: String) {
        scope.launch(Dispatchers.IO) {
            ThemeStore.deleteTheme(context, prefs, id)
            withContext(Dispatchers.Main) {
                themes = ThemeStore.loadThemes(prefs)
                if (selectedThemeId == id) selectedThemeId = ""
                importStatus = "已删除主题"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        importStatus = "正在导入…"
        scope.launch(Dispatchers.IO) {
            val result = ThemeImporter.importTheme(context, uri)
            withContext(Dispatchers.Main) {
                importing = false
                when (result) {
                    is ThemeImporter.Result.Ok -> {
                        val newThemes = ThemeStore.loadThemes(prefs) + result.entry
                        ThemeStore.saveThemes(prefs, newThemes)
                        themes = newThemes
                        selectedThemeId = result.entry.id
                        prefs.edit()
                            .putString(ThemeStore.KEY_SELECTED_THEME, result.entry.id)
                            .apply()
                        importStatus = "已导入并选中「${result.entry.name}」"
                        syncStamp()
                    }
                    is ThemeImporter.Result.Err -> {
                        importStatus = result.message
                    }
                }
            }
        }
    }

    /**
     * 「重启系统界面」（v2.0.12）：把请求时刻写进 restart.txt，
     * SystemUI 侧看守在 1 秒内读到后结束自己进程、被系统拉起，
     * 模块随之重新加载——等同强停系统界面，不依赖 root。点了
     * 立即给反馈、不弹确认框：动作只影响系统界面本身、一两秒
     * 自行恢复，符合少打扰口径。
     */
    fun requestRestart() {
        restartStatus = "已发送重启请求，系统界面马上会重启（通知栏会闪断一两秒）"
        scope.launch(Dispatchers.IO) {
            val ok = PublicStore.writeFile(
                context,
                StampStore.STAMP_REL_DIR,
                StampStore.RESTART_NAME,
                System.currentTimeMillis().toString().toByteArray(Charsets.UTF_8),
            )
            if (!ok) {
                withContext(Dispatchers.Main) {
                    restartStatus = "发送失败，请再点一次"
                }
            }
        }
    }

    fun saveClockSize(value: Int) {
        if (value == clockSize) return
        clockSize = value
        prefs.edit().putInt(KEY_CLOCK_SIZE, value).apply()
        makePrefsReadable(context)
    }

    fun saveDateSize(value: Int) {
        if (value == dateSize) return
        dateSize = value
        prefs.edit().putInt(KEY_DATE_SIZE, value).apply()
        makePrefsReadable(context)
    }

    Scaffold(
        containerColor = MiuixTheme.colorScheme.background,
        topBar = {
            SmallTopAppBar(
                title = "通知中心大时钟",
                color = Color.Transparent,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                SmallTitle(
                    text = "正式版 v${BuildConfig.VERSION_NAME}",
                    insideMargin = PaddingValues(start = 4.dp),
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Column {
                        Text(
                            "在通知栏顶部盖一层自绘覆盖层，按荣耀锁屏主题的样式渲染（数字图集时钟、日期、电量、天气小模块等），主题可导入、可切换。",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            "覆盖层构建或渲染出任何问题都会自动卸下、恢复系统原头部，不会让系统界面崩溃。",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                SmallTitle(
                    text = "顶部样式",
                    insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    SuperSwitch(
                        title = "锁屏主题样式",
                        summary = "开：用导入的锁屏主题渲染顶部；关：系统原头部",
                        checked = topStyleTheme,
                        onCheckedChange = { saveTopStyle(it) },
                    )
                    SuperSwitch(
                        title = "主题背景图",
                        summary = "开：连主题的整幅背景图一起画；关：只画时钟等元素，透出通知栏原背景",
                        checked = showBackground,
                        onCheckedChange = { saveShowBackground(it) },
                    )
                    SuperSwitch(
                        title = "小模块（温度/风向/电量）",
                        summary = "开：连温度、风向、电量小模块一起画；关：只显示时间样式（默认关）",
                        checked = showWidgets,
                        onCheckedChange = { saveShowWidgets(it) },
                    )
                    SuperSwitch(
                        title = "隐藏运营商文字",
                        summary = "开：锁屏、控制中心、通知中心左上角的运营商文字（中国联通等）不显示（默认开）",
                        checked = hideCarrier,
                        onCheckedChange = { saveHideCarrier(it) },
                    )
                }

                SmallTitle(
                    text = "位置",
                    insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Column {
                        SizeSliderRow(
                            title = "垂直偏移",
                            value = offsetDp,
                            min = StampStore.OFFSET_DP_MIN,
                            max = StampStore.OFFSET_DP_MAX,
                            onValueChange = { saveOffsetDp(it) },
                        )
                        Text(
                            "负数上移、正数下移。主题顶部的留白是给锁屏天空的，在通知中心里显空时往上调；最多上移到时钟簇贴顶。",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                SmallTitle(
                    text = "主题管理",
                    insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(vertical = 4.dp),
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !importing) {
                                    importLauncher.launch(arrayOf("*/*"))
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "导入主题文件",
                                    fontSize = 15.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Text(
                                    if (importing) "正在导入…" else "选择 .hnt / .hwt 主题包",
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Text(
                                "›",
                                fontSize = 20.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        if (importStatus.isNotEmpty()) {
                            Text(
                                importStatus,
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        if (themes.isEmpty()) {
                            Text(
                                "还没有导入主题。导入后点主题名即可切换。",
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        for (theme in themes) {
                            val selected = theme.id == selectedThemeId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectTheme(theme.id) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        theme.name,
                                        fontSize = 15.sp,
                                        color = MiuixTheme.colorScheme.onSurface,
                                    )
                                    if (selected) {
                                        Text(
                                            "当前使用",
                                            fontSize = 12.sp,
                                            color = MiuixTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                Text(
                                    "删除",
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier
                                        .clickable { deleteTheme(theme.id) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                }

                SmallTitle(
                    text = "区域高度",
                    insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(vertical = 4.dp),
                ) {
                    Column {
                        HeightModeRow("跟随内容", "高度取所画元素的最下缘", heightMode == 0) {
                            saveHeightMode(0)
                        }
                        HeightModeRow("屏高 1/3", "覆盖层固定占屏幕高度三分之一", heightMode == 1) {
                            saveHeightMode(1)
                        }
                        HeightModeRow("屏高 1/2", "覆盖层固定占屏幕高度二分之一", heightMode == 2) {
                            saveHeightMode(2)
                        }
                    }
                }

                SmallTitle(
                    text = "字号（原系统头部模式）",
                    insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Column {
                        SizeSliderRow(
                            title = "时钟字号",
                            value = clockSize,
                            min = CLOCK_DP_MIN,
                            max = CLOCK_DP_MAX,
                            onValueChange = { saveClockSize(it) },
                        )
                        SizeSliderRow(
                            title = "日期字号",
                            value = dateSize,
                            min = DATE_DP_MIN,
                            max = DATE_DP_MAX,
                            onValueChange = { saveDateSize(it) },
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }

                SmallTitle(
                    text = "系统界面",
                    insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(vertical = 4.dp),
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { requestRestart() }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "重启系统界面",
                                    fontSize = 15.sp,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "等同强停系统界面：字号等需要重载的改动点它即可生效，不用去设置里强停",
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Text(
                                "›",
                                fontSize = 20.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        if (restartStatus.isNotEmpty()) {
                            Text(
                                restartStatus,
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                SmallTitle(
                    text = "说明",
                    insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                )
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Column {
                        Text(
                            "切换主题、改区域高度、调垂直偏移、开关锁屏主题样式、主题背景图与隐藏运营商文字：下次下拉通知栏即生效（约 1 秒内自动刷新），不用强停系统界面。",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            "字号滑杆只对原系统头部模式有效，改完点上方「重启系统界面」即可生效（等同强停，不用重启手机）。",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            "温度/风向小模块读系统天气服务，与锁屏同源（「小模块」开关默认关，打开后才画）；主题里的入场动画只按终态静态呈现，秒数逐秒切换。",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            "在 LSPosed 里停用本模块，再强停一次系统界面，通知中心即恢复原样。诊断日志在 /sdcard/Download/shadeclock_probe.log，主题资产在 /sdcard/Download/HonorShadeClock/themes/。",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 区域高度三档的选择行。 */
@Composable
private fun HeightModeRow(
    title: String,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                summary,
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        if (selected) {
            Text(
                "当前",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

/** 字号滑杆行：标题 + 当前值（dp）+ Slider，拖动按整数 dp 步进、变化即落盘。 */
@Composable
private fun SizeSliderRow(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "${value}dp",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = {
                onValueChange(it.roundToInt().coerceIn(min, max))
            },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

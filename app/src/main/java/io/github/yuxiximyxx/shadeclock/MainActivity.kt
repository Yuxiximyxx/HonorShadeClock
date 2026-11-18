package io.github.yuxiximyxx.shadeclock

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
 * 正式版主界面（v1.1.0 起为设置页）：位置（时钟居中开关）与字号
 * （时钟/日期字号滑杆）两组设置，写入 SharedPreferences「settings」，
 * 由 hook 侧在 SystemUI 进程启动时经 XSharedPreferences 读取。
 * 样式照 MagicPods v2.11 的 MIUIX 写法（MiuixTheme + Scaffold +
 * SmallTopAppBar + Card + SuperSwitch + Slider）。
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
private const val KEY_CENTER_CLOCK = "center_clock"
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
    val prefs = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    var centerClock by remember {
        mutableStateOf(prefs.getBoolean(KEY_CENTER_CLOCK, true))
    }
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

    fun saveCenterClock(checked: Boolean) {
        centerClock = checked
        prefs.edit().putBoolean(KEY_CENTER_CLOCK, checked).apply()
        makePrefsReadable(context)
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
                SmallTitle(text = "正式版 v1.1.1", insideMargin = PaddingValues(start = 4.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Column {
                        Text(
                            "把荣耀 MagicOS 11 通知中心顶部的时钟改成居中显示的大时钟。流量行、右上角铃铛按钮和原动画保持原样。",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            "模块带结构自检：系统版本变化导致头部结构对不上时，居中会自动跳过、只保留放大，不会硬改；居中逻辑万一运行时出错也会立即回退系统原逻辑。",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                SmallTitle(
                    text = "位置",
                    insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    SuperSwitch(
                        title = "时钟居中",
                        summary = "关掉回到原来的左对齐",
                        checked = centerClock,
                        onCheckedChange = { saveCenterClock(it) },
                    )
                }

                SmallTitle(
                    text = "字号",
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
                            "改完设置后，强停一次系统界面（或重启手机）生效：模块在新进程启动时读取设置。",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            "在 LSPosed 里停用本模块，再强停一次系统界面，通知中心即恢复原样。",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            "模块的设置读数、安装结果与字号验读会写一行到 /sdcard/Download/shadeclock_probe.log，排查问题时可看这个文件。",
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

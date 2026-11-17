package io.github.yuxiximyxx.shadeclock

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 探针版主界面：只说明用途与取日志步骤，没有任何可调功能。
 * 样式照 MagicPods v2.11 的 MIUIX 写法（MiuixTheme + Scaffold + SmallTopAppBar + Card）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        run { window.statusBarColor = 0x00000000 }
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
            MiuixTheme(controller = controller) { ProbeHome() }
        }
    }
}

@Composable
private fun ProbeHome() {
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
                SmallTitle(text = "探针版 v0.3.0", insideMargin = PaddingValues(start = 4.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Column {
                        Text(
                            "这个版本不改任何样式，只做一件事：定位荣耀 MagicOS 11 通知中心顶部时钟控件的运行时结构，为正式版把时钟改成居中大时钟提供落点。v0.2.0 改用整棵视图树遍历：每 5 秒把 SystemUI 全部窗口的视图树扫一遍，直接读取控件当前的类名、id、文本、尺寸与位置，不再只依赖 setText。v0.3.0 已确认头部是一个 Compose 容器，本版专门给它做一次图纸测绘（类结构与 Compose 语义树），为正式版选改法。",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            "探针只读取视图信息，绝不修改任何视图属性。",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                SmallTitle(
                    text = "使用步骤",
                    insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Column {
                        Text("1. 覆盖安装本新版 v0.3.0（直接装，不用先卸载旧版）", fontSize = 14.sp)
                        Text("2. 在 LSPosed 里确认模块已启用，作用域仍勾着「系统界面（SystemUI）」", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                        Text("3. 强停一次系统界面，让探针注入新进程", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                        Text("4. 下拉通知中心并保持打开约 10 秒，再关掉", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                        Text("5. 把日志文件发回即可（见下方路径）", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }

                SmallTitle(
                    text = "日志输出",
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
                            "/sdcard/Download/shadeclock_probe.log",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            "LSPosed 日志里只记每轮的标题行，控件明细都在这个文件里（搜 probe v2 pass）。",
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

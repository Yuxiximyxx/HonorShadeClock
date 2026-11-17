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
                SmallTitle(text = "探针版 v0.1.0", insideMargin = PaddingValues(start = 4.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Column {
                        Text(
                            "这个版本不改任何样式，只做一件事：定位荣耀 MagicOS 11 通知中心顶部时钟控件的运行时结构（类名、id、字号、父级布局），为正式版把时钟改成居中大时钟提供落点。",
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
                        Text("1. 在 LSPosed 里启用本模块，作用域勾选「系统界面（SystemUI）」", fontSize = 14.sp)
                        Text("2. 强停一次 SystemUI，让探针注入新进程", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                        Text("3. 下拉一次通知中心，等时钟跳过一分钟", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                        Text("4. 把日志文件发回即可（见下方路径）", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
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
                            "同时也会写一份到 LSPosed 日志（搜 ShadeClockProbe）。",
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

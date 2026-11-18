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
 * 正式版主界面：说明功能效果、生效方式与关闭办法，没有可调开关。
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
            MiuixTheme(controller = controller) { HomeScreen() }
        }
    }
}

@Composable
private fun HomeScreen() {
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
                SmallTitle(text = "正式版 v1.0.0", insideMargin = PaddingValues(start = 4.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Column {
                        Text(
                            "把荣耀 MagicOS 11 通知中心顶部的时钟改成居中显示的大时钟：时钟和日期整组水平居中，时钟字号由 40dp 放大到 54dp，日期字号由 14dp 放大到 16dp。流量行、右上角铃铛按钮和原动画保持原样。",
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
                    text = "生效方式",
                    insideMargin = PaddingValues(start = 4.dp, top = 16.dp),
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 20.dp,
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Column {
                        Text("1. 在 LSPosed 里启用本模块，作用域勾「系统界面（SystemUI）」", fontSize = 14.sp)
                        Text("2. 强停一次系统界面（或重启手机），让模块注入新进程", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                        Text("3. 下拉通知中心即可看到居中大时钟", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }

                SmallTitle(
                    text = "关闭办法",
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
                            "在 LSPosed 里停用本模块，再强停一次系统界面（或重启手机），通知中心即恢复原样。",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                        Text(
                            "模块的安装结果与字号验读会写一行到 /sdcard/Download/shadeclock_probe.log，排查问题时可看这个文件。",
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

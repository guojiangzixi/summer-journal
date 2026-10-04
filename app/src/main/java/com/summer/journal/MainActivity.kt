package com.summer.journal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.summer.journal.ui.AppViewModel
import com.summer.journal.ui.MainScaffold
import com.summer.journal.ui.theme.SummerJournalTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 入口。只做三件事：装启动页、套主题、交给 MainScaffold。
 *
 * ★ 背景渲染**不在这里**，放在 MainScaffold 里 ——
 *   因为「应用范围」这个设置（全部页面 / 仅日历页）需要知道当前在哪个 Tab，
 *   而 Tab 状态属于 MainScaffold。放这里就得把 Tab 状态往上提，反而更绕。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前调用，否则会闪一下白屏
        installSplashScreen()

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val appViewModel: AppViewModel = hiltViewModel()
            val backgroundTheme by appViewModel.backgroundTheme.collectAsStateWithLifecycle()

            SummerJournalTheme(backgroundTheme = backgroundTheme) {
                MainScaffold(appViewModel = appViewModel)
            }
        }
    }
}

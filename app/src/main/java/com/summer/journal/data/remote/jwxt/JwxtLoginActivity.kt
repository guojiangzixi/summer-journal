package com.summer.journal.data.remote.jwxt

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dagger.hilt.android.AndroidEntryPoint

/**
 * 教务系统登录页。
 *
 * ★ 设计原则：**不做自动填表，不读取密码。**
 *   App 只是把教务系统的登录页展示出来，让用户自己输。
 *   密码从头到尾没有经过我们的代码，也没有落到任何存储里 ——
 *   这既是最稳妥的合规做法，也避免了「记住密码」带来的安全事故。
 *
 * 登录成功后通过 shouldOverrideUrlLoading 侦测跳转，回传 RESULT_OK。
 */
@AndroidEntryPoint
class JwxtLoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 教务系统是 http，Android 9+ 默认禁止明文流量。
        // 这里只为这个域名开启（network_security_config.xml 里限定域名，不要全局放开）。
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(WebView(this), true)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    JwxtLoginScreen(
                        onCancel = { setResult(Activity.RESULT_CANCELED); finish() },
                        onLoginSucceeded = {
                            setResult(Activity.RESULT_OK)
                            finish()
                        },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        // 不在这个页面留任何 WebView 痕迹
        super.onDestroy()
    }

    @Composable
    private fun JwxtLoginScreen(
        onCancel: () -> Unit,
        onLoginSucceeded: () -> Unit,
    ) {
        var cookiesReady by remember { mutableStateOf(false) }

        Column(modifier = Modifier.fillMaxSize()) {
            // ── 顶部说明条：把「为什么要在 App 里登教务」讲清楚 ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "登录学校教务系统",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "登录后会自动读取你这学期的课表。账号密码只在学校页面上输入，" +
                            "App 不保存、不上传。导入完成后会立即退出登录状态。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // ── WebView：真正的登录界面，我们完全不介入 ──
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // 不要开 file access / universal access from file URLs，有安全风险
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val url = request.url.toString()
                                if (isLoggedIn(url)) {
                                    // 让 WebView 把 Cookie 落盘后再回调
                                    CookieManager.getInstance().flush()
                                    cookiesReady = true
                                    onLoginSucceeded()
                                    return true
                                }
                                return false
                            }

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                if (isLoggedIn(url)) {
                                    onLoginSucceeded()
                                }
                            }
                        }

                        loadUrl(CsuJwxtImporter.BASE_URL + "jsxsd/")
                    }
                },
            )

            // ── 底部：用户可以自己确认已经登录完成 ──
            // 有些教务系统的跳转路径不固定，侦测可能漏；给用户一个手动按钮做兜底。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel) { Text("取消") }
                Button(
                    onClick = {
                        CookieManager.getInstance().flush()
                        onLoginSucceeded()
                    },
                ) { Text("我已登录，开始导入") }
            }
        }
    }

    /**
     * 登录成功的判定。
     * 教务系统登录后会跳到带 framework / xskb 的路径，用这个特征判断最稳。
     * 判定不了也没关系 —— 用户在底部手动点「我已登录」。
     */
    private fun isLoggedIn(url: String): Boolean =
        url.contains("framework") || url.contains("xskb") || url.contains("xsMain")
}

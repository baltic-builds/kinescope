package com.kinescope.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Patch 39: describes one site Kinescope can keep a signed-in web session for. [WebSessionLoginScreen]
 * is the only sign-in screen; it holds no site-specific logic, so adding or changing a site means
 * adding one of these (this replaces two near-identical screens, one per site).
 */
internal class WebSessionSite(
    val instructionsRes: Int,
    val capturedRes: Int,
    val notDetectedRes: Int,
    val startUrl: String,
    /** Google refuses sign-in from the stock WebView user agent; see the note in the screen. */
    val stripWebViewMarker: Boolean,
    /** True: hand non-web links (intent://, app deep links) to Android. False: ignore them. */
    val openOtherSchemesExternally: Boolean,
    /** Stores the WebView's cookies for yt-dlp; true when a signed-in session was found. */
    val capture: (Context, WebView?) -> Boolean
)

// Opens straight on Google's sign-in form instead of the YouTube homepage, so the user does not
// have to find "Sign in" themselves.
internal val YouTubeSessionSite = WebSessionSite(
    instructionsRes = R.string.youtube_login_instructions,
    capturedRes = R.string.youtube_session_captured,
    notDetectedRes = R.string.youtube_session_capture_uncertain,
    startUrl = "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F",
    stripWebViewMarker = true,
    openOtherSchemesExternally = true,
    capture = { context, webView ->
        YouTubeAuth.captureCurrentSession(
            context = context,
            userAgent = webView?.settings?.userAgentString
        ).looksSignedIn
    }
)

// After signing in Instagram continues to its home feed (and may show "save login info" or
// notification prompts); the user taps "Use this session" once the feed is visible. Instagram
// tries to hand the page to its own app (intent:// or instagram://); that is ignored so the
// user stays in Kinescope.
internal val InstagramSessionSite = WebSessionSite(
    instructionsRes = R.string.instagram_login_instructions,
    capturedRes = R.string.instagram_session_captured,
    notDetectedRes = R.string.instagram_session_capture_uncertain,
    startUrl = "https://www.instagram.com/accounts/login/",
    stripWebViewMarker = false,
    openOtherSchemesExternally = false,
    capture = { context, _ -> InstagramAuth.captureCurrentSession(context).looksSignedIn }
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun WebSessionLoginScreen(site: WebSessionSite, onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var status by remember { mutableStateOf("") }

    BackHandler(enabled = true) {
        val webView = webViewRef
        if (webView != null && webView.canGoBack()) webView.goBack() else onBack()
    }

    DisposableEffect(Unit) {
        onDispose { webViewRef?.destroy() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(site.instructionsRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val signedIn = site.capture(context, webViewRef)
                status = context.getString(if (signedIn) site.capturedRes else site.notDetectedRes)
                if (signedIn) onSaved()
            }) {
                Text(stringResource(R.string.session_use))
            }
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.cancel)) }
        }
        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(8.dp))
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { webContext ->
                WebView(webContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.safeBrowsingEnabled = true
                    if (site.stripWebViewMarker) {
                        // Google rejects sign-in from the stock WebView user agent: it contains a
                        // "; wv" marker that Google's login explicitly detects and blocks with
                        // "This browser or app may not be secure" (disallowed_useragent). Stripping
                        // just that marker -- keeping the real device/Android/Chrome version as-is
                        // -- is the documented minimal fix (see CHANGELOG.md's Patch 31 entry for
                        // sources); a made-up user agent would be both less reliable and less honest.
                        // Instagram is left on the stock agent: nothing says it needs this, and if a
                        // device shows it refusing the embedded browser that is a finding to record.
                        settings.userAgentString = settings.userAgentString
                            .replace("; wv)", ")")
                            .replace("; wv ", " ")
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val uri = request?.url ?: return false
                            if (uri.scheme == "http" || uri.scheme == "https") return false
                            if (site.openOtherSchemesExternally) {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                }
                            }
                            return true
                        }
                    }
                    loadUrl(site.startUrl)
                    webViewRef = this
                }
            }
        )
    }
}

package io.fitgymlog.app.ui.exercise

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Plays a YouTube form video in place, in the card its thumbnail was in, so checking a lift mid-workout doesn't leave
 * the workout. YouTube's own full-screen button still fills the screen; back or its button returns to the card.
 */
@Composable
fun InlineVideoPlayer(youtubeId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var fullscreen by remember { mutableStateOf<View?>(null) }
    val webView = remember(youtubeId) {
        createPlayer(context, onShow = { fullscreen = it }, onHide = { fullscreen = null })
            .apply { loadUrl(embedUrl(youtubeId), mapOf("Referer" to APP_REFERRER)) }
    }
    DisposableEffect(webView) {
        onDispose {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { webView.onPause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { webView.onResume() }

    AndroidView(factory = { webView }, modifier = modifier.background(Color.Black))

    fullscreen?.let { view ->
        Dialog(
            onDismissRequest = { webView.webChromeClient?.onHideCustomView() },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize().background(Color.Black))
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createPlayer(context: Context, onShow: (View) -> Unit, onHide: () -> Unit): WebView =
    WebView(context).apply {
        setBackgroundColor(android.graphics.Color.BLACK)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        webChromeClient = object : WebChromeClient() {
            private var callback: CustomViewCallback? = null

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                this.callback = callback
                onShow(view)
            }

            override fun onHideCustomView() {
                callback?.onCustomViewHidden()
                callback = null
                onHide()
            }
        }
    }

/**
 * The embed loads straight into the WebView rather than in a frame on a page of our own, which stayed empty: YouTube turns
 * away embeds that don't say which app shows them (error 153), and an app names itself as an https origin.
 */
private const val APP_REFERRER = "https://io.fitgymlog.app"

private fun embedUrl(youtubeId: String): String =
    "https://www.youtube-nocookie.com/embed/$youtubeId?autoplay=1&playsinline=1&rel=0&modestbranding=1"

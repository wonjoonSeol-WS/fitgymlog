package io.fitgymlog.app.ui.exercise

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.fitgymlog.app.R

/**
 * Plays a YouTube form video over the app, so checking a lift mid-workout doesn't leave the workout.
 * Videos whose owner turned off embedding show YouTube's own error, so the YouTube app stays one tap away.
 */
@Composable
fun VideoPlayerDialog(url: String, youtubeId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var fullscreen by remember { mutableStateOf<View?>(null) }
    val webView = remember {
        createPlayer(context, onShow = { fullscreen = it }, onHide = { fullscreen = null })
            .apply { loadDataWithBaseURL(EMBED_ORIGIN, embedPage(youtubeId), "text/html", "utf-8", null) }
    }
    DisposableEffect(webView) {
        onDispose {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { webView.onPause() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { webView.onResume() }

    Dialog(
        onDismissRequest = { if (fullscreen != null) webView.webChromeClient?.onHideCustomView() else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(16f / 9f),
                )
                Row(Modifier.align(Alignment.TopEnd).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { openLink(context, url) }) {
                        Text(stringResource(R.string.open_in_youtube), color = Color.White)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, stringResource(R.string.close), tint = Color.White)
                    }
                }
            }
            fullscreen?.let { view ->
                AndroidView(factory = { view }, modifier = Modifier.fillMaxSize().background(Color.Black))
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createPlayer(context: android.content.Context, onShow: (View) -> Unit, onHide: () -> Unit): WebView =
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

/** YouTube refuses embeds that arrive without a referrer, so the page is given an https origin of its own. */
private const val EMBED_ORIGIN = "https://io.fitgymlog.app/"

private fun embedPage(youtubeId: String): String = """
    <!doctype html>
    <html><head>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{position:absolute;inset:0;width:100%;height:100%;border:0}</style>
    </head><body>
    <iframe src="https://www.youtube-nocookie.com/embed/$youtubeId?autoplay=1&playsinline=1&rel=0&modestbranding=1"
        allow="autoplay; encrypted-media; picture-in-picture; fullscreen" allowfullscreen></iframe>
    </body></html>
""".trimIndent()

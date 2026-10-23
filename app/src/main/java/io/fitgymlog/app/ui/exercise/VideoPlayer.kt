package io.fitgymlog.app.ui.exercise

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.FullscreenListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

/**
 * Plays a YouTube form video in place, in the card its thumbnail was in, so checking a lift mid-workout doesn't leave
 * the workout. The player is android-youtube-player's wrapper of YouTube's IFrame Player API, which names the app to
 * YouTube as embeds now require; it pauses with the screen, and its full-screen button fills the screen until back.
 */
@Composable
fun InlineVideoPlayer(youtubeId: String, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var fullscreen by remember { mutableStateOf<Pair<View, () -> Unit>?>(null) }

    AndroidView(
        modifier = modifier.background(Color.Black),
        factory = { context ->
            YouTubePlayerView(context).apply {
                enableAutomaticInitialization = false
                lifecycle.addObserver(this)
                addFullscreenListener(
                    object : FullscreenListener {
                        override fun onEnterFullscreen(fullscreenView: View, exitFullscreen: () -> Unit) {
                            fullscreen = fullscreenView to exitFullscreen
                        }

                        override fun onExitFullscreen() {
                            fullscreen = null
                        }
                    },
                )
                val options = IFramePlayerOptions.Builder(context).controls(1).fullscreen(1).rel(0).ivLoadPolicy(3).build()
                initialize(
                    object : AbstractYouTubePlayerListener() {
                        override fun onReady(youTubePlayer: YouTubePlayer) {
                            youTubePlayer.loadVideo(youtubeId, 0f)
                        }
                    },
                    options,
                )
            }
        },
        onRelease = { player ->
            lifecycle.removeObserver(player)
            player.release()
        },
    )

    fullscreen?.let { (view, exit) ->
        Dialog(onDismissRequest = exit, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            AndroidView(
                factory = { view.also { (it.parent as? ViewGroup)?.removeView(it) } },
                modifier = Modifier.fillMaxSize().background(Color.Black),
            )
        }
    }
}

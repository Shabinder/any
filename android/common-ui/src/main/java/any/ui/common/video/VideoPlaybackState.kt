package any.ui.common.video

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import any.base.util.MB
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.database.StandaloneDatabaseProvider
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.FileDataSource
import com.google.android.exoplayer2.upstream.cache.Cache
import com.google.android.exoplayer2.upstream.cache.CacheDataSink
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor
import com.google.android.exoplayer2.upstream.cache.SimpleCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max

private const val DEFAULT_TICK_INTERVAL = 100L

@Composable
fun rememberVideoPlaybackState(
    uri: Uri,
    progressTickInterval: Long = DEFAULT_TICK_INTERVAL,
): VideoPlaybackState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(uri) {
        VideoPlaybackState(context, scope, progressTickInterval, uri)
    }

    SideEffect {
        state.tickInterval = progressTickInterval
    }

    DisposableEffect(state) {
        PlaybackStateManager.add(state)
        onDispose { PlaybackStateManager.remove(state) }
    }

    return state
}

private object PlaybackStateManager {
    private val states = mutableSetOf<VideoPlaybackState>()

    fun add(state: VideoPlaybackState) {
        states.add(state)
    }

    fun remove(state: VideoPlaybackState) {
        states.remove(state)
    }

    fun onPlay(state: VideoPlaybackState) {
        for (s in states) {
            if (s != state) {
                // Pause other players
                s.pause()
            }
        }
    }
}

@Stable
class VideoPlaybackState(
    private val context: Context,
    private val tickScope: CoroutineScope,
    internal var tickInterval: Long,
    val uri: Uri,
) : Player.Listener {
    private var player: ExoPlayer? = null

    var isRenderedFirstFrame by mutableStateOf(false)
        private set

    var isPlayed by mutableStateOf(false)
        private set

    var isPlaying by mutableStateOf(false)
        private set

    var isBuffering by mutableStateOf(false)
        private set

    var error: PlaybackException? by mutableStateOf(null)
        private set

    var duration: Long by mutableStateOf(-0L)
        private set

    var progress: Float by mutableStateOf(-1f)
        private set

    private var _isMuted by mutableStateOf(false)
    var isMuted: Boolean
        get() = _isMuted
        set(value) {
            withPlayer { volume = if (value) 0f else 1f }
            _isMuted = value
        }

    private var tickJob: Job? = null

    private val cacheDataSourceFactory by lazy {
        val cache = ExoCache.get(context)
        val cacheSink = CacheDataSink.Factory().setCache(cache)
        val upstreamFactory = DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory())
        CacheDataSource.Factory()
            .setCache(cache)
            .setCacheWriteDataSinkFactory(cacheSink)
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        this.isPlaying = isPlaying
    }

    override fun onRenderedFirstFrame() {
        this.isRenderedFirstFrame = true
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        duration = player?.duration ?: 0L
        when (playbackState) {
            Player.STATE_BUFFERING -> {
                isBuffering = true
            }

            Player.STATE_ENDED -> {
                isBuffering = false
            }

            Player.STATE_IDLE -> {
                isBuffering = false
            }

            Player.STATE_READY -> {
                isBuffering = false
            }
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        this.error = error
    }

    fun attachToView(view: StyledPlayerView) {
        view.player = player
    }

    fun detachFromView(view: StyledPlayerView) {
        release()
        view.player = null
    }

    fun play() {
        error = null
        isPlayed = true
        PlaybackStateManager.onPlay(this)
        withPlayer {
            if (currentMediaItem == null) {
                val mediaSource = ProgressiveMediaSource.Factory(cacheDataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uri))
                setMediaSource(mediaSource)
                repeatMode = Player.REPEAT_MODE_ONE
                prepare()
                playWhenReady = true
            }
            play()
        }
        tickProgress()
    }

    fun pause() {
        withPlayer { pause() }
        tickJob?.cancel()
    }

    fun seek(progress: Float) {
        if (duration <= 0) return
        seek((duration * progress).toLong())
    }

    fun seek(position: Long) {
        withPlayer { seekTo(position) }
    }

    fun updateProgress() {
        if (player == null) return
        withPlayer {
            progress = if (duration > 0) {
                currentPosition.toFloat() / duration
            } else {
                -1f
            }
        }
    }

    private fun tickProgress() {
        tickJob = tickScope.launch {
            while (player != null) {
                updateProgress()
                delay(max(50L, tickInterval))
            }
        }
    }

    fun init() {
        if (player != null) {
            return
        }
        val player = ExoPlayer.Builder(context).build()
        player.addListener(this)
        this.player = player
    }

    fun release() {
        if (player == null) return
        withPlayer {
            removeListener(this@VideoPlaybackState)
            release()
        }
        tickJob?.cancel()
        isPlayed = false
        isBuffering = false
        isPlaying = false
        player = null
    }

    private inline fun <T> withPlayer(block: ExoPlayer.() -> T): T {
        val player = checkNotNull(player) { "ExoPlayer is not initialized" }
        return block(player)
    }
}

private object ExoCache {
    private const val VIDEO_CACHE_DIR = "exoCache"

    private val MAX_CACHE_SIZE = 500.MB

    @Volatile
    private var cache: Cache? = null

    fun get(context: Context): Cache {
        return cache ?: synchronized(VideoPlaybackState::class.java) {
            cache ?: SimpleCache(
                File(context.cacheDir, VIDEO_CACHE_DIR),
                LeastRecentlyUsedCacheEvictor(MAX_CACHE_SIZE),
                StandaloneDatabaseProvider(context)
            ).also {
                cache = it
            }
        }
    }
}
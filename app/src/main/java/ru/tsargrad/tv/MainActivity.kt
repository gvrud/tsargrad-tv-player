package ru.tsargrad.tv

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.tsargrad.tv.databinding.ActivityMainBinding

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PlayerPrefs
    private val resolver = StreamResolver()
    private val handler = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var source = StreamSource.RUTUBE
    private var audioOnly = false
    private var playJob: Job? = null
    private var hasMedia = false
    private var playerErrorCount = 0
    private var controlsVisible = true

    private val hideControlsRunnable = Runnable { setControlsVisible(false) }

    private val restartRunnable = Runnable {
        if (!isFinishing && !isDestroyed) {
            startStream()
        }
    }

    private val pulseAnimator: ObjectAnimator by lazy {
        ObjectAnimator.ofFloat(binding.audioPulse, View.ALPHA, 0.25f, 0.8f).apply {
            duration = 1_800L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
    }

    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> showLoading(true)
                Player.STATE_READY -> {
                    showLoading(false)
                    hideError()
                    playerErrorCount = 0
                    updateQualityBadge()
                }

                Player.STATE_ENDED -> {
                    showLoading(false)
                    hasMedia = false
                    restartAfter(1_500L)
                }

                Player.STATE_IDLE -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                showLoading(false)
                hideError()
            }
            updateQualityBadge()
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateQualityBadge()
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlayerError()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PlayerPrefs(this)
        source = prefs.source
        audioOnly = prefs.audioOnly

        setupInsets()
        setupControls()
        createPlayer()
        updateQualityBadge()
        enterFullscreen()
        setControlsVisible(true)
    }

    override fun onStart() {
        super.onStart()
        enterFullscreen()
        if (player == null) {
            createPlayer()
        }
        if (hasMedia) {
            player?.seekToDefaultPosition()
            player?.play()
        } else {
            startStream()
        }
    }

    override fun onStop() {
        super.onStop()
        playJob?.cancel()
        player?.pause()
        handler.removeCallbacks(restartRunnable)
    }

    override fun onDestroy() {
        playJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        pulseAnimator.cancel()
        binding.playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enterFullscreen()
        }
    }

    private fun createPlayer() {
        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setMaxVideoSize(1920, 1080)
                    .setMaxVideoBitrate(9_000_000)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .build()
            )
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 50_000, 2_000, 4_000)
            .build()
        val exoPlayer = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        exoPlayer.setWakeMode(C.WAKE_MODE_NETWORK)
        exoPlayer.playWhenReady = true
        exoPlayer.addListener(playerListener)
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly)
            .build()

        player = exoPlayer
        binding.playerView.player = exoPlayer
    }

    private fun setupControls() {
        binding.sourceGroup.check(
            if (source == StreamSource.RUTUBE) R.id.sourceRutube else R.id.sourceVk
        )
        binding.sourceGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val next = if (checkedId == R.id.sourceVk) StreamSource.VK else StreamSource.RUTUBE
            if (next != source) {
                source = next
                prefs.source = next
                updateErrorSwitchLabel()
                startStream(resetErrors = true)
                Toast.makeText(
                    this,
                    getString(R.string.toast_source, sourceLabel()),
                    Toast.LENGTH_SHORT
                ).show()
            }
            scheduleControlsHide()
        }

        binding.audioToggle.setOnClickListener {
            setAudioOnly(!audioOnly)
            scheduleControlsHide()
        }

        binding.reloadButton.setOnClickListener {
            startStream(resetErrors = true)
            scheduleControlsHide()
        }

        binding.touchLayer.setOnClickListener {
            setControlsVisible(!controlsVisible)
        }

        binding.topBar.setOnClickListener { scheduleControlsHide() }
        binding.bottomPanel.setOnClickListener { scheduleControlsHide() }

        binding.errorRetry.setOnClickListener {
            startStream(resetErrors = true)
        }

        binding.errorSwitch.setOnClickListener {
            val next = if (source == StreamSource.RUTUBE) StreamSource.VK else StreamSource.RUTUBE
            source = next
            prefs.source = next
            binding.sourceGroup.check(
                if (next == StreamSource.RUTUBE) R.id.sourceRutube else R.id.sourceVk
            )
            updateErrorSwitchLabel()
            startStream(resetErrors = true)
        }

        updateAudioUi(false)
        updateErrorSwitchLabel()
    }

    private fun startStream(resetErrors: Boolean = false) {
        val currentPlayer = player ?: return
        playJob?.cancel()
        handler.removeCallbacks(restartRunnable)
        if (resetErrors) {
            playerErrorCount = 0
        }
        hasMedia = false

        playJob = lifecycleScope.launch {
            showLoading(true)
            hideError()
            var failure: Throwable? = null

            repeat(3) { attempt ->
                if (attempt > 0) {
                    delay(1_200L * attempt)
                    if (!isActive) return@launch
                }
                try {
                    val stream = resolver.resolve(source)
                    if (!isActive || player !== currentPlayer) return@launch
                    currentPlayer.setMediaSource(buildMediaSource(stream))
                    currentPlayer.prepare()
                    currentPlayer.play()
                    hasMedia = true
                    return@launch
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    failure = error
                }
            }

            if (!isActive) return@launch
            showLoading(false)
            showError(messageFor(failure))
        }
    }

    private fun buildMediaSource(stream: ResolvedStream): HlsMediaSource {
        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent(stream.userAgent)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to stream.referer,
                    "Origin" to stream.referer.removeSuffix("/")
                )
            )
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)

        return HlsMediaSource.Factory(dataSource)
            .setAllowChunklessPreparation(true)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(4))
            .createMediaSource(MediaItem.fromUri(stream.url))
    }

    private fun setAudioOnly(enabled: Boolean) {
        if (audioOnly == enabled) return
        audioOnly = enabled
        prefs.audioOnly = enabled
        player?.let { currentPlayer ->
            currentPlayer.trackSelectionParameters = currentPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, enabled)
                .build()
        }
        updateAudioUi(true)
        updateQualityBadge()
        scheduleControlsHide()
    }

    private fun updateAudioUi(animate: Boolean) {
        binding.audioToggle.text = getString(
            if (audioOnly) R.string.watch_video else R.string.listen_audio
        )
        binding.audioToggle.setIconResource(
            if (audioOnly) R.drawable.ic_videocam else R.drawable.ic_headphones
        )

        val accent = ContextCompat.getColor(this, R.color.gold)
        val neutral = ContextCompat.getColor(this, R.color.white_dim)
        val current = if (audioOnly) accent else neutral
        binding.audioToggle.setTextColor(current)
        binding.audioToggle.iconTint = ColorStateList.valueOf(current)
        binding.audioToggle.strokeColor = ColorStateList.valueOf(
            if (audioOnly) accent else ContextCompat.getColor(this, R.color.stroke_soft)
        )
        binding.audioToggle.backgroundTintList = ColorStateList.valueOf(
            if (audioOnly) ContextCompat.getColor(this, R.color.gold_soft) else Color.TRANSPARENT
        )

        if (audioOnly) {
            if (!binding.audioMode.isVisible) {
                binding.audioMode.alpha = 0f
                binding.audioMode.isVisible = true
                binding.audioMode.animate()
                    .alpha(1f)
                    .setDuration(if (animate) 220L else 0L)
                    .start()
            }
            if (!pulseAnimator.isStarted) {
                pulseAnimator.start()
            } else {
                pulseAnimator.resume()
            }
        } else {
            if (pulseAnimator.isStarted) {
                pulseAnimator.pause()
            }
            if (binding.audioMode.isVisible) {
                binding.audioMode.animate()
                    .alpha(0f)
                    .setDuration(if (animate) 180L else 0L)
                    .withEndAction { binding.audioMode.isVisible = false }
                    .start()
            }
        }
    }

    private fun updateQualityBadge() {
        if (audioOnly) {
            binding.qualityBadge.text = getString(R.string.quality_audio)
            return
        }
        val currentPlayer = player ?: return
        var height = 0
        val groups = currentPlayer.currentTracks.groups
        for (group in groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (index in 0 until group.length) {
                if (group.isTrackSelected(index)) {
                    height = group.getTrackFormat(index).height
                    break
                }
            }
            if (height > 0) break
        }
        binding.qualityBadge.text = if (height > 0) {
            getString(R.string.quality_auto_with, height)
        } else {
            getString(R.string.quality_auto)
        }
    }

    private fun handlePlayerError() {
        showLoading(false)
        hasMedia = false
        if (isFinishing || isDestroyed) return
        playerErrorCount++
        if (playerErrorCount <= 2) {
            restartAfter(1_500L * playerErrorCount)
        } else {
            showError(getString(R.string.error_playback, sourceLabel()))
        }
    }

    private fun restartAfter(delayMs: Long) {
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, delayMs)
    }

    private fun showLoading(show: Boolean) {
        binding.loadingBox.isVisible = show
        if (show) {
            binding.errorCard.isVisible = false
        }
    }

    private fun hideError() {
        binding.errorCard.isVisible = false
    }

    private fun showError(message: String) {
        binding.errorMessage.text = message
        binding.errorCard.isVisible = true
        binding.loadingBox.isVisible = false
        updateErrorSwitchLabel()
        setControlsVisible(true, autoHide = false)
    }

    private fun messageFor(error: Throwable?): String {
        if (!isNetworkAvailable()) {
            return getString(R.string.error_no_network)
        }
        return when (error) {
            is StreamResolveException -> when (error.reason) {
                StreamResolveException.Reason.AUTH_REQUIRED -> getString(R.string.error_vk_auth)
                StreamResolveException.Reason.NO_STREAM ->
                    getString(R.string.error_no_stream, sourceLabel())

                StreamResolveException.Reason.NETWORK ->
                    getString(R.string.error_source, sourceLabel())
            }

            is PlaybackException -> getString(R.string.error_playback, sourceLabel())
            else -> getString(R.string.error_generic)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun sourceLabel(target: StreamSource = source): String = getString(
        when (target) {
            StreamSource.RUTUBE -> R.string.source_rutube
            StreamSource.VK -> R.string.source_vk
        }
    )

    private fun updateErrorSwitchLabel() {
        val other = if (source == StreamSource.RUTUBE) StreamSource.VK else StreamSource.RUTUBE
        binding.errorSwitch.text = getString(R.string.error_switch, sourceLabel(other))
    }

    private fun setupInsets() {
        val topSide = resources.getDimensionPixelSize(R.dimen.top_bar_side_padding)
        val topPadding = resources.getDimensionPixelSize(R.dimen.top_bar_top_padding)
        val topBottom = resources.getDimensionPixelSize(R.dimen.top_bar_bottom_padding)
        val panelSide = resources.getDimensionPixelSize(R.dimen.panel_side_margin)
        val panelBottom = resources.getDimensionPixelSize(R.dimen.panel_bottom_margin)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val side = maxOf(cutout.left, cutout.right)

            binding.topBar.updatePadding(
                left = topSide + side,
                right = topSide + side,
                top = topPadding + maxOf(cutout.top, bars.top),
                bottom = topBottom
            )

            val params = binding.bottomPanel.layoutParams as ViewGroup.MarginLayoutParams
            params.leftMargin = panelSide + side
            params.rightMargin = panelSide + side
            params.bottomMargin = panelBottom + maxOf(cutout.bottom, bars.bottom)
            binding.bottomPanel.layoutParams = params
            insets
        }
    }

    private fun setControlsVisible(visible: Boolean, autoHide: Boolean = true) {
        if (!visible && binding.errorCard.isVisible) return
        if (visible == controlsVisible && binding.controls.isVisible == visible) {
            if (visible) {
                if (autoHide) {
                    scheduleControlsHide()
                } else {
                    handler.removeCallbacks(hideControlsRunnable)
                }
            }
            return
        }

        controlsVisible = visible
        handler.removeCallbacks(hideControlsRunnable)
        binding.controls.animate().cancel()

        if (visible) {
            binding.controls.isVisible = true
            binding.controls.animate().alpha(1f).setDuration(180L).start()
            if (autoHide) {
                scheduleControlsHide()
            }
        } else {
            binding.controls.animate()
                .alpha(0f)
                .setDuration(220L)
                .withEndAction {
                    if (!controlsVisible) {
                        binding.controls.isVisible = false
                    }
                }
                .start()
        }
    }

    private fun scheduleControlsHide() {
        handler.removeCallbacks(hideControlsRunnable)
        if (binding.errorCard.isVisible) return
        handler.postDelayed(hideControlsRunnable, 4_500L)
    }

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

package com.salvia.salviabrowxer.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.salvia.salviabrowxer.core.MediaFormatters
import com.salvia.salviabrowxer.data.DownloadItemRecord
import com.salvia.salviabrowxer.data.DownloadState
import com.salvia.salviabrowxer.data.PlaybackOrder
import com.salvia.salviabrowxer.ui.SalviaUiState
import com.salvia.salviabrowxer.ui.theme.SalviaBlack
import com.salvia.salviabrowxer.ui.theme.SalviaBorder
import com.salvia.salviabrowxer.ui.theme.SalviaCyan
import com.salvia.salviabrowxer.ui.theme.SalviaDeepCosmic
import com.salvia.salviabrowxer.ui.theme.SalviaElevated
import com.salvia.salviabrowxer.ui.theme.SalviaError
import com.salvia.salviabrowxer.ui.theme.SalviaGold
import com.salvia.salviabrowxer.ui.theme.SalviaIris
import com.salvia.salviabrowxer.ui.theme.SalviaIrisContainer
import com.salvia.salviabrowxer.ui.theme.SalviaMonoFontFamily
import com.salvia.salviabrowxer.ui.theme.SalviaPearl
import com.salvia.salviabrowxer.ui.theme.SalviaSilverMid
import com.salvia.salviabrowxer.ui.theme.SalviaSurface
import kotlinx.coroutines.delay

@Composable
fun MediaPlayerScreen(
    state: SalviaUiState,
    t: (String, Array<out Pair<String, Any?>>) -> String,
    resolvePlayableUri: (DownloadItemRecord) -> Uri?,
    onBack: () -> Unit,
    onSelectItem: (String) -> Unit,
    onCyclePlaybackOrder: () -> Unit,
    onDeleteItem: (String) -> Unit,
    onEnterPip: () -> Unit
) {
    val context = LocalContext.current
    val playlist = remember(state.downloads, state.isLockedFolderUnlocked) {
        state.downloads.filter { d ->
            d.state == DownloadState.COMPLETED &&
                (!d.isLocked || state.isLockedFolderUnlocked) &&
                !d.fileName.endsWith(".apk", ignoreCase = true)
        }
    }
    val currentItem = remember(playlist, state.playerDownloadId) {
        playlist.firstOrNull { it.id == state.playerDownloadId } ?: playlist.firstOrNull()
    }

    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    var isMuted by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var isFullscreen by remember { mutableStateOf(false) }
    var showFullscreenControls by remember { mutableStateOf(true) }
    var showPlaylist by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var errorKey by remember { mutableStateOf<String?>(null) }
    var reloadNonce by remember { mutableLongStateOf(0L) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) {
        var cur: Context? = context
        while (cur is ContextWrapper) {
            if (cur is Activity) break
            cur = cur.baseContext
        }
        cur as? Activity
    }

    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build()
    }

    fun pickAdjacentIndex(delta: Int): Int? {
        if (playlist.isEmpty() || currentItem == null) return null
        if (playlist.size == 1) return if (state.playbackOrder == PlaybackOrder.LOOP_ALL) 0 else null
        val curIdx = playlist.indexOfFirst { it.id == currentItem.id }.coerceAtLeast(0)
        return when (state.playbackOrder) {
            PlaybackOrder.SHUFFLE -> {
                val candidates = playlist.indices.filter { it != curIdx }
                candidates.randomOrNull()
            }
            PlaybackOrder.REVERSE -> {
                val target = curIdx - delta
                if (target in playlist.indices) target else null
            }
            PlaybackOrder.LOOP_ALL -> (curIdx + delta + playlist.size) % playlist.size
            PlaybackOrder.SEQUENTIAL -> {
                val target = curIdx + delta
                if (target in playlist.indices) target else null
            }
        }
    }

    fun jumpNext() {
        val nextIdx = pickAdjacentIndex(1) ?: return
        onSelectItem(playlist[nextIdx].id)
    }

    fun jumpPrev() {
        val prevIdx = pickAdjacentIndex(-1) ?: return
        onSelectItem(playlist[prevIdx].id)
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val d = exoPlayer.duration
                if (d > 0) durationMs = d
                if (playbackState == Player.STATE_ENDED) {
                    val nextIdx = pickAdjacentIndex(1)
                    if (nextIdx != null) {
                        val nextItem = playlist[nextIdx]
                        if (nextItem.id == currentItem?.id) {
                            exoPlayer.seekTo(0)
                            exoPlayer.playWhenReady = true
                        } else {
                            onSelectItem(nextItem.id)
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                errorKey = "player_playback_failed"
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(currentItem?.id, reloadNonce) {
        errorKey = null
        positionMs = 0L
        durationMs = 0L
        val item = currentItem ?: return@LaunchedEffect
        val uri = resolvePlayableUri(item)
        if (uri == null) {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            errorKey = "player_file_missing"
            return@LaunchedEffect
        }
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    DisposableEffect(lifecycleOwner, exoPlayer, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                val inPip = activity?.isInPictureInPictureMode == true
                if (!inPip && exoPlayer.isPlaying) {
                    exoPlayer.pause()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    BackHandler(enabled = isFullscreen) {
        isFullscreen = false
    }

    LaunchedEffect(isFullscreen, activity) {
        val win = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(win, win.decorView)
        if (isFullscreen) {
            showFullscreenControls = true
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(activity) {
        onDispose {
            val win = activity?.window ?: return@onDispose
            try {
                WindowCompat.getInsetsController(win, win.decorView).show(WindowInsetsCompat.Type.systemBars())
            } catch (_: Exception) {
            }
        }
    }

    LaunchedEffect(isPlaying, currentItem?.id) {
        while (true) {
            if (!isScrubbing) {
                positionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            }
            val d = exoPlayer.duration
            if (d > 0L) durationMs = d
            delay(350)
        }
    }

    val isAudioOnly = currentItem?.mimeType?.startsWith("audio/") == true
    val controlsVisible = !isFullscreen || showFullscreenControls

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SalviaBlack)
            .let { if (isFullscreen) it else it.statusBarsPadding().navigationBarsPadding() }
    ) {
        // Top Header Bar
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SalviaDeepCosmic.copy(alpha = 0.92f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("player_back_button")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("go_back", emptyArray()), tint = SalviaPearl)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentItem?.title ?: t("media_fallback_title", emptyArray()),
                        style = MaterialTheme.typography.titleMedium,
                        color = SalviaPearl,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (currentItem != null) {
                        Text(
                            text = currentItem.fileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = SalviaSilverMid,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Playback order cycle button
                val orderDesc = when (state.playbackOrder) {
                    PlaybackOrder.SEQUENTIAL -> t("player_order_sequential", emptyArray())
                    PlaybackOrder.LOOP_ALL -> t("player_order_loop", emptyArray())
                    PlaybackOrder.REVERSE -> t("player_order_reverse", emptyArray())
                    PlaybackOrder.SHUFFLE -> t("player_order_shuffle", emptyArray())
                }
                IconButton(onClick = onCyclePlaybackOrder, modifier = Modifier.size(44.dp).testTag("player_order_button")) {
                    Icon(
                        imageVector = if (state.playbackOrder == PlaybackOrder.SHUFFLE) Icons.Filled.Shuffle else Icons.Filled.Repeat,
                        contentDescription = orderDesc,
                        tint = if (state.playbackOrder == PlaybackOrder.SEQUENTIAL) SalviaSilverMid else SalviaCyan
                    )
                }

                IconButton(onClick = onEnterPip, modifier = Modifier.size(44.dp).testTag("player_pip_button")) {
                    Icon(Icons.Filled.PictureInPictureAlt, contentDescription = t("player_pip", emptyArray()), tint = SalviaSilverMid)
                }

                IconButton(
                    onClick = { showPlaylist = !showPlaylist },
                    modifier = Modifier.size(44.dp).testTag("player_playlist_toggle")
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic,
                        contentDescription = t("player_queue_toggle", emptyArray()),
                        tint = if (showPlaylist) SalviaCyan else SalviaSilverMid
                    )
                }

                IconButton(
                    onClick = { isFullscreen = !isFullscreen },
                    modifier = Modifier.size(44.dp).testTag("player_fullscreen_button")
                ) {
                    Icon(
                        imageVector = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = t(if (isFullscreen) "player_exit_fullscreen" else "player_fullscreen", emptyArray()),
                        tint = SalviaPearl
                    )
                }
            }
        }
        }

        // Main Player Surface
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(SalviaBlack)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (isFullscreen) {
                        showFullscreenControls = !showFullscreenControls
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            when {
                currentItem == null -> {
                    Text(t("no_completed_downloads", emptyArray()), color = SalviaSilverMid)
                }

                errorKey != null -> {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = SalviaSurface,
                        border = BorderStroke(1.dp, SalviaError.copy(alpha = 0.55f)),
                        modifier = Modifier.padding(24.dp).widthIn(max = 440.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = SalviaError, modifier = Modifier.size(36.dp))
                            Text(t(errorKey!!, emptyArray()), color = SalviaPearl, style = MaterialTheme.typography.bodyLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { reloadNonce = System.currentTimeMillis() },
                                    colors = ButtonDefaults.buttonColors(containerColor = SalviaIris, contentColor = SalviaPearl)
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.size(6.dp))
                                    Text(t("player_retry", emptyArray()))
                                }
                                OutlinedButton(
                                    onClick = { onDeleteItem(currentItem.id) },
                                    border = BorderStroke(1.dp, SalviaError)
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = null, tint = SalviaError, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.size(6.dp))
                                    Text(t("player_delete_download", emptyArray()), color = SalviaError)
                                }
                            }
                        }
                    }
                }

                isAudioOnly -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(140.dp)
                                .clip(CircleShape)
                                .background(Brush.radialGradient(listOf(SalviaIrisContainer, SalviaElevated))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.AudioFile, contentDescription = null, tint = SalviaCyan, modifier = Modifier.size(64.dp))
                        }
                        Text(currentItem.title, style = MaterialTheme.typography.titleLarge, color = SalviaPearl)
                    }
                }

                else -> {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                player = exoPlayer
                            }
                        },
                        update = { pv ->
                            if (pv.player !== exoPlayer) pv.player = exoPlayer
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Collapsible Playlist
        if (showPlaylist && playlist.isNotEmpty()) {
            Surface(
                color = SalviaSurface,
                border = BorderStroke(1.dp, SalviaBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 210.dp)
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(playlist, key = { it.id }) { entry ->
                        val active = entry.id == currentItem?.id
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (active) SalviaIrisContainer else SalviaElevated,
                            border = BorderStroke(1.dp, if (active) SalviaIris else SalviaBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectItem(entry.id) }
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = if (entry.mimeType.startsWith("audio/")) Icons.Filled.AudioFile else Icons.Filled.Movie,
                                    contentDescription = null,
                                    tint = if (active) SalviaCyan else SalviaSilverMid,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = entry.title.ifBlank { entry.fileName },
                                    color = SalviaPearl,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom Cosmic Transport Controls
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
        Surface(
            color = SalviaDeepCosmic,
            border = BorderStroke(1.dp, SalviaBorder.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                val sliderValue = when {
                    isScrubbing -> scrubFraction.coerceIn(0f, 1f)
                    durationMs > 0L -> (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    else -> 0f
                }
                val displayedPositionMs = if (isScrubbing && durationMs > 0L) {
                    (scrubFraction * durationMs).toLong()
                } else {
                    positionMs
                }
                Slider(
                    value = sliderValue,
                    onValueChange = { frac ->
                        isScrubbing = true
                        scrubFraction = frac.coerceIn(0f, 1f)
                    },
                    onValueChangeFinished = {
                        if (durationMs > 0L) {
                            val target = (scrubFraction * durationMs).toLong()
                            positionMs = target
                            exoPlayer.seekTo(target)
                        }
                        isScrubbing = false
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = SalviaCyan,
                        activeTrackColor = SalviaIris,
                        inactiveTrackColor = SalviaElevated
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = MediaFormatters.formatDurationMs(displayedPositionMs),
                        fontSize = 12.sp,
                        fontFamily = SalviaMonoFontFamily,
                        color = SalviaPearl
                    )
                    Text(
                        text = if (durationMs > 0L) MediaFormatters.formatDurationMs(durationMs) else "--:--",
                        fontSize = 12.sp,
                        fontFamily = SalviaMonoFontFamily,
                        color = SalviaSilverMid
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Mute toggle
                    IconButton(
                        onClick = {
                            isMuted = !isMuted
                            exoPlayer.volume = if (isMuted) 0f else 1f
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = t(if (isMuted) "player_unmute" else "player_mute", emptyArray()),
                            tint = if (isMuted) SalviaError else SalviaSilverMid
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(onClick = ::jumpPrev, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Filled.SkipPrevious, contentDescription = t("player_previous", emptyArray()), tint = SalviaPearl)
                        }
                        IconButton(
                            onClick = { exoPlayer.seekTo((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)) },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(Icons.Filled.Replay10, contentDescription = "-10s", tint = SalviaPearl)
                        }

                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(SalviaIris, SalviaCyan)))
                                .clickable {
                                    if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                                }
                                .testTag("player_play_pause_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = t(if (isPlaying) "player_pause" else "player_play", emptyArray()),
                                tint = SalviaDeepCosmic,
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                val cap = if (durationMs > 0L) durationMs else Long.MAX_VALUE
                                exoPlayer.seekTo((exoPlayer.currentPosition + 10_000L).coerceAtMost(cap))
                            },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(Icons.Filled.Forward10, contentDescription = "+10s", tint = SalviaPearl)
                        }
                        IconButton(onClick = ::jumpNext, modifier = Modifier.size(44.dp)) {
                            Icon(Icons.Filled.SkipNext, contentDescription = t("player_next", emptyArray()), tint = SalviaPearl)
                        }
                    }

                    // Speed selector
                    Box {
                        TextButton(onClick = { showSpeedMenu = true }) {
                            Text("${playbackSpeed}x", color = SalviaGold, fontFamily = SalviaMonoFontFamily, fontSize = 13.sp)
                        }
                        DropdownMenu(
                            expanded = showSpeedMenu,
                            onDismissRequest = { showSpeedMenu = false },
                            containerColor = SalviaElevated
                        ) {
                            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                                DropdownMenuItem(
                                    text = { Text("${spd}x", color = if (spd == playbackSpeed) SalviaCyan else SalviaPearl) },
                                    onClick = {
                                        showSpeedMenu = false
                                        playbackSpeed = spd
                                        exoPlayer.setPlaybackSpeed(spd)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

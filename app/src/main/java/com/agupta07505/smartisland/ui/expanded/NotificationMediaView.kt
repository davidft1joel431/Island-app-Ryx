/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.ui.expanded

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.ImageView
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.agupta07505.smartisland.model.NotificationMedia
import com.agupta07505.smartisland.model.NotificationMediaKind
import com.agupta07505.smartisland.R
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Which notification's picture is open at full size in the expanded island: the card key and the picture's position.
 */
class MediaExpansion(
    val key: String?,
    val index: Int,
    /** Landscape: the open picture is drawn as its own island beside the card, so the card keeps its content. */
    val besideCard: Boolean = false,
    /** Landscape: the image selector is open beside the card (no single picture is open yet). */
    val selectorOpen: Boolean = false,
    val onChange: (key: String?, index: Int) -> Unit
) {
    val isOpen: Boolean get() = key != null
}

val LocalMediaExpansion = compositionLocalOf { MediaExpansion(null, 0) { _, _ -> } }

/**
 * The open step (preview / reader / picture) of the shown card, held by the view model so the card, its arrow and the
 * swipe gestures share it (model/CardDepth.kt). Any other card reads the first state.
 */
class CardStepsAccess(
    private val state: com.agupta07505.smartisland.model.CardStepState,
    private val pictureKey: String?,
    private val pictureIndex: Int,
    val set: (key: String, steps: com.agupta07505.smartisland.model.CardSteps) -> Unit,
    val reportExpandable: (key: String, expandable: Boolean) -> Unit
) {
    fun stepsFor(key: String?): com.agupta07505.smartisland.model.CardSteps = state.steps(key, pictureKey, pictureIndex)
}

val LocalCardSteps = compositionLocalOf {
    CardStepsAccess(com.agupta07505.smartisland.model.CardStepState(), null, 0, { _, _ -> }, { _, _ -> })
}

/**
 * Where the reader text and its thumbnail column report scrolling, so the island's gesture handler can refuse a tap
 * that only stops a fling. Provided once at the top of the overlay.
 */
val LocalReaderScroll = compositionLocalOf { com.agupta07505.smartisland.util.ReaderScrollTracker() }

/**
 * Reports a reader list's scrolling to [LocalReaderScroll]: drag and fling (state) and every offset change (moves).
 * Call it next to the list's `verticalScroll`.
 */
@androidx.compose.runtime.Composable
fun ReportReaderScroll(state: androidx.compose.foundation.ScrollState) {
    val tracker = LocalReaderScroll.current
    androidx.compose.runtime.DisposableEffect(tracker) {
        // A reader that leaves the screen mid-fling must not leave the island thinking it still scrolls.
        onDispose { tracker.reset() }
    }
    androidx.compose.runtime.LaunchedEffect(state, tracker) {
        kotlinx.coroutines.coroutineScope {
            launch {
                androidx.compose.runtime.snapshotFlow { state.isScrollInProgress }.drop(1).collect {
                    tracker.onScrollState(it, android.os.SystemClock.uptimeMillis())
                }
            }
            launch {
                androidx.compose.runtime.snapshotFlow { state.value }.drop(1).collect {
                    tracker.onMoved(android.os.SystemClock.uptimeMillis())
                }
            }
        }
    }
}

/** The view model's half-typed replies; provided once at the top of the overlay (see OverlayIsland). */
val LocalReplyDrafts = compositionLocalOf { com.agupta07505.smartisland.model.ReplyDrafts() }

/** Height the full-size picture gets: the tallest an expanded island ever grows. */
internal val EXPANDED_MEDIA_HEIGHT = com.agupta07505.smartisland.model.PictureSize.FULL_DP.dp

/** Height the half-size picture step gets: halfway between the card's smallest height and [EXPANDED_MEDIA_HEIGHT]. */
internal val PICTURE_MID_HEIGHT = com.agupta07505.smartisland.model.PictureSize.MID_DP.dp

/** A notification picture. Animated stickers and GIFs play; everything else is the still image. */
@Composable
fun NotificationMediaView(
    media: NotificationMedia,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    maxSidePx: Int = 512
) {
    when (media.kind) {
        NotificationMediaKind.AUDIO -> {
            AudioAttachmentPlayer(media, modifier)
            return
        }
        NotificationMediaKind.VIDEO -> {
            VideoAttachmentPlayer(media, modifier)
            return
        }
        NotificationMediaKind.IMAGE -> Unit
    }
    val still = androidx.compose.runtime.remember(media.still) { media.still.asImageBitmap() }
    val bytes = media.animatedBytes
    if (bytes == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        Image(bitmap = still, contentDescription = null, contentScale = contentScale, modifier = modifier)
        return
    }
    AnimatedMediaView(bytes, still, modifier, contentScale, maxSidePx)
}

@Composable
private fun AudioAttachmentPlayer(media: NotificationMedia, modifier: Modifier) {
    val file = media.file
    if (file == null) {
        Image(bitmap = media.still.asImageBitmap(), contentDescription = null, modifier = modifier)
        return
    }
    val player = remember(file) { android.media.MediaPlayer() }
    var prepared by remember(file) { mutableStateOf(false) }
    var playing by remember(file) { mutableStateOf(false) }
    var duration by remember(file) { mutableStateOf(0) }
    var position by remember(file) { mutableStateOf(0) }

    DisposableEffect(player, file) {
        player.setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .build()
        )
        player.setOnPreparedListener {
            duration = it.duration.coerceAtLeast(0)
            prepared = true
        }
        player.setOnCompletionListener {
            position = duration
            playing = false
        }
        runCatching {
            player.setDataSource(file.absolutePath)
            player.prepareAsync()
        }
        onDispose { runCatching { player.release() } }
    }
    LaunchedEffect(player, prepared, playing) {
        if (!prepared) return@LaunchedEffect
        if (playing && !player.isPlaying) player.start()
        else if (!playing && player.isPlaying) player.pause()
        while (playing && prepared) {
            position = player.currentPosition.coerceAtLeast(0)
            delay(250)
        }
    }

    Row(
        modifier = modifier
            .background(Color(0xFF171717))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IconButton(
            onClick = {
                if (prepared) {
                    if (playing) playing = false
                    else {
                        if (duration > 0 && position >= duration) player.seekTo(0)
                        playing = true
                    }
                }
            },
            enabled = prepared
        ) {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.notification_media_audio), color = Color.White, maxLines = 1)
            Slider(
                value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { value ->
                    position = (value * duration).toInt()
                    if (prepared && duration > 0) player.seekTo(position)
                },
                enabled = prepared && duration > 0
            )
        }
        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White.copy(alpha = 0.72f))
    }
}

@Composable
private fun VideoAttachmentPlayer(media: NotificationMedia, modifier: Modifier) {
    val file = media.file
    if (file == null) {
        Image(bitmap = media.still.asImageBitmap(), contentDescription = null, modifier = modifier)
        return
    }
    var prepared by remember(file) { mutableStateOf(false) }
    var playing by remember(file) { mutableStateOf(false) }
    val context = LocalContext.current
    val videoView = remember(file) {
        VideoView(context).apply {
            setVideoPath(file.absolutePath)
            setOnPreparedListener { it.isLooping = false; prepared = true }
            setOnCompletionListener { playing = false; seekTo(0) }
            setOnErrorListener { _, _, _ -> prepared = false; playing = false; true }
        }
    }
    LaunchedEffect(videoView, prepared, playing) {
        if (prepared) {
            if (playing && !videoView.isPlaying) videoView.start()
            else if (!playing && videoView.isPlaying) videoView.pause()
        }
    }
    DisposableEffect(videoView) { onDispose { runCatching { videoView.stopPlayback() } } }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(factory = { videoView }, modifier = Modifier.fillMaxSize())
        if (!playing) {
            IconButton(
                onClick = { if (prepared) playing = true },
                enabled = prepared,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.62f), androidx.compose.foundation.shape.CircleShape)
                    .padding(8.dp)
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(38.dp))
            }
        } else {
            IconButton(
                onClick = { playing = false },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.62f), androidx.compose.foundation.shape.CircleShape)
            ) {
                Icon(Icons.Rounded.Pause, contentDescription = null, tint = Color.White)
            }
        }
    }
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
@Composable
private fun AnimatedMediaView(
    bytes: ByteArray,
    still: androidx.compose.ui.graphics.ImageBitmap,
    modifier: Modifier,
    contentScale: ContentScale,
    maxSidePx: Int
) {
    val drawable by produceState<Drawable?>(null, bytes, maxSidePx) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                    val longest = max(info.size.width, info.size.height)
                    if (longest > maxSidePx) {
                        val scale = maxSidePx.toFloat() / longest
                        decoder.setTargetSize(
                            (info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1)
                        )
                    }
                }
            }.getOrNull()
        }
    }
    val current = drawable
    if (current == null) {
        // The animation is still being decoded: show the first frame meanwhile.
        Image(bitmap = still, contentDescription = null, contentScale = contentScale, modifier = modifier)
        return
    }
    DisposableEffect(current) {
        (current as? AnimatedImageDrawable)?.let {
            it.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            it.start()
        }
        onDispose { (current as? AnimatedImageDrawable)?.stop() }
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ImageView(context).apply {
                scaleType = if (contentScale == ContentScale.Crop) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            }
        },
        update = { it.setImageDrawable(current) }
    )
}

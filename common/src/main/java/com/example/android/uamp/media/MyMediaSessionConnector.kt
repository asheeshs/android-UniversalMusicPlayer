package com.example.android.uamp.media

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.RatingCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.utils.MediaConstants
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector.DefaultMediaMetadataProvider
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector.QueueNavigator
import com.google.android.exoplayer2.util.Assertions
import com.google.android.exoplayer2.util.Util

class MyMediaSessionConnector(
    private val mediaSession: MediaSessionCompat,
    private val playbackPreparer: MusicService.UampPlaybackPreparer,
    private val queueNavigator: MusicService.UampQueueNavigator
) {

    private val looper = Util.getCurrentOrMainLooper()
    private val METADATA_EMPTY = MediaMetadataCompat.Builder().build()
    private val mediaMetadataProvider = DefaultMediaMetadataProvider(
        mediaSession.controller,
        null
    )

    internal var player: Player? = null
        set(newPlayer) {
            Assertions.checkArgument(player == null || player?.applicationLooper == looper)
            this.player?.removeListener(playerListener)
            field = newPlayer
            newPlayer?.addListener(playerListener)
            update()
        }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            Log.d(TAG, "onEvents player: $player, events: $events")
            queueNavigator.onTimelineChanged(player)
            updatePlayerState()
            updateMetadata()
        }
    }

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPrepare() {
            Log.d(TAG, "onPrepare")
            playbackPreparer.onPrepare(false)
        }

        override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) {
            Log.d(TAG, "onPrepareFromMediaId, mediaId: $mediaId")
            playbackPreparer.onPrepareFromMediaId(mediaId!!, false, extras)
        }

        override fun onPrepareFromSearch(query: String?, extras: Bundle?) {
            Log.d(TAG, "onPrepareFromSearch, query: $query")
            playbackPreparer.onPrepareFromSearch(query!!,  /* playWhenReady= */false, extras)
        }

        override fun onPrepareFromUri(uri: Uri, extras: Bundle) {
            Log.d(TAG, "onPrepareFromUri, uri: $uri")
            playbackPreparer.onPrepareFromUri(uri,  /* playWhenReady= */false, extras)
        }

        override fun onPlay() {
            Log.d(TAG, "onPlay")
            playbackPreparer.onPrepare(true)
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            Log.d(TAG, "onPlayFromMediaId, mediaId: $mediaId")
            playbackPreparer.onPrepareFromMediaId(mediaId!!,  /* playWhenReady= */true, extras)
        }

        override fun onPlayFromUri(uri: Uri, extras: Bundle) {
            Log.d(TAG, "onPlayFromUri uri:$uri")
            playbackPreparer.onPrepareFromUri(uri,  /* playWhenReady= */true, extras)
        }


        override fun onPlayFromSearch(query: String, extras: Bundle) {
            Log.d(TAG, "onPlayFromSearch, query: $query")
            playbackPreparer.onPrepareFromSearch(query,  /* playWhenReady= */true, extras)
        }

        override fun onPause() {
            Log.d(TAG, "onPause")
            player?.pause()
        }

        override fun onStop() {
            Log.d(TAG, "onStop")
            player?.apply {
               stop()
               clearMediaItems()
            }
        }

        override fun onSkipToPrevious() {
            Log.d(TAG, "onSkipToPrevious")
            player?.let {
                queueNavigator.onSkipToPrevious(it)
            }
        }

        override fun onSkipToNext() {
            Log.d(TAG, "onSkipToNext")
            player?.let {
                queueNavigator.onSkipToNext(it)
            }
        }

        override fun onSkipToQueueItem(id: Long) {
            player?.let {
                queueNavigator.onSkipToQueueItem(it, id)
            }
        }

        override fun onSeekTo(pos: Long) {
            Log.d(TAG, "onSeekTo: $pos")
            player?.apply {
                seekTo(currentMediaItemIndex, pos)
            }
        }

        override fun onSetRating(rating: RatingCompat) {
            Log.d(TAG, "onSetRating")
        }

        override fun onCustomAction(action: String, extras: Bundle) {
            Log.d(TAG, "onCustomAction: $action")
        }
    }

    init {
        mediaSession.setFlags(BASE_MEDIA_SESSION_FLAGS)
        mediaSession.setCallback(mediaSessionCallback, Handler(looper))
    }

    private fun update() {
        updatePlayerState()
        updateMetadata()
    }

    private fun updateMetadata() {
        val player = this.player
        Log.d(TAG, "updateMetadata, timeline: ${player?.currentTimeline?.isEmpty}")
        val metadata =
            if (player != null) {
                Log.d(TAG, "Building Metadata")
                mediaMetadataProvider.getMetadata(player)
            } else {
                Log.d(TAG, "Empty Metadata")
                METADATA_EMPTY
            }
        Log.d(TAG, "metadata is title: ${metadata.description.title}, url : ${metadata.description.iconUri}")
        val oldMetadata = mediaSession.controller.metadata
        if (oldMetadata != null && mediaMetadataProvider.sameAs(oldMetadata, metadata)) {
            // Do not update if metadata did not change.
            Log.d(TAG, "Not updating as metadata did not change.")
            return
        }

        mediaSession.setMetadata(metadata)
    }

    private fun updatePlayerState() {
        val builder = PlaybackStateCompat.Builder()
        val player = player
        if (player == null) {
            builder
                .setActions(BASE_ACTIONS)
                .setState(
                    PlaybackStateCompat.STATE_NONE,  /* position= */
                    0,  /* playbackSpeed= */
                    0f,  /* updateTime= */
                    SystemClock.elapsedRealtime()
                )
            mediaSession.setRepeatMode(PlaybackStateCompat.REPEAT_MODE_NONE)
            mediaSession.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_NONE)
            mediaSession.setPlaybackState(builder.build())
            return
        }
        mediaSession.setPlaybackState(buildPlaybackState())
    }

    private fun buildPlaybackState(): PlaybackStateCompat {
        val playbackStateBuilder = PlaybackStateCompat.Builder()
        // set actions
        playbackStateBuilder.setActions(buildPlaybackActions(player))

        //extras
        val extras = Bundle()
        val playbackSpeed = player?.playbackParameters?.speed ?: 0f
        extras.putFloat(MediaSessionConnector.EXTRAS_SPEED, playbackSpeed)
        val currentMediaItem = player?.currentMediaItem
        if (currentMediaItem != null && MediaItem.DEFAULT_MEDIA_ID != currentMediaItem.mediaId) {
            extras.putString(
                MediaConstants.PLAYBACK_STATE_EXTRAS_KEY_MEDIA_ID,
                currentMediaItem.mediaId
            )
        }
        player?.let {
            playbackStateBuilder.setActiveQueueItemId(
                queueNavigator.getActiveQueueItemId(player)
            )
            playbackStateBuilder.setBufferedPosition(it.bufferedPosition)
        }

        // set playback state
        val sessionPlaybackSpeed = player?.let {
            if (it.isPlaying) playbackSpeed else 0f
        } ?: 0f
        playbackStateBuilder.putState(sessionPlaybackSpeed)
        playbackStateBuilder.setExtras(extras)
        // build and return
        return playbackStateBuilder.build()
    }

    private fun PlaybackStateCompat.Builder.putState(sessionPlaybackSpeed: Float): PlaybackStateCompat.Builder {
        player?.apply {
            val playWhenReady = playWhenReady
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    Log.d(TAG, "setting state : STATE_BUFFERING")
                    setState(
                        if (playWhenReady) PlaybackStateCompat.STATE_BUFFERING
                        else if (isLiveStation()) PlaybackStateCompat.STATE_STOPPED else PlaybackStateCompat.STATE_PAUSED,
                        currentPosition,
                        sessionPlaybackSpeed
                    )
                }
                Player.STATE_READY -> {
                    Log.d(TAG, "setting state : STATE_READY, playWhenReady: $playWhenReady")
                    setState(
                        if (playWhenReady) PlaybackStateCompat.STATE_PLAYING
                        else if (isLiveStation()) PlaybackStateCompat.STATE_STOPPED else PlaybackStateCompat.STATE_PAUSED,
                        currentPosition,
                        sessionPlaybackSpeed
                    )
                }
                Player.STATE_ENDED -> {
                    Log.d(TAG, "setting state : STATE_ENDED")
                    setState(
                        if (isLiveStation()) PlaybackStateCompat.STATE_STOPPED else PlaybackStateCompat.STATE_PAUSED,
                        currentPosition,
                        sessionPlaybackSpeed
                    )
                }
                else -> {
                    Log.d(TAG, "setting state : STATE_NONE")
                    setState(PlaybackStateCompat.STATE_NONE, currentPosition, sessionPlaybackSpeed)
                }
            }
        }
        return this
    }

    private fun Player.isLiveStation() = isCurrentMediaItemLive

    private fun buildPlaybackActions(player: Player?): Long {
        var playbackActions = BASE_ACTIONS
        var actions = playbackActions
        player?.let {
            if (!it.isLiveStation()) {
                playbackActions = playbackActions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                playbackActions = playbackActions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            }
            actions = actions or (QueueNavigator.ACTIONS and queueNavigator.getSupportedQueueNavigatorActions(player))
            if (it.playbackState == Player.STATE_READY) {
                actions = if (it.playWhenReady) {
                    if (it.isLiveStation()) actions or PlaybackStateCompat.ACTION_STOP else actions or PlaybackStateCompat.ACTION_PAUSE
                } else actions or PlaybackStateCompat.ACTION_PLAY
            }
        }
        return actions
    }

    companion object {
        private const val TAG = "MyMediaSessionConnector"
        private const val BASE_ACTIONS = (PlaybackStateCompat.ACTION_SET_RATING
                or PlaybackStateCompat.ACTION_PLAY_PAUSE
                or PlaybackStateCompat.ACTION_PLAY_FROM_URI
                or PlaybackStateCompat.ACTION_PREPARE
                or PlaybackStateCompat.ACTION_PREPARE_FROM_URI
                or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH)

        private const val BASE_MEDIA_SESSION_FLAGS = (MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                        or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
    }
}

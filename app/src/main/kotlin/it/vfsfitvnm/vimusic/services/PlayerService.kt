package it.vfsfitvnm.vimusic.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.media3.common.*
import androidx.media3.common.util.Util
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaNotification.ActionFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import coil.ImageLoader
import coil.request.ImageRequest
import it.vfsfitvnm.vimusic.Database
import it.vfsfitvnm.vimusic.MainActivity
import it.vfsfitvnm.vimusic.R
import it.vfsfitvnm.vimusic.utils.RingBuffer
import it.vfsfitvnm.vimusic.utils.YoutubePlayer
import it.vfsfitvnm.vimusic.utils.insert
import it.vfsfitvnm.youtubemusic.Outcome
import kotlinx.coroutines.*
import kotlin.math.roundToInt


@ExperimentalAnimationApi
@ExperimentalFoundationApi
@ExperimentalTextApi
class PlayerService : MediaSessionService(), MediaSession.MediaItemFiller,
    MediaNotification.Provider,
    PlaybackStatsListener.Callback, Player.Listener,YoutubePlayer.Radio.Listener {

    companion object {
        private const val NotificationId = 1001
        private const val NotificationChannelId = "default_channel_id"
    }

    private val cache: SimpleCache by lazy(LazyThreadSafetyMode.NONE) {
        SimpleCache(cacheDir, NoOpCacheEvictor(), StandaloneDatabaseProvider(this))
    }

    private lateinit var mediaSession: MediaSession

    private val notificationManager by lazy(LazyThreadSafetyMode.NONE) {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private var lastArtworkUri: Uri? = null
    private var lastBitmap: Bitmap? = null

    private val coroutineScope = CoroutineScope(Dispatchers.IO) + Job()

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        setMediaNotificationProvider(this)

        val player = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setMediaSourceFactory(DefaultMediaSourceFactory(createDataSourceFactory()))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .build()
            .also { player ->
                player.playWhenReady = true
                player.addAnalyticsListener(PlaybackStatsListener(false, this))
            }

        mediaSession = MediaSession.Builder(this, player)
            .withSessionActivity()
            .setMediaItemFiller(this)
            .build()

        player.addListener(this)
        YoutubePlayer.Radio.listener = this
    }

    override fun onDestroy() {
        mediaSession.player.release()
        mediaSession.release()
        cache.release()
        super.onDestroy()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        return mediaSession
    }

    override fun onPlaybackStatsReady(
        eventTime: AnalyticsListener.EventTime,
        playbackStats: PlaybackStats
    ) {
        val mediaItem =
            eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem

        coroutineScope.launch(Dispatchers.IO) {
            Database.insert(mediaItem)
            Database.incrementTotalPlayTimeMs(mediaItem.mediaId, playbackStats.totalPlayTimeMs)
        }
    }

    override fun process(play: Boolean) {
        if (YoutubePlayer.Radio.isActive) {
            coroutineScope.launch {
                YoutubePlayer.Radio.process(mediaSession.player, play = play)
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (YoutubePlayer.Radio.isActive) {
            coroutineScope.launch {
                YoutubePlayer.Radio.process(mediaSession.player)
            }
        }
    }

    override fun fillInLocalConfiguration(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItem: MediaItem
    ): MediaItem {
        return mediaItem.buildUpon()
            .setUri(mediaItem.mediaId)
            .setCustomCacheKey(mediaItem.mediaId)
            .build()
    }

    override fun createNotification(
        mediaController: MediaController,
        actionFactory: ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback
    ): MediaNotification {
        fun NotificationCompat.Builder.addMediaAction(
            @DrawableRes resId: Int,
            @StringRes stringId: Int,
            @Player.Command command: Long
        ): NotificationCompat.Builder {
            return addAction(
                actionFactory.createMediaAction(
                    IconCompat.createWithResource(this@PlayerService, resId),
                    getString(stringId),
                    command
                )
            )
        }

        val mediaMetadata = mediaController.mediaMetadata

        val builder = NotificationCompat.Builder(applicationContext, NotificationChannelId)
            .setContentTitle(mediaMetadata.title)
            .setContentText(mediaMetadata.artist)
            .addMediaAction(
                R.drawable.play_skip_back,
                R.string.media3_controls_seek_to_previous_description,
                ActionFactory.COMMAND_SKIP_TO_PREVIOUS
            ).run {
                if (mediaController.playbackState == Player.STATE_ENDED || !mediaController.playWhenReady) {
                    addMediaAction(
                        R.drawable.play,
                        R.string.media3_controls_play_description,
                        ActionFactory.COMMAND_PLAY
                    )
                } else {
                    addMediaAction(
                        R.drawable.pause,
                        R.string.media3_controls_pause_description,
                        ActionFactory.COMMAND_PAUSE
                    )
                }
            }.addMediaAction(
                R.drawable.play_skip_forward,
                R.string.media3_controls_seek_to_next_description,
                ActionFactory.COMMAND_SKIP_TO_NEXT
            )
            .setContentIntent(mediaController.sessionActivity)
            .setDeleteIntent(
                actionFactory.createMediaActionPendingIntent(
                    ActionFactory.COMMAND_STOP
                )
            )
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSmallIcon(R.drawable.app_icon)
            .setOngoing(false)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
                    .setMediaSession(mediaSession.sessionCompatToken as android.support.v4.media.session.MediaSessionCompat.Token)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)


        if (lastArtworkUri == mediaMetadata.artworkUri) {
            builder.setLargeIcon(lastBitmap)
        } else {
            val size = (96 * resources.displayMetrics.density).roundToInt()

            builder.setLargeIcon(
                resources.getDrawable(R.drawable.disc_placeholder, null)?.toBitmap(size, size)
            )

            ImageLoader(applicationContext)
                .enqueue(
                    ImageRequest.Builder(applicationContext)
                        .listener { _, result ->
                            lastBitmap = (result.drawable as BitmapDrawable).bitmap
                            lastArtworkUri = mediaMetadata.artworkUri

                            onNotificationChangedCallback.onNotificationChanged(
                                MediaNotification(
                                    NotificationId,
                                    builder.setLargeIcon(lastBitmap).build()
                                )
                            )
                        }
                        .data("${mediaMetadata.artworkUri}-w${size}-h${size}")
                        .build()
                )
        }

        return MediaNotification(NotificationId, builder.build())
    }

    override fun handleCustomAction(
        mediaController: MediaController,
        action: String,
        extras: Bundle
    ) = Unit

    private fun createNotificationChannel() {
        if (Util.SDK_INT >= 26 && notificationManager.getNotificationChannel(NotificationChannelId) == null) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    NotificationChannelId,
                    getString(R.string.default_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun createCacheDataSource(): DataSource.Factory {
        return CacheDataSource.Factory().setCache(cache).apply {
            setUpstreamDataSourceFactory(
                DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(16000)
                    .setReadTimeoutMs(8000)
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; rv:91.0) Gecko/20100101 Firefox/91.0")
            )
        }
    }

    private fun createDataSourceFactory(): DataSource.Factory {
        val chunkLength = 512 * 1024L
        val ringBuffer = RingBuffer<Pair<String, Uri>?>(2) { null }

        return ResolvingDataSource.Factory(createCacheDataSource()) { dataSpec ->
            val videoId = dataSpec.key ?: error("A key must be set")

            if (cache.isCached(videoId, dataSpec.position, chunkLength)) {
                dataSpec
            } else {
                when (videoId) {
                    ringBuffer.getOrNull(0)?.first -> dataSpec.withUri(ringBuffer.getOrNull(0)!!.second)
                    ringBuffer.getOrNull(1)?.first -> dataSpec.withUri(ringBuffer.getOrNull(1)!!.second)
                    else -> {
                        val url = runBlocking(Dispatchers.IO) {
                            it.vfsfitvnm.youtubemusic.YouTube.player(videoId)
                        }.flatMap { body ->
                            when (val status = body.playabilityStatus.status) {
                                "OK" -> body.streamingData?.adaptiveFormats?.findLast { format ->
                                    format.itag == 251 || format.itag == 140
                                }?.url?.let { Outcome.Success(it) } ?: Outcome.Error.Unhandled(
                                    PlaybackException(
                                        "Couldn't find a playable audio format",
                                        null,
                                        PlaybackException.ERROR_CODE_REMOTE_ERROR
                                    )
                                )
                                else -> Outcome.Error.Unhandled(
                                    PlaybackException(
                                        status,
                                        null,
                                        PlaybackException.ERROR_CODE_REMOTE_ERROR
                                    )
                                )
                            }
                        }

                        when (url) {
                            is Outcome.Success -> {
                                ringBuffer.append(videoId to url.value.toUri())
                                dataSpec.withUri(url.value.toUri())
                                    .subrange(dataSpec.uriPositionOffset, chunkLength)
                            }
                            is Outcome.Error.Network -> throw PlaybackException(
                                "Couldn't reach the internet",
                                null,
                                PlaybackException.ERROR_CODE_REMOTE_ERROR
                            )
                            is Outcome.Error.Unhandled -> throw url.throwable
                            else -> throw PlaybackException(
                                "Unexpected error",
                                null,
                                PlaybackException.ERROR_CODE_REMOTE_ERROR
                            )
                        }
                    }
                }
            }
        }
    }

    private fun MediaSession.Builder.withSessionActivity(): MediaSession.Builder {
        return setSessionActivity(
            PendingIntent.getActivity(
                this@PlayerService,
                0,
                Intent(this@PlayerService, MainActivity::class.java),
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            )
        )
    }
}

package it.vfsfitvnm.vimusic.ui.screens

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.valentinilk.shimmer.shimmer
import it.vfsfitvnm.route.RouteHandler
import it.vfsfitvnm.vimusic.R
import it.vfsfitvnm.vimusic.ui.components.ExpandableText
import it.vfsfitvnm.vimusic.ui.components.Message
import it.vfsfitvnm.vimusic.ui.components.OutcomeItem
import it.vfsfitvnm.vimusic.ui.components.TopAppBar
import it.vfsfitvnm.vimusic.ui.components.themed.TextPlaceholder
import it.vfsfitvnm.vimusic.ui.styling.LocalColorPalette
import it.vfsfitvnm.vimusic.ui.styling.LocalTypography
import it.vfsfitvnm.vimusic.utils.*
import it.vfsfitvnm.youtubemusic.Outcome
import it.vfsfitvnm.youtubemusic.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


@ExperimentalAnimationApi
@Composable
fun ArtistScreen(
    browseId: String,
) {
    val scrollState = rememberScrollState()

    var artist by remember {
        mutableStateOf<Outcome<YouTube.Artist>>(Outcome.Loading)
    }

    val onLoad = relaunchableEffect(Unit) {
        artist = withContext(Dispatchers.IO) {
            YouTube.artist(browseId)
        }
    }

    val albumRoute = rememberPlaylistOrAlbumRoute()
    val artistRoute = rememberArtistRoute()

    RouteHandler(listenToGlobalEmitter = true) {
        albumRoute { browseId ->
            PlaylistOrAlbumScreen(
                browseId = browseId ?: error("browseId cannot be null")
            )
        }

        artistRoute { browseId ->
            ArtistScreen(
                browseId = browseId ?: error("browseId cannot be null")
            )
        }

        host {
            val density = LocalDensity.current
            val colorPalette = LocalColorPalette.current
            val typography = LocalTypography.current

            val (thumbnailSizeDp, thumbnailSizePx) = remember {
                density.run {
                    192.dp to 192.dp.roundToPx()
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(bottom = 72.dp)
                    .background(colorPalette.background)
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                TopAppBar(
                    modifier = Modifier
                        .height(52.dp)
                ) {
                    Image(
                        painter = painterResource(R.drawable.chevron_back),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(colorPalette.text),
                        modifier = Modifier
                            .clickable(onClick = pop)
                            .padding(vertical = 8.dp)
                            .padding(horizontal = 16.dp)
                            .size(24.dp)
                    )
                }

                OutcomeItem(
                    outcome = artist,
                    onRetry = onLoad,
                    onLoading = {
                        Loading()
                    }
                ) { artist ->
                    AsyncImage(
                        model = artist.thumbnail?.size(thumbnailSizePx),
                        contentDescription = null,
                        modifier = Modifier
                            .clip(CircleShape)
                            .size(thumbnailSizeDp)

                    )

                    BasicText(
                        text = artist.name,
                        style = typography.l.semiBold,
                        modifier = Modifier
                            .padding(vertical = 8.dp, horizontal = 16.dp)
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Image(
                            painter = painterResource(R.drawable.shuffle),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(colorPalette.text),
                            modifier = Modifier
                                .clickable {
                                    YoutubePlayer.Radio.reset()
                                    artist.shuffleEndpoint?.let(YoutubePlayer.Radio::setup)
                                }
                                .shadow(elevation = 2.dp, shape = CircleShape)
                                .background(color = colorPalette.elevatedBackground, shape = CircleShape)
                                .padding(horizontal = 16.dp, vertical = 16.dp)
                                .size(20.dp)
                        )

                        Image(
                            painter = painterResource(R.drawable.radio),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(colorPalette.text),
                            modifier = Modifier
                                .clickable {
                                    YoutubePlayer.Radio.reset()
                                    artist.radioEndpoint?.let(YoutubePlayer.Radio::setup)
                                }
                                .shadow(elevation = 2.dp, shape = CircleShape)
                                .background(color = colorPalette.elevatedBackground, shape = CircleShape)
                                .padding(horizontal = 16.dp, vertical = 16.dp)
                                .size(20.dp)
                        )
                    }

                    artist.description?.let { description ->
                        ExpandableText(
                            text = description,
                            style = typography.xxs.secondary.align(TextAlign.Justify),
                            minimizedMaxLines = 4,
                            backgroundColor = colorPalette.background,
                            showMoreTextStyle = typography.xxs.bold,
                            modifier = Modifier
                                .animateContentSize()
                                .padding(horizontal = 16.dp)
                        )
                    }

                    Message(
                        text = "Page under construction",
                        icon = R.drawable.sad,
                        modifier = Modifier
                            .padding(vertical = 64.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun Loading() {
    val colorPalette = LocalColorPalette.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .shimmer()
    ) {
        Spacer(
            modifier = Modifier
                .background(color = colorPalette.darkGray, shape = CircleShape)
                .size(192.dp)
        )

        TextPlaceholder(
            modifier = Modifier
                .alpha(0.9f)
                .padding(vertical = 8.dp, horizontal = 16.dp)
        )

        repeat(3) {
            TextPlaceholder(
                modifier = Modifier
                    .alpha(0.8f)
                    .padding(horizontal = 16.dp)
            )
        }
    }
}


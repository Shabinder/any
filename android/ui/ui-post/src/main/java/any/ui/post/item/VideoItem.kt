package any.ui.post.item

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import any.base.image.ImageRequest
import any.domain.entity.UiContentElement
import any.ui.common.image.AsyncImage
import any.ui.common.theme.imagePlaceholder
import any.ui.common.theme.thumb
import any.ui.common.video.VideoView
import any.ui.common.video.rememberVideoPlaybackState

// TODO: Implement the in-app video playback
@Composable
internal fun VideoItem(
    video: UiContentElement.Video,
    modifier: Modifier = Modifier,
    onPlayClick: () -> Unit,
    defaultAspectRatio: Float = 16f / 9,
) {
    Box(
        modifier = modifier
            .padding(
                horizontal = ItemsDefaults.ItemHorizontalSpacing,
                vertical = ItemsDefaults.ItemVerticalSpacing,
            )
            .fillMaxWidth()
            .aspectRatio(video.aspectRatio ?: defaultAspectRatio)
            .clip(MaterialTheme.shapes.thumb)
            .background(MaterialTheme.colors.imagePlaceholder),
    ) {
        val thumb = video.thumbnail
        if (video.url.isNotEmpty()) {
            VideoView(
                state = rememberVideoPlaybackState(url = video.url),
                modifier = Modifier.fillMaxSize(),
            )
        } else if (!thumb.isNullOrEmpty()) {
            AsyncImage(
                request = ImageRequest.Url(thumb),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

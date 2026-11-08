// ArtistSimilarScreen.kt

package com.vantra.music.ui.screens.artist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.vantra.music.ui.utils.resize
import com.vantra.music.utils.ArtistPageCache
import com.vantra.music.viewmodels.ArtistSimilarViewModel

/** "similar to <artist>": the artists around them, the playlists they're on, those artists' songs */
@Composable
fun ArtistSimilarScreen(
    navController: NavController,
    viewModel: ArtistSimilarViewModel = hiltViewModel(),
) {
    val page by viewModel.page.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val deepLoading by viewModel.deepLoading.collectAsState()
    val header = ArtistPageCache.header(viewModel.artistId)

    SimilarPage(
        navController = navController,
        bandImageUrl = header?.bannerUrl
            ?: (page?.artist?.thumbnail ?: viewModel.picks?.artistThumbnailUrl)?.resize(544, 544),
        smallLine = "similar to",
        bigLine = header?.name ?: page?.artist?.title ?: viewModel.picks?.artistName.orEmpty(),
        groups = groups,
        deepLoading = deepLoading,
        artistsTitle = "Similar Artist",
    )
}

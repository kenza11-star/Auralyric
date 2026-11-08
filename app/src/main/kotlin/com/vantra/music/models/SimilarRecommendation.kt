// SimilarRecommendation.kt

package com.vantra.music.models

import com.music.innertube.models.YTItem
import com.vantra.music.db.entities.LocalItem

data class SimilarRecommendation(
    val title: LocalItem,
    val items: List<YTItem>,
)

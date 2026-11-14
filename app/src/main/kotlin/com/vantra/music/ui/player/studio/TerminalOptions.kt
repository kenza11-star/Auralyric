package com.vantra.music.ui.player.studio

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.vantra.music.R
import com.vantra.music.constants.TerminalBeatKey
import com.vantra.music.constants.TerminalIntensity
import com.vantra.music.constants.TerminalIntensityKey
import com.vantra.music.constants.TerminalOrientation
import com.vantra.music.constants.TerminalOrientationKey
import com.vantra.music.constants.TerminalQuality
import com.vantra.music.constants.TerminalQualityKey
import com.vantra.music.constants.TerminalTypingKey
import com.vantra.music.ui.player.customize.CoverOption
import com.vantra.music.utils.rememberEnumPreference

// the terminal's sheet: how far the camera moves, whether it follows the beat, the typing, the quality and which way up it sits
@Composable
internal fun ColumnScope.TerminalOptions() {
    var intensity by rememberEnumPreference(TerminalIntensityKey, TerminalIntensity.NORMAL)
    var quality by rememberEnumPreference(TerminalQualityKey, TerminalQuality.AUTO)
    var orientation by rememberEnumPreference(TerminalOrientationKey, TerminalOrientation.AUTO)
    OptionSheet(title = "The terminal") {
        TerminalIntensity.entries.forEachIndexed { index, option ->
            OptionRow(
                selected = intensity == option,
                onClick = { intensity = option },
                caption = if (index == 0) "Camera movement" else null,
                compact = true,
            ) { TwoLines(option.title, option.description) }
        }
        SwitchRow(TerminalBeatOption)
        SwitchRow(TerminalTypingOption)
        TerminalQuality.entries.forEachIndexed { index, option ->
            OptionRow(
                selected = quality == option,
                onClick = { quality = option },
                caption = if (index == 0) "Quality" else null,
                compact = true,
            ) { TwoLines(option.title, option.description) }
        }
        TerminalOrientation.entries.forEachIndexed { index, option ->
            OptionRow(
                selected = orientation == option,
                onClick = { orientation = option },
                caption = if (index == 0) "Orientation" else null,
                compact = true,
            ) { TwoLines(option.title, option.description) }
        }
    }
}

private val TerminalBeatOption = CoverOption.Switch(
    title = "Beat-reactive camera",
    description = "kicks and bass move the camera, read from the audio being played",
    icon = R.drawable.graphic_eq,
    key = TerminalBeatKey,
    default = true,
)

private val TerminalTypingOption = CoverOption.Switch(
    title = "Typing animation",
    description = "each line types itself out in time with the lyrics",
    icon = R.drawable.lyrics,
    key = TerminalTypingKey,
    default = true,
)

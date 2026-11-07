package com.mochyy.mochymusic.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mochyy.mochymusic.R
import com.mochyy.mochymusic.constants.SYSTEM_DEFAULT
import com.mochyy.mochymusic.ui.component.DefaultDialog
import java.util.Locale

private data class LanguageOption(val tag: String, val nativeName: String, val englishName: String)

internal fun appLanguageNativeName(tag: String): String {
    if (tag == "id" || tag == "in") return "Bahasa Indonesia"
    val locale = Locale.forLanguageTag(tag)
    return locale.getDisplayName(locale).ifBlank { tag }
}

@Composable
internal fun AppLanguageDialog(
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val tags = stringArrayResource(R.array.app_language_tags)
    val options = remember(tags.toList()) {
        tags.map { tag ->
            LanguageOption(tag, appLanguageNativeName(tag), Locale.forLanguageTag(tag).getDisplayName(Locale.ENGLISH))
        }.sortedBy { it.nativeName.lowercase(Locale.ROOT) }
    }
    val systemDefault = stringResource(R.string.system_default)
    val followDevice = stringResource(R.string.app_language_follow_device)
    val needle = query.trim()
    val filtered = options.filter {
        needle.isBlank() || it.nativeName.contains(needle, ignoreCase = true) ||
            it.englishName.contains(needle, ignoreCase = true) || it.tag.contains(needle, ignoreCase = true)
    }
    val showSystem = needle.isBlank() || systemDefault.contains(needle, ignoreCase = true) ||
        followDevice.contains(needle, ignoreCase = true)
    val maxListHeight = (LocalConfiguration.current.screenHeightDp * 0.48f).dp

    DefaultDialog(
        onDismiss = onDismiss,
        title = { Text(stringResource(R.string.app_language)) },
        buttons = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) {
        Text(stringResource(R.string.app_language_incomplete))
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.app_language_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = maxListHeight).selectableGroup(),
        ) {
            if (showSystem) {
                item(key = SYSTEM_DEFAULT) {
                    LanguageRow(
                        title = systemDefault,
                        subtitle = followDevice,
                        selected = selected == SYSTEM_DEFAULT,
                        onClick = { onSelect(SYSTEM_DEFAULT) },
                    )
                }
            }
            items(filtered, key = { it.tag }) { option ->
                LanguageRow(
                    title = option.nativeName,
                    subtitle = null,
                    selected = Locale.forLanguageTag(selected).toLanguageTag() == Locale.forLanguageTag(option.tag).toLanguageTag(),
                    onClick = { onSelect(option.tag) },
                )
            }
        }
    }
}

@Composable
private fun LanguageRow(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

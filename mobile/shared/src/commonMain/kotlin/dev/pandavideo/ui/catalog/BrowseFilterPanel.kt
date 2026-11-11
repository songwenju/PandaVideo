package dev.pandavideo.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pandavideo.app.BrowseCountryOptions
import dev.pandavideo.app.BrowseGenreOptions
import dev.pandavideo.app.BrowseSortOptions
import dev.pandavideo.app.BrowseTypeOptions
import dev.pandavideo.app.browseYearOptions
import dev.pandavideo.core.model.BrowseFilters
import dev.pandavideo.ui.common.ChoiceRow
import dev.pandavideo.ui.common.Accent
import io.ktor.util.date.GMTDate

/** Applies the web annual-TV preset, including clearing any additional mobile region filter. */
internal fun annualTvFilters(year: Int): BrowseFilters =
    BrowseFilters(type = "tv", year = year.toString(), sort = "hot")

/** Fixed media tabs followed by horizontally scrollable genre, region, year and sorting rows. */
@Composable
internal fun BrowseFilterPanel(current: BrowseFilters, update: (BrowseFilters) -> Unit) {
    val year = GMTDate().year
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Four fixed-width tabs expose every supported media type without horizontal clipping.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BrowseTypeOptions.forEach { (key, label) ->
                FilterChoice(label, key == current.type, Modifier.weight(1f), tab = true) {
                    update(current.copy(type = key))
                }
            }
        }
        ChoiceRow(BrowseGenreOptions, current.genre) { update(current.copy(genre = it)) }
        ChoiceRow(BrowseCountryOptions, current.country) { update(current.copy(country = it)) }
        ChoiceRow(browseYearOptions(year), current.year) { update(current.copy(year = it)) }
        ChoiceRow(BrowseSortOptions, current.sort) { update(current.copy(sort = it)) }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun FilterChoice(
    label: String, selected: Boolean, modifier: Modifier = Modifier, tab: Boolean = false,
    select: () -> Unit,
) {
    Text(
        label, fontSize = if (tab) 14.sp else 13.sp, lineHeight = 20.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
        modifier = modifier.clip(RoundedCornerShape(8.dp))
            .background(if (selected) Accent.copy(alpha = 0.10f) else Color.Transparent)
            .selectable(selected, role = if (tab) Role.Tab else Role.RadioButton, onClick = select)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

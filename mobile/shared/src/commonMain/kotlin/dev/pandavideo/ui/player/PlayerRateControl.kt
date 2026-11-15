package dev.pandavideo.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pandavideo.ui.common.Accent
import dev.pandavideo.ui.common.ActionButtonPadding

/** Speeds exposed by every player rate menu, ascending and including normal speed. */
internal val PlaybackRates = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * Displays the same rate label and selection menu in inline and fullscreen playback.
 *
 * The owner retains popup state so its auto-hide timer can wait until selection finishes.
 * The displayed rate comes from the controller, keeping it current when the layout changes.
 */
@Composable
internal fun PlayerRateControl(
    rate: Float,
    expanded: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (Float) -> Unit,
    enabled: Boolean = true,
) {
    Box {
        TextButton(
            onClick = onOpen,
            enabled = enabled,
            modifier = Modifier.heightIn(min = 44.dp),
            contentPadding = ActionButtonPadding,
        ) {
            Text(
                if (rate == 1f) "倍速" else "${rate.stripRate()}×",
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
                fontSize = 16.sp, fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
        // A popup keeps the options outside the inline video's height constraints and
        // receives mouse input separately from the desktop native video surface.
        DropdownMenu(expanded, onDismiss, containerColor = Color(0xF21F1F1F)) {
            PlaybackRates.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${option.stripRate()}×", fontSize = 15.sp,
                            color = if (option == rate) Accent else Color.White,
                            fontWeight = if (option == rate) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = { onSelect(option); onDismiss() },
                )
            }
        }
    }
}

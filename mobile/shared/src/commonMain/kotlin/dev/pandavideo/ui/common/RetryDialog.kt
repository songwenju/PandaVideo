package dev.pandavideo.ui.common

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** Consistent recovery actions for failed user operations on every shared platform. */
@Composable
internal fun RetryDialog(title: String, message: String, retry: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { Text(message) },
        shape = RoundedCornerShape(24.dp),
        confirmButton = {
            Button(
                onClick = retry,
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
            ) { Text("重试") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } },
    )
}

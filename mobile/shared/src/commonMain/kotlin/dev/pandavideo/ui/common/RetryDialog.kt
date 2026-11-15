package dev.pandavideo.ui.common

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Consistent recovery actions for failed user operations on every shared platform. */
@Composable
internal fun RetryDialog(title: String, message: String, retry: () -> Unit, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold) },
        text = { Text(message, Modifier.verticalScroll(rememberScrollState()),
            fontSize = 14.sp, lineHeight = 21.sp) },
        shape = RoundedCornerShape(24.dp),
        confirmButton = {
            Button(
                onClick = retry,
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
            ) { Text("重试", fontSize = 14.sp) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消", fontSize = 14.sp) } },
    )
}

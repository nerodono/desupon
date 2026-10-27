package moe.desupon.feature.subscriptions.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import moe.desupon.feature.subscriptions.R
import moe.desupon.feature.subscriptions.qr.QrEncoder

@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismiss: String = stringResource(R.string.subs_cancel),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismiss) } },
    )
}

@Composable
internal fun DeleteDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) = ConfirmDialog(
    title = stringResource(R.string.subs_delete_title),
    text = stringResource(R.string.subs_delete_text, title),
    confirm = stringResource(R.string.subs_delete),
    onConfirm = onConfirm,
    onDismiss = onDismiss,
    destructive = true,
)

/** The subscription link as a QR code, with a warning that whoever scans it gets the subscription. */
@Composable
internal fun QrShareDialog(url: String, onDismiss: () -> Unit) {
    val bitmap = remember(url) { runCatching { QrEncoder.bitmap(url, QR_SIZE_PX).asImageBitmap() }.getOrNull() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subs_editor_qr_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.subs_editor_share_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = stringResource(R.string.subs_editor_qr_cd),
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    )
                } else {
                    Text(stringResource(R.string.subs_editor_qr_failed))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.subs_close)) } },
    )
}

private const val QR_SIZE_PX = 720

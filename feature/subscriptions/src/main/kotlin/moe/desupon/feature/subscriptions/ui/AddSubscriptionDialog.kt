package moe.desupon.feature.subscriptions.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.desupon.feature.subscriptions.AddDraft
import moe.desupon.feature.subscriptions.R
import moe.desupon.feature.subscriptions.UiText
import moe.desupon.feature.subscriptions.qr.QrDecoder
import moe.desupon.feature.subscriptions.qr.QrScannerDialog
import moe.desupon.feature.subscriptions.resolve

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AddSubscriptionDialog(
    draft: AddDraft,
    onTextChange: (String) -> Unit,
    onError: (UiText) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by rememberSaveable { mutableStateOf(false) }
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else onError(UiText.Res(R.string.subs_camera_denied))
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val decoded = withContext(Dispatchers.Default) {
                runCatching { QrDecoder.decodeImage(context.contentResolver, uri) }
            }
            decoded.fold(
                onSuccess = { text -> if (text != null) onTextChange(text) else onError(UiText.Res(R.string.subs_qr_not_found)) },
                onFailure = { onError(UiText.Res(R.string.subs_qr_image_error)) },
            )
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = !draft.submitting),
        title = { Text(stringResource(R.string.subs_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = draft.text,
                    onValueChange = onTextChange,
                    label = { Text(stringResource(R.string.subs_add_field)) },
                    placeholder = { Text(stringResource(R.string.subs_add_field_hint)) },
                    minLines = 3,
                    maxLines = 8,
                    enabled = !draft.submitting,
                    isError = draft.error != null,
                    supportingText = draft.error?.let { error -> { Text(error.resolve()) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(enabled = !draft.submitting, onClick = {
                        val text = readClipboard(context)
                        if (text.isNullOrBlank()) onError(UiText.Res(R.string.subs_add_clipboard_empty)) else onTextChange(text)
                    }) { Text(stringResource(R.string.subs_add_paste)) }
                    if (hasCamera) {
                        OutlinedButton(enabled = !draft.submitting, onClick = {
                            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                                PackageManager.PERMISSION_GRANTED
                            if (granted) scanning = true else cameraPermission.launch(Manifest.permission.CAMERA)
                        }) { Text(stringResource(R.string.subs_add_scan)) }
                    }
                    OutlinedButton(enabled = !draft.submitting, onClick = {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text(stringResource(R.string.subs_add_image)) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSubmit, enabled = !draft.submitting && draft.text.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (draft.submitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.subs_add_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !draft.submitting) { Text(stringResource(R.string.subs_cancel)) }
        },
    )

    if (scanning) {
        QrScannerDialog(
            onResult = { text ->
                scanning = false
                onTextChange(text)
            },
            onError = {
                scanning = false
                onError(UiText.Res(R.string.subs_camera_error))
            },
            onDismiss = { scanning = false },
        )
    }
}

internal fun readClipboard(context: Context): String? {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

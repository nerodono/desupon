package moe.desupon.feature.subscriptions.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/** Copies [text] flagged sensitive, so the system clipboard preview hides it. */
internal fun copySensitive(context: Context, text: String) {
    val clip = ClipData.newPlainText("", text)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

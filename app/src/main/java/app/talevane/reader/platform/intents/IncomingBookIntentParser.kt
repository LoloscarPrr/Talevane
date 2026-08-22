package app.talevane.reader.platform.intents

import android.content.Intent
import android.net.Uri
import android.os.Build

/** Android-only adapter that translates system intents into an opaque source URI for BookFlow. */
object IncomingBookIntentParser {
    fun sourceUri(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> sharedStreamUri(intent)
        else -> null
    }?.toString()?.takeIf { it.isNotBlank() }

    @Suppress("DEPRECATION")
    private fun sharedStreamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        } else {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        }
}

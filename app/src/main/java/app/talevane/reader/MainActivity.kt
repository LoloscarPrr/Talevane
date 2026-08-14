package app.talevane.reader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.talevane.reader.ui.TalevaneRoot

class MainActivity : ComponentActivity() {
    private var incomingBookUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 404)
        }

        handleIncomingBook(intent)

        val app = application as TalevaneApp
        setContent {
            TalevaneRoot(
                repository = app.repository,
                narrationGateway = app.narrationGateway,
                incomingBookUri = incomingBookUri,
                onIncomingBookHandled = { incomingBookUri = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingBook(intent)
    }

    private fun handleIncomingBook(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        incomingBookUri = intent.data
    }
}

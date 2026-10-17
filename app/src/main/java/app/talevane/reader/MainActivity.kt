package app.talevane.reader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import app.talevane.reader.platform.intents.IncomingBookIntentParser
import app.talevane.reader.platform.permissions.NotificationPermissionRequester
import app.talevane.reader.presentation.app.AppViewModel
import app.talevane.reader.presentation.app.AppViewModelFactory
import app.talevane.reader.ui.TalevaneRoot

class MainActivity : ComponentActivity() {
    private lateinit var appViewModel: AppViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        NotificationPermissionRequester.requestIfNeeded(this)

        val library = (application as TalevaneApp).repository
        appViewModel = ViewModelProvider(this, AppViewModelFactory(library))[AppViewModel::class.java]
        appViewModel.importExternalBook(IncomingBookIntentParser.sourceUri(intent))

        setContent {
            TalevaneRoot(
                library = library,
                appViewModel = appViewModel
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        appViewModel.importExternalBook(IncomingBookIntentParser.sourceUri(intent))
    }
}

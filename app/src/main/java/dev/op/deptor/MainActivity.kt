package dev.op.deptor

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import dev.op.deptor.ui.DeptorApp
import dev.op.deptor.ui.theme.DeptorTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Odkaz, se kterým byla aplikace otevřená.
     *
     * Drží se tady, ne v navigaci: pozvánka typicky dorazí někomu, kdo appku spouští
     * poprvé a ještě není přihlášený. V tu chvíli navigační graf vůbec neexistuje,
     * takže by se deep link zahodil a uživatel by po přihlášení skončil v prázdném
     * seznamu skupin, aniž by tušil proč.
     */
    private var pendingLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingLink = intent?.data?.toString()

        setContent {
            DeptorTheme {
                DeptorApp(
                    pendingLink = pendingLink,
                    onLinkHandled = { pendingLink = null },
                )
            }
        }
    }

    /** Odkaz otevřený, když už aplikace běží. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingLink = intent.data?.toString()
    }
}

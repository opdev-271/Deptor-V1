package dev.op.deptor.data

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Inicializace Firebase.
 *
 * Za normálních okolností se o ni postará plugin `google-services` podle
 * `app/google-services.json`. Dokud ten soubor neexistuje, `FirebaseApp.initializeApp`
 * vrátí `null` a každé pozdější `getInstance()` by shodilo aplikaci hned na startu.
 *
 * V debug buildu se proto v takovém případě Firebase nastaví ručně na lokální emulátor
 * — stejným způsobem jako v testech. Aplikace jde díky tomu rozchodit a proklikat ještě
 * předtím, než vůbec vznikne Firebase projekt.
 *
 * V release buildu se nic takového neděje: chybějící konfigurace je tam skutečná chyba
 * a je lepší, aby se projevila, než aby release verze potichu mířila na localhost.
 */
object FirebaseBootstrap {

    private const val TAG = "FirebaseBootstrap"
    private const val EMULATOR_HOST = "10.0.2.2"
    private const val FIRESTORE_PORT = 8080
    private const val AUTH_PORT = 9099

    /** @return true, pokud aplikace běží proti lokálnímu emulátoru. */
    fun initialize(context: Context, allowEmulatorFallback: Boolean): Boolean {
        if (FirebaseApp.initializeApp(context) != null) return false

        check(allowEmulatorFallback) {
            "Chybí app/google-services.json — bez něj se Firebase nedá inicializovat. Viz SETUP.md."
        }

        Log.w(TAG, "google-services.json chybí, používám lokální emulátor na $EMULATOR_HOST")

        FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setProjectId("demo-deptor")
                .setApplicationId("1:0:android:0")
                .setApiKey("fake-api-key-for-emulator")
                .build(),
        )

        FirebaseFirestore.getInstance().useEmulator(EMULATOR_HOST, FIRESTORE_PORT)
        FirebaseAuth.getInstance().useEmulator(EMULATOR_HOST, AUTH_PORT)
        return true
    }
}

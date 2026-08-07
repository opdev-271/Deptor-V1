package dev.op.deptor.data

import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.memoryCacheSettings

/**
 * Firebase namířený na lokální emulátor.
 *
 * Nepotřebuje `google-services.json` ani skutečný Firebase projekt: [FirebaseOptions]
 * se sestaví ručně a projectId s prefixem `demo-` emulátor pozná jako demo projekt,
 * který se nikdy nepokouší volat produkční službu. Testy tedy běží bez jakéhokoli účtu.
 *
 * Hostitel je `10.0.2.2`, protože z pohledu Android emulátoru je to loopback vývojového
 * stroje — `localhost` by ukazoval na samotné zařízení.
 */
object EmulatorFirebase {

    const val PROJECT_ID = "demo-deptor"
    private const val HOST = "10.0.2.2"
    private const val FIRESTORE_PORT = 8080
    private const val AUTH_PORT = 9099

    private var app: FirebaseApp? = null

    fun app(): FirebaseApp = app ?: synchronized(this) {
        app ?: create().also { app = it }
    }

    private fun create(): FirebaseApp {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val options = FirebaseOptions.Builder()
            .setProjectId(PROJECT_ID)
            .setApplicationId("1:0:android:0")
            .setApiKey("fake-api-key-for-emulator")
            .build()

        val instance = FirebaseApp.getApps(context)
            .firstOrNull { it.name == NAME }
            ?: FirebaseApp.initializeApp(context, options, NAME)

        FirebaseFirestore.getInstance(instance).apply {
            useEmulator(HOST, FIRESTORE_PORT)
            // Testy chtějí čistý stav; trvalá cache by data protahovala mezi běhy.
            firestoreSettings = firestoreSettings {
                setLocalCacheSettings(memoryCacheSettings { })
            }
        }
        FirebaseAuth.getInstance(instance).useEmulator(HOST, AUTH_PORT)

        return instance
    }

    fun firestore(): FirebaseFirestore = FirebaseFirestore.getInstance(app())

    fun auth(): FirebaseAuth = FirebaseAuth.getInstance(app())

    private const val NAME = "deptor-emulator"
}

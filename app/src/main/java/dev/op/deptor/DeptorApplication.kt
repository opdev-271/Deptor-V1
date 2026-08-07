package dev.op.deptor

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.op.deptor.data.FirebaseBootstrap

@HiltAndroidApp
class DeptorApplication : Application() {

    /** Nastavené jen v debug buildu bez `google-services.json`, viz [FirebaseBootstrap]. */
    var runningOnEmulator: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        runningOnEmulator = FirebaseBootstrap.initialize(
            context = this,
            allowEmulatorFallback = BuildConfig.DEBUG,
        )
    }
}

package dev.op.deptor.data

import java.net.HttpURLConnection
import java.net.URL

/**
 * Administrativní REST rozhraní emulátoru — maže data mezi testy.
 *
 * Jde vědomě mimo Firestore SDK: mazání přes SDK by podléhalo security rules a muselo
 * by se protloukat po jednom dokumentu. Tenhle endpoint smaže vše naráz a existuje
 * pouze v emulátoru, takže se nemá jak dostat do produkce.
 */
object EmulatorAdmin {

    private const val FIRESTORE = "http://10.0.2.2:8080"
    private const val AUTH = "http://10.0.2.2:9099"

    fun clearFirestore() {
        delete("$FIRESTORE/emulator/v1/projects/${EmulatorFirebase.PROJECT_ID}/databases/(default)/documents")
    }

    fun clearAuthUsers() {
        delete("$AUTH/emulator/v1/projects/${EmulatorFirebase.PROJECT_ID}/accounts")
    }

    /**
     * Úklid se opakuje, protože emulátor vrací 409, když v tu chvíli ještě dobíhá
     * aktivita předchozího testu — typicky odesílání zápisů, které vznikly offline.
     * Bez opakování by testy padaly podle toho, jak rychle stroj zrovna stíhá.
     */
    private fun delete(url: String) {
        var lastCode = -1
        for (attempt in 0 until MAX_ATTEMPTS) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = 10_000
                readTimeout = 10_000
                // Emulátor autorizaci neřeší, hlavičku ale vyžaduje.
                setRequestProperty("Authorization", "Bearer owner")
            }
            lastCode = try {
                connection.responseCode
            } finally {
                connection.disconnect()
            }

            if (lastCode in 200..299) return
            if (lastCode != HTTP_CONFLICT) break
            Thread.sleep(RETRY_DELAY_MILLIS * (attempt + 1))
        }
        error("Emulátor odmítl úklid: HTTP $lastCode pro $url")
    }

    private const val HTTP_CONFLICT = 409
    private const val MAX_ATTEMPTS = 5
    private const val RETRY_DELAY_MILLIS = 300L
}

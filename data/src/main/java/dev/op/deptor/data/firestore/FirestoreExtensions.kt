package dev.op.deptor.data.firestore

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Realtime listener jako [Flow].
 *
 * `awaitClose` odregistruje listener, jakmile odběratel skončí — bez toho by listener
 * běžel dál, držel referenci na scope a při každé změně budil síť i pro obrazovku,
 * kterou uživatel dávno zavřel.
 *
 * Chyba se do proudu propouští jako výjimka, aby ji ViewModel mohl zobrazit; typicky
 * jde o `PERMISSION_DENIED`, když uživatel přestal být členem skupiny.
 */
fun Query.snapshots(): Flow<QuerySnapshot> = callbackFlow {
    val registration = addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
        when {
            error != null -> close(error)
            snapshot != null -> trySend(snapshot)
        }
    }
    awaitClose { registration.remove() }
}

/**
 * Odbaví zápis bez čekání na potvrzení serverem.
 *
 * **Na `await()` u zápisu se čekat nesmí.** Task z `set()`, `update()` a `delete()`
 * se dokončí až tehdy, když zápis potvrdí server — bez sítě tedy nikdy. Kdyby na něj
 * repozitář čekal, přidání výdaje v offline režimu by zamrzlo na spinneru, přestože
 * Firestore data okamžitě uloží do lokální cache a listener je hned vrátí do UI.
 * Offline režim ze zadání (bod 8) stojí přesně na tomhle rozdílu.
 *
 * Cenou je, že se pozdější selhání (typicky PERMISSION_DENIED) uživateli nedostane —
 * zatím jen do logu. Až bude potřeba je ukazovat, patří sem kanál chyb do UI.
 */
internal fun Task<*>.reportFailureAsync(operation: String) {
    addOnFailureListener { error ->
        Log.e("Firestore", "Zápis '$operation' se nepodařilo odeslat na server", error)
    }
}

/**
 * Totéž pro jeden dokument. Vrací `null`, když dokument neexistuje — smazaná skupina
 * je legitimní stav, ne chyba.
 */
fun DocumentReference.snapshotsOrNull(): Flow<DocumentSnapshot?> = callbackFlow {
    val registration = addSnapshotListener(MetadataChanges.EXCLUDE) { snapshot, error ->
        when {
            error != null -> close(error)
            else -> trySend(snapshot?.takeIf { it.exists() })
        }
    }
    awaitClose { registration.remove() }
}

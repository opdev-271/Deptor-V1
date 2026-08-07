package dev.op.deptor.ui.invite

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.op.deptor.domain.invite.Invite
import dev.op.deptor.domain.invite.InviteLink
import dev.op.deptor.ui.common.generateQrCode

/**
 * Sdílení pozvánky odkazem nebo QR kódem.
 *
 * Odkaz **je oprávnění ke vstupu** — kdo ho má, může do skupiny. Proto je u něj
 * napsané, komu ho posílat, a ne jen holé „sdílet".
 */
@Composable
fun ShareInviteDialog(
    groupName: String,
    invite: Invite,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val link = remember(invite) { InviteLink.build(invite) }
    val qrCode = remember(link) { generateQrCode(link, QR_SIZE_PX) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pozvat do skupiny") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (qrCode != null) {
                    Image(
                        bitmap = qrCode,
                        contentDescription = "QR kód s pozvánkou do skupiny $groupName",
                        modifier = Modifier.size(220.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                }

                Text(
                    text = "Kdo odkaz nebo QR kód dostane, může do skupiny vstoupit. " +
                        "Posílej ho jen lidem, které tam chceš.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "Přidej se ke skupině \u201A$groupName\u2018 v Deptoru: $link",
                        )
                    }
                    context.startActivity(Intent.createChooser(share, "Poslat pozvánku"))
                    onDismiss()
                },
            ) {
                Text("Poslat odkaz")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Zavřít") }
        },
    )
}

/** Pevná velikost v pixelech — QR je bitmapa, ne vektor. */
private const val QR_SIZE_PX = 640

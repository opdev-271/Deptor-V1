package dev.op.deptor.ui.invite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.ui.common.EmptyState

@Composable
fun JoinGroupScreen(
    onJoined: (GroupId) -> Unit,
    onCancel: () -> Unit,
    viewModel: JoinGroupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.linkInvalid) {
        EmptyState(
            icon = Icons.Default.Warning,
            title = "Pozvánka nedává smysl",
            description = "Odkaz je neúplný nebo poškozený. Nech si od někoho ze skupiny " +
                "poslat nový.",
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Pozvánka do skupiny", style = MaterialTheme.typography.headlineSmall)
        Text(
            // Název skupiny tu vidět není: dokud uživatel nevstoupí, nemá na skupinu
            // právo, takže si ji nemá jak přečíst.
            text = "Pod jakým jménem chceš ve skupině vystupovat?",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::onNameChange,
            label = { Text("Jméno") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        state.error?.let { error ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { viewModel.join(onJoined) },
            enabled = state.canJoin,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.joining) {
                CircularProgressIndicator(Modifier.height(20.dp))
            } else {
                Text("Vstoupit do skupiny")
            }
        }

        TextButton(onClick = onCancel, enabled = !state.joining) {
            Text("Teď ne")
        }
    }
}

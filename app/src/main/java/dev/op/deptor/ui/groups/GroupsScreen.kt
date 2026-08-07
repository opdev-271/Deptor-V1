package dev.op.deptor.ui.groups

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.op.deptor.domain.model.CurrencyCode
import dev.op.deptor.domain.model.Group
import dev.op.deptor.domain.model.GroupId
import dev.op.deptor.ui.common.EmptyState
import dev.op.deptor.ui.common.LoadingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(
    onOpenGroup: (GroupId) -> Unit,
    viewModel: GroupsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Moje skupiny") },
                actions = {
                    IconButton(onClick = viewModel::signOut) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Odhlásit se")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Nová skupina")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val groups = state.groups

        when {
            groups == null -> LoadingState(Modifier.padding(padding))

            groups.isEmpty() -> EmptyState(
                icon = Icons.Default.Group,
                title = "Zatím žádná skupina",
                description = "Založ skupinu pro výlet, domácnost nebo cokoli, co s někým platíš dohromady.",
                modifier = Modifier.padding(padding),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
            ) {
                items(groups, key = { it.id.value }) { group ->
                    GroupCard(group = group, onClick = { onOpenGroup(group.id) })
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateGroupDialog(
            busy = state.creating,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, currency ->
                viewModel.createGroup(name, currency) { id ->
                    showCreateDialog = false
                    onOpenGroup(id)
                }
            },
        )
    }
}

@Composable
private fun GroupCard(group: Group, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(group.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = memberSummary(group),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun memberSummary(group: Group): String = when (val count = group.members.size) {
    0 -> "Zatím bez členů · ${group.currency.code}"
    1 -> "1 člen · ${group.currency.code}"
    in 2..4 -> "$count členové · ${group.currency.code}"
    else -> "$count členů · ${group.currency.code}"
}

@Composable
private fun CreateGroupDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, CurrencyCode) -> Unit,
) {
    var name by remember { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Nová skupina") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Název") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onCreate(name, CurrencyCode.CZK) },
                enabled = name.isNotBlank() && !busy,
            ) {
                Text("Vytvořit")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss, enabled = !busy) {
                Text("Zrušit")
            }
        },
    )
}

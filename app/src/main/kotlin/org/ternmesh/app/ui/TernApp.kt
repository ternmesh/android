// The app's frame: the three tabs, the conversation over them, and a line saying where the link to
// the node is whenever it is not simply connected.
package org.ternmesh.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import org.ternmesh.app.R
import org.ternmesh.app.node.NodeRepository
import org.ternmesh.app.node.NodeState
import org.ternmesh.app.node.Notifier
import org.ternmesh.app.node.Phase
import org.ternmesh.companion.Outcome
import org.ternmesh.companion.Peer

/** Shows a short message at the foot of the screen. */
val LocalSay = staticCompositionLocalOf<(String) -> Unit> { {} }

/** What a request came to, said briefly when it did not simply work. */
@Composable
fun rememberReport(): (Outcome) -> Unit {
    val say = LocalSay.current
    val context = LocalContext.current
    return remember(say) { { o: Outcome -> if (o !is Outcome.Answered) say(outcomeText(context, o)) } }
}

private enum class Tab(val route: String, val label: Int, val icon: ImageVector) {
    CHATS("chats", R.string.tab_chats, Icons.Filled.Email),
    CONTACTS("contacts", R.string.tab_contacts, Icons.Filled.AccountCircle),
    NODE("node", R.string.tab_node, Icons.Filled.Settings),
}

@Composable
fun TernApp(repository: NodeRepository, opening: String?, opened: () -> Unit) {
    val state by repository.state.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val say: (String) -> Unit = remember { { text -> scope.launch { snackbar.showSnackbar(text) } } }
    val context = LocalContext.current
    var permitted by remember { mutableStateOf(MainActivity.hasBluetoothPermissions(context)) }
    LifecycleResumeEffect(Unit) {
        permitted = MainActivity.hasBluetoothPermissions(context)
        onPauseOrDispose {}
    }

    LaunchedEffect(opening, state.node) {
        val peer = opening?.let(Notifier::peer)
        if (peer != null && state.node != null) {
            nav.navigate(chatRoute(peer)) { launchSingleTop = true }
            opened()
        }
    }

    CompositionLocalProvider(LocalSay provides say) {
        if (state.node != null && !permitted) {
            // Permission taken away after a node was chosen: ask again, then pick the link back up.
            Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                PermissionPrompt(Modifier.padding(padding)) {
                    permitted = true
                    repository.resume()
                }
            }
            return@CompositionLocalProvider
        }
        if (state.node == null) {
            Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                ConnectScreen(repository, state, Modifier.padding(padding))
            }
            return@CompositionLocalProvider
        }
        val back by nav.currentBackStackEntryAsState()
        val route = back?.destination?.route
        val onTab = Tab.entries.any { it.route == route }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (onTab) {
                    NavigationBar {
                        for (tab in Tab.entries) {
                            NavigationBarItem(
                                selected = route == tab.route,
                                onClick = {
                                    nav.navigate(tab.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(stringResource(tab.label)) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                LinkBanner(repository, state)
                NavHost(nav, startDestination = Tab.CHATS.route, modifier = Modifier.weight(1f)) {
                    composable(Tab.CHATS.route) { ChatsScreen(repository, state) { nav.navigate(chatRoute(it)) } }
                    composable(Tab.CONTACTS.route) { ContactsScreen(repository, state) { nav.navigate(chatRoute(it)) } }
                    composable(Tab.NODE.route) { NodeScreen(repository, state) }
                    composable("chat/{peer}") { entry ->
                        val peer = entry.arguments?.getString("peer")?.let(Notifier::peer)
                        if (peer != null) ChatScreen(repository, state, peer) { nav.popBackStack() }
                    }
                }
            }
        }
    }
}

fun chatRoute(peer: Peer) = "chat/${Notifier.key(peer)}"

/** Where the link is, unless it is simply up. */
@Composable
private fun LinkBanner(repository: NodeRepository, state: NodeState) {
    if (state.phase == Phase.READY) return
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(phaseText(context, state), style = MaterialTheme.typography.labelLarge)
                state.problem?.let { Text(problemText(context, it), style = MaterialTheme.typography.bodySmall) }
            }
            if (state.phase == Phase.STOPPED) {
                TextButton(onClick = repository::retry) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}

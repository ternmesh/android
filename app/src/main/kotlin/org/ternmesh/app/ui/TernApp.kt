// The app's frame: the four tabs (chats, people, the map and the node), the conversation over them, and a line saying where the link to
// the node is whenever it is not simply connected.
package org.ternmesh.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import org.ternmesh.app.link.BleLink
import org.ternmesh.app.link.LinkFailure
import org.ternmesh.app.node.NodeRepository
import org.ternmesh.app.node.NodeState
import org.ternmesh.app.node.Notifier
import org.ternmesh.app.node.Phase
import org.ternmesh.app.node.Problem
import org.ternmesh.app.node.stayingOff
import org.ternmesh.companion.Address
import org.ternmesh.companion.Conversations
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
    CHATS("chats", R.string.tab_chats, TernIcons.Chats),
    PEOPLE("people", R.string.tab_people, TernIcons.People),
    MAP("map", R.string.tab_map, TernIcons.Map),
    NODE("node", R.string.tab_node, TernIcons.Node),
}

@Composable
fun TernApp(
    repository: NodeRepository,
    opening: String?,
    incoming: Address?,
    joining: String?,
    opened: () -> Unit,
    taken: () -> Unit,
    joined: () -> Unit,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    TernApp(repository, state, opening, incoming, joining, opened, taken, joined)
}

/**
 * The frame for a given [state], which is the repository's as it changes. The store screenshots
 * (app/src/test) give it one of their own, so that the screens they draw are the app's.
 */
@Composable
internal fun TernApp(
    repository: NodeRepository,
    state: NodeState,
    opening: String? = null,
    incoming: Address? = null,
    joining: String? = null,
    opened: () -> Unit = {},
    taken: () -> Unit = {},
    joined: () -> Unit = {},
) {
    val nav = rememberNavController()
    val goTab: (Tab) -> Unit = { tab ->
        nav.navigate(tab.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val say: (String) -> Unit = remember { { text -> scope.launch { snackbar.showSnackbar(text) } } }
    val context = LocalContext.current
    var permitted by remember { mutableStateOf(MainActivity.hasBluetoothPermissions(context)) }
    LifecycleResumeEffect(Unit) {
        permitted = MainActivity.hasBluetoothPermissions(context)
        onPauseOrDispose {}
    }

    // Only once the tabs, and with them the navigation graph, are on screen: until permission is
    // given the conversation waits.
    LaunchedEffect(opening, state.node, permitted) {
        val peer = opening?.let(Notifier::peer)
        if (peer != null && state.node != null && permitted) {
            nav.navigate(chatRoute(peer)) { launchSingleTop = true }
            opened()
        }
    }
    // A link to add: to the contacts, where the dialog takes it.
    LaunchedEffect(incoming, state.node, permitted) {
        if (incoming != null && state.node != null && permitted) {
            nav.navigate(Tab.PEOPLE.route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
            }
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
        if (needsSetup(state)) {
            Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                SetupScreen(repository, state, Modifier.padding(padding))
            }
            return@CompositionLocalProvider
        }
        val unread = remember(state.records) { Conversations.of(state.records).sumOf { it.unread } }
        val back by nav.currentBackStackEntryAsState()
        val route = back?.destination?.route
        val onTab = Tab.entries.any { it.route == route }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (onTab) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                        for (tab in Tab.entries) {
                            NavigationBarItem(
                                selected = route == tab.route,
                                onClick = { goTab(tab) },
                                icon = {
                                    if (tab == Tab.CHATS && unread > 0) {
                                        BadgedBox(badge = { Badge(containerColor = MaterialTheme.colorScheme.error) { Text(if (unread > 99) "99+" else "$unread") } }) {
                                            Icon(tab.icon, contentDescription = null)
                                        }
                                    } else {
                                        Icon(tab.icon, contentDescription = null)
                                    }
                                },
                                label = { Text(stringResource(tab.label)) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                ),
                            )
                        }
                    }
                }
            },
        ) { padding ->
            // A join code opened from elsewhere: offered once the node has said what it speaks.
            if (joining != null && state.version != null) {
                if (hasJoinCodes(state)) {
                    JoinDialog(repository, state, joining, onDismiss = joined) { nav.navigate(chatRoute(it)) }
                } else {
                    val older = stringResource(R.string.join_code_needs_update)
                    LaunchedEffect(joining) {
                        say(older)
                        joined()
                    }
                }
            }
            Column(Modifier.fillMaxSize().padding(padding)) {
                LinkBanner(repository, state)
                DefaultPasskeyBanner(repository, state)
                NavHost(nav, startDestination = Tab.CHATS.route, modifier = Modifier.weight(1f)) {
                    val toNode = { goTab(Tab.NODE) }
                    val back: () -> Unit = { nav.popBackStack() }
                    composable(Tab.CHATS.route) { ChatsScreen(repository, state, toNode) { nav.navigate(chatRoute(it)) } }
                    composable(Tab.PEOPLE.route) {
                        PeopleScreen(repository, state, incoming, taken, toNode, manage = { nav.navigate(manageRoute(it)) }) { nav.navigate(chatRoute(it)) }
                    }
                    composable(Tab.MAP.route) { MapScreen(repository, state, toNode) }
                    composable(Tab.NODE.route) { NodeScreen(repository, state) { nav.navigate(it.route) } }
                    composable(NodePage.ADDRESS.route) { AddressScreen(state, back) }
                    composable(NodePage.NEIGHBOURS.route) { NeighboursScreen(repository, state, back) }
                    composable(NodePage.RADIO.route) { RadioScreen(repository, state, back) }
                    composable(NodePage.PRIVACY.route) { PrivacyScreen(repository, state, back) }
                    composable(NodePage.PIN.route) { PinScreen(repository, state, back) }
                    composable(NodePage.ABOUT.route) { AboutScreen(repository, state, back) }
                    composable("manage/{address}") { entry ->
                        val address = entry.arguments?.getString("address")?.let(Address::fromHex)
                        if (address != null) ManageScreen(repository, state, address, back)
                    }
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

/** The Manage screen of the node at [address], managed over the mesh. */
fun manageRoute(address: Address) = "manage/$address"

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
                if (state.lockedOut) {
                    Text(stringResource(R.string.bluetooth_locked_title), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.bluetooth_locked_explained), style = MaterialTheme.typography.bodySmall)
                }
                if (state.phase == Phase.DISCONNECTED) {
                    Text(stringResource(R.string.disconnected_explained), style = MaterialTheme.typography.bodySmall)
                }
                if (state.phase == Phase.OFF) {
                    Text(stringResource(R.string.off_explained), style = MaterialTheme.typography.bodySmall)
                }
                if (state.phase == Phase.BOOTLOADER) {
                    Text(stringResource(R.string.bootloader_explained), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.phase.stayingOff) {
                TextButton(onClick = repository::connect) { Text(stringResource(R.string.connect)) }
            }
            if (state.phase == Phase.STOPPED) {
                if (state.problem == Problem.Link(LinkFailure.BLUETOOTH_OFF)) {
                    // Bluetooth off: turning it on is the way back, and the link is tried again after.
                    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                        if (BleLink.isOn(context)) repository.connect()
                    }
                    TextButton(onClick = { enable.launch(BleLink.enableIntent()) }) {
                        Text(stringResource(R.string.connect_turn_on))
                    }
                } else {
                    TextButton(onClick = repository::connect) { Text(stringResource(R.string.retry)) }
                }
            }
        }
    }
}

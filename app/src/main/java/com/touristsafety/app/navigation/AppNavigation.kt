package com.touristsafety.app.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.touristsafety.app.AppContainer
import com.touristsafety.app.authority.AuthoritySession
import com.touristsafety.app.authority.OfficerSession
import com.touristsafety.app.ui.screens.AlertHistoryScreen
import com.touristsafety.app.ui.screens.AuthorityScreen
import com.touristsafety.app.ui.screens.EmergencyContactsScreen
import com.touristsafety.app.ui.screens.GeoFenceEditorScreen
import com.touristsafety.app.ui.screens.HomeScreen
import com.touristsafety.app.ui.screens.ManageZonesScreen
import com.touristsafety.app.ui.screens.OfficerScreen
import com.touristsafety.app.ui.screens.SettingsScreen
import com.touristsafety.app.ui.screens.SimulationScreen
import com.touristsafety.app.ui.screens.TouristAuthScreen
import com.touristsafety.app.ui.screens.TrackingScreen
import com.touristsafety.app.ui.screens.WatchScreen
import com.touristsafety.app.ui.screens.WelcomeScreen

/** Every route in the app, in one place so a typo cannot silently create a dead destination. */
object AppRoutes {

    /**
     * The role chooser. The app starts here unless a valid tourist session is already stored, so the
     * first question anyone is asked is the one that actually matters: tourist or officer?
     */
    const val WELCOME = "welcome"

    /** Tourist sign-in and self-registration. */
    const val TOURIST_AUTH = "tourist-auth"

    /** Officer sign-in, then the register of tourists. */
    const val OFFICER = "officer"

    const val HOME = "home"
    const val TRACKING = "tracking"
    const val ZONES = "zones"
    const val ALERTS = "alerts"
    const val SETTINGS = "settings"
    const val SIMULATION = "simulation"
    const val WATCH = "watch"
    const val AUTHORITY = "authority"
    const val CONTACTS = "contacts"

    const val EDITOR_ARG_ZONE_ID = "zoneId"
    private const val EDITOR_BASE = "editor"

    /** `zoneId = -1` means "create"; anything else means "edit that zone". */
    fun editor(zoneId: Long? = null): String =
        "$EDITOR_BASE?$EDITOR_ARG_ZONE_ID=${zoneId ?: NEW_ZONE_ID}"

    const val EDITOR_PATTERN = "$EDITOR_BASE?$EDITOR_ARG_ZONE_ID={$EDITOR_ARG_ZONE_ID}"
    const val NEW_ZONE_ID = -1L
}

private data class BottomDestination(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    /** Route prefixes that should light this item up, so the editor maps to "Create". */
    val matchPrefixes: List<String>,
)

/**
 * The tourist's bottom bar: Home, Tracking, Safety Alerts, Profile/Settings.
 *
 * There is deliberately **no Create or Zones tab**. Restricted areas are published by the authority;
 * a tourist may see them on the map and be warned by them, but cannot create, edit, pause or delete
 * one. The zone-management screens still exist (the officer console opens them) and are gated on an
 * authenticated officer session, but they are not part of the tourist's navigation.
 */
object TouristNavigation {

    /** The routes the tourist's bottom bar may reach. Plain strings, so a JVM test can assert them. */
    val tabRoutes: List<String> = listOf(
        AppRoutes.HOME,
        AppRoutes.TRACKING,
        AppRoutes.ALERTS,
        AppRoutes.SETTINGS,
    )

    /**
     * Routes that manage restricted zones. Authority-only by role, and never a tourist tab. `editor`
     * is a prefix (`editor?zoneId=…`) rather than a full route, which is why it is matched by prefix.
     */
    val authorityZoneRoutes: List<String> = listOf(AppRoutes.ZONES, "editor")

    fun isTouristTab(route: String?): Boolean = route in tabRoutes
}

private val touristTabSpecs:
    Map<String, Pair<String, androidx.compose.ui.graphics.vector.ImageVector>> = linkedMapOf(
    AppRoutes.HOME to ("Home" to Icons.Filled.Home),
    AppRoutes.TRACKING to ("Tracking" to Icons.Filled.MyLocation),
    AppRoutes.ALERTS to ("Alerts" to Icons.Filled.Notifications),
    AppRoutes.SETTINGS to ("Settings" to Icons.Filled.Settings),
)

/**
 * Built from [TouristNavigation.tabRoutes] rather than listed again, so the bar and the tested
 * declaration cannot drift: adding a tab to one and not the other is a start-up failure, not a
 * silent regression.
 */
private val bottomDestinations: List<BottomDestination> = TouristNavigation.tabRoutes.map { route ->
    val spec = requireNotNull(touristTabSpecs[route]) { "no bottom-bar spec for route '$route'" }
    BottomDestination(route, spec.first, spec.second, listOf(route))
}

/** Routes that are part of entering the app rather than using it: no tab bar, no shortcut icons. */
private val onboardingRoutes = setOf(AppRoutes.WELCOME, AppRoutes.TOURIST_AUTH, AppRoutes.OFFICER)

/**
 * The app's navigation graph.
 *
 * The start destination depends on whether this phone already holds a valid **tourist** session: a
 * returning tourist goes straight to their safety home, everyone else is asked who they are. That
 * gate is resolved before the graph is built, so there is no flash of the wrong screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    container: AppContainer,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val selectedItem = bottomDestinations.firstOrNull { destination ->
        destination.matchPrefixes.any { prefix -> currentRoute?.startsWith(prefix) == true }
    }

    val touristSession by container.authoritySessionStore.session
        .collectAsStateWithLifecycle(initialValue = AuthoritySession())

    // Resolved once, before the NavHost exists, so the first screen is the right one.
    var startRoute by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val restored = container.authoritySessionStore.current()
        startRoute = if (restored.isSignedIn(System.currentTimeMillis())) {
            AppRoutes.HOME
        } else {
            AppRoutes.WELCOME
        }
    }

    val start = startRoute
    if (start == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val signedIn = touristSession.isSignedIn(System.currentTimeMillis())
    val onOnboarding = currentRoute in onboardingRoutes

    /** Wipe the back stack: used for both finishing a sign-in and finishing a sign-out. */
    fun resetTo(route: String) {
        navController.navigate(route) {
            popUpTo(0) { inclusive = true }
            launchSingleTop = true
        }
    }

    Scaffold(
        topBar = {
            when {
                currentRoute == AppRoutes.WELCOME -> Unit

                onOnboarding -> {
                    CenterAlignedTopAppBar(
                        title = { Text(titleFor(currentRoute)) },
                        navigationIcon = {
                            TextButton(onClick = { navController.popBackStack() }) {
                                Text("Back")
                            }
                        },
                    )
                }

                else -> {
                    CenterAlignedTopAppBar(
                        title = { Text(titleFor(currentRoute)) },
                        actions = {
                            if (currentRoute != AppRoutes.WATCH) {
                                IconButton(onClick = { navController.navigate(AppRoutes.WATCH) }) {
                                    Icon(Icons.Filled.Watch, contentDescription = "Simulated smartwatch")
                                }
                            }
                            if (currentRoute != AppRoutes.SETTINGS) {
                                IconButton(onClick = { navController.navigate(AppRoutes.SETTINGS) }) {
                                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                                }
                            }
                        },
                    )
                }
            }
        },
        bottomBar = {
            if (!onOnboarding) {
                NavigationBar {
                    bottomDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = selectedItem?.route == destination.route,
                            onClick = { navigateToTab(navController, destination.route) },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = start,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            composable(AppRoutes.WELCOME) {
                WelcomeScreen(
                    resumeName = if (signedIn) {
                        touristSession.displayName?.takeIf { it.isNotBlank() }
                            ?: touristSession.subjectId
                    } else {
                        null
                    },
                    onResume = { resetTo(AppRoutes.HOME) },
                    onTourist = { navController.navigate(AppRoutes.TOURIST_AUTH) },
                    onOfficer = { navController.navigate(AppRoutes.OFFICER) },
                )
            }

            composable(AppRoutes.TOURIST_AUTH) {
                TouristAuthScreen(
                    container = container,
                    onSignedIn = { resetTo(AppRoutes.HOME) },
                    onOpenBackendSettings = { navController.navigate(AppRoutes.AUTHORITY) },
                )
            }

            composable(AppRoutes.OFFICER) {
                OfficerScreen(
                    container = container,
                    onOpenBackendSettings = { navController.navigate(AppRoutes.AUTHORITY) },
                    onManageZones = { navController.navigate(AppRoutes.ZONES) },
                )
            }

            composable(AppRoutes.HOME) {
                HomeScreen(
                    container = container,
                    onOpenTracking = { navigateToTab(navController, AppRoutes.TRACKING) },
                    onOpenAlerts = { navigateToTab(navController, AppRoutes.ALERTS) },
                    onOpenContacts = { navController.navigate(AppRoutes.CONTACTS) },
                    onOpenSettings = { navController.navigate(AppRoutes.SETTINGS) },
                    onSignedOut = { resetTo(AppRoutes.WELCOME) },
                )
            }

            composable(AppRoutes.TRACKING) {
                TrackingScreen(container = container)
            }

            composable(
                route = AppRoutes.EDITOR_PATTERN,
                arguments = listOf(
                    navArgument(AppRoutes.EDITOR_ARG_ZONE_ID) {
                        type = NavType.LongType
                        defaultValue = AppRoutes.NEW_ZONE_ID
                    },
                ),
            ) { entry ->
                val officer by container.authoritySessionStore.officerSession
                    .collectAsStateWithLifecycle(initialValue = OfficerSession())
                if (officer.isSignedIn(System.currentTimeMillis())) {
                    val rawId = entry.arguments?.getLong(AppRoutes.EDITOR_ARG_ZONE_ID)
                        ?: AppRoutes.NEW_ZONE_ID
                    GeoFenceEditorScreen(
                        container = container,
                        zoneId = rawId.takeIf { it != AppRoutes.NEW_ZONE_ID },
                        onFinished = { navController.popBackStack() },
                    )
                } else {
                    AuthorityRequired(onGoToOfficer = { navController.navigate(AppRoutes.OFFICER) })
                }
            }

            composable(AppRoutes.ZONES) {
                val officer by container.authoritySessionStore.officerSession
                    .collectAsStateWithLifecycle(initialValue = OfficerSession())
                if (officer.isSignedIn(System.currentTimeMillis())) {
                    ManageZonesScreen(
                        container = container,
                        onCreateZone = { navController.navigate(AppRoutes.editor()) },
                        onEditZone = { zoneId -> navController.navigate(AppRoutes.editor(zoneId)) },
                    )
                } else {
                    AuthorityRequired(onGoToOfficer = { navController.navigate(AppRoutes.OFFICER) })
                }
            }

            composable(AppRoutes.ALERTS) {
                AlertHistoryScreen(container = container)
            }

            composable(AppRoutes.SETTINGS) {
                SettingsScreen(
                    container = container,
                    onOpenSimulation = { navController.navigate(AppRoutes.SIMULATION) },
                    onOpenAuthority = { navController.navigate(AppRoutes.AUTHORITY) },
                    onOpenContacts = { navController.navigate(AppRoutes.CONTACTS) },
                )
            }

            composable(AppRoutes.AUTHORITY) {
                AuthorityScreen(
                    container = container,
                    onOpenContacts = { navController.navigate(AppRoutes.CONTACTS) },
                )
            }

            composable(AppRoutes.CONTACTS) {
                EmergencyContactsScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(AppRoutes.SIMULATION) {
                SimulationScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(AppRoutes.WATCH) {
                WatchScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                    onOpenAuthority = { navController.navigate(AppRoutes.AUTHORITY) },
                )
            }
        }
    }
}

/** Standard bottom-bar behaviour: one entry per tab, state preserved across switches. */
private fun navigateToTab(navController: NavHostController, route: String) {
    navController.navigate(route) {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Shown when a zone-management screen is opened without an authenticated officer session.
 *
 * This is a usability gate, not the security boundary: the server rejects an unauthorised zone
 * request with 403 regardless of what any screen does. But a tourist who never sees a Create button
 * never wonders why it is there.
 */
@Composable
private fun AuthorityRequired(onGoToOfficer: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Authority only",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Restricted zones are created and managed by the authority. Sign in as an officer " +
                "to add or change one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGoToOfficer) { Text("Go to officer sign-in") }
    }
}

private fun titleFor(route: String?): String = when {
    route == null -> "Tourist Safety"
    route == AppRoutes.WELCOME -> "Tourist Safety"
    route == AppRoutes.TOURIST_AUTH -> "Tourist sign-in"
    route == AppRoutes.OFFICER -> "Authority officer"
    route.startsWith("editor") -> "Create restricted zone (authority)"
    route == AppRoutes.HOME -> "Safety Dashboard"
    route == AppRoutes.TRACKING -> "Live GPS Tracking"
    route == AppRoutes.ZONES -> "Restricted zones (authority)"
    route == AppRoutes.ALERTS -> "Alert History"
    route == AppRoutes.SETTINGS -> "Settings & Permissions"
    route == AppRoutes.SIMULATION -> "Simulation Mode"
    route == AppRoutes.WATCH -> "Simulated Smartwatch"
    route == AppRoutes.AUTHORITY -> "Authority Backend"
    route == AppRoutes.CONTACTS -> "Emergency Contacts"
    else -> "Tourist Safety"
}

package com.touristsafety.app.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.CenterAlignedTopAppBar
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
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.touristsafety.app.ui.screens.BackendSettingsScreen
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
import com.touristsafety.app.ui.screens.authority.AuthorityAccountScreen
import com.touristsafety.app.ui.screens.authority.AuthorityDashboardScreen
import com.touristsafety.app.ui.screens.authority.AuthorityLiveScreen
import com.touristsafety.app.ui.screens.authority.AuthorityRiskScreen
import com.touristsafety.app.ui.screens.authority.AuthoritySosScreen
import com.touristsafety.app.ui.screens.authority.AuthorityTouristsScreen
import com.touristsafety.app.ui.screens.authority.AuthorityZoneEditorScreen
import com.touristsafety.app.ui.screens.authority.AuthorityZonesScreen
import com.touristsafety.app.ui.viewmodel.AppViewModelFactory
import com.touristsafety.app.ui.viewmodel.AuthorityConsoleViewModel
import com.touristsafety.app.ui.viewmodel.AuthorityZonesViewModel
import com.touristsafety.app.ui.viewmodel.OfficerViewModel

/** Every route in the app, in one place so a typo cannot silently create a dead destination. */
object AppRoutes {

    /**
     * The role chooser: tourist or officer. Every launch begins here, whatever is already stored on
     * the phone, so the first question anyone is asked is the one that actually matters.
     */
    const val WELCOME = "welcome"

    /**
     * Where a cold launch begins, and where a sign-out returns to.
     *
     * One constant for both, so "the start of the app" and "after signing out" cannot drift apart:
     * both are the same screen asking the same question. It is deliberately not a session-dependent
     * value — see `AppNavigation`, which starts here unconditionally, and the Welcome screen, which
     * *offers* an existing tourist session rather than acting on it.
     */
    const val ROLE_SELECTION = WELCOME

    /** Tourist sign-in and self-registration. */
    const val TOURIST_AUTH = "tourist-auth"

    /** Officer sign-in. A successful sign-in opens [AUTHORITY_DASHBOARD]. */
    const val OFFICER = "officer"

    const val HOME = "home"
    const val TRACKING = "tracking"

    /** Shared restricted-zone management, as the authority backend holds it. Officer only. */
    const val ZONES = "zones"

    /** Local-only test zones on this handset. Officer only; never published to anyone. */
    const val LOCAL_ZONES = "local-zones"

    const val ALERTS = "alerts"
    const val SETTINGS = "settings"
    const val SIMULATION = "simulation"
    const val WATCH = "watch"
    const val AUTHORITY = "authority"
    const val CONTACTS = "contacts"

    /**
     * Which authority this phone dials: reachable before anyone has signed in.
     *
     * It sits with the onboarding routes on purpose. An address that no longer answers has to be
     * changeable without a sign-in, and a sign-in needs a reachable authority, so hiding this behind
     * the signed-in Settings screen is what once left the app unrecoverable on a new network.
     */
    const val BACKEND_SETTINGS = "backend-settings"

    // ---------------------------------------------------------------- officer console

    const val AUTHORITY_DASHBOARD = "authority-dashboard"
    const val AUTHORITY_LIVE = "authority-live"
    const val AUTHORITY_SOS = "authority-sos"
    const val AUTHORITY_RISK = "authority-risk"
    const val AUTHORITY_TOURISTS = "authority-tourists"
    const val AUTHORITY_ACCOUNT = "authority-account"

    /** The prefix shared by the local editor route (`editor?zoneId=…`). */
    const val EDITOR_ROUTE_PREFIX = "editor"

    const val EDITOR_ARG_ZONE_ID = "zoneId"
    private const val EDITOR_BASE = EDITOR_ROUTE_PREFIX

    /** `zoneId = -1` means "create"; anything else means "edit that zone". */
    fun editor(zoneId: Long? = null): String =
        "$EDITOR_BASE?$EDITOR_ARG_ZONE_ID=${zoneId ?: NEW_ZONE_ID}"

    const val EDITOR_PATTERN = "$EDITOR_BASE?$EDITOR_ARG_ZONE_ID={$EDITOR_ARG_ZONE_ID}"
    const val NEW_ZONE_ID = -1L

    // ---------------------------------------------------------------- authority zone editor

    const val AUTHORITY_ZONE_EDITOR_ROUTE_PREFIX = "authority-zone-editor"
    const val AUTHORITY_ZONE_ARG_ID = "authorityZoneId"

    /** `zoneId = -1` means "publish a new zone"; anything else means "edit that authority zone". */
    fun authorityZoneEditor(zoneId: Int? = null): String =
        "$AUTHORITY_ZONE_EDITOR_ROUTE_PREFIX?$AUTHORITY_ZONE_ARG_ID=${zoneId ?: NEW_AUTHORITY_ZONE_ID}"

    const val AUTHORITY_ZONE_EDITOR_PATTERN =
        "$AUTHORITY_ZONE_EDITOR_ROUTE_PREFIX?$AUTHORITY_ZONE_ARG_ID={$AUTHORITY_ZONE_ARG_ID}"
    const val NEW_AUTHORITY_ZONE_ID = -1
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
 * one. Those screens are gated on an authenticated officer session, and they are not part of the
 * tourist's navigation.
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

/**
 * The officer console's own navigation.
 *
 * Five bottom-bar destinations — a Material navigation bar becomes unreadable beyond that on a phone —
 * with the two remaining sections of the console reached from the dashboard's action row and the top
 * bar. Every one of the console's seven sections is therefore at most two taps from anywhere.
 *
 * All of these routes are officer-only. The gate is the server (`require_auth("authority")` on every
 * route they call); the navigation here only spares an unauthorised user from staring at controls
 * that could never work.
 */
object AuthorityNavigation {

    /** The bottom bar. */
    val tabRoutes: List<String> = listOf(
        AppRoutes.AUTHORITY_DASHBOARD,
        AppRoutes.AUTHORITY_SOS,
        AppRoutes.ZONES,
        AppRoutes.AUTHORITY_TOURISTS,
        AppRoutes.AUTHORITY_LIVE,
    )

    /** Reached from a dashboard card, the top bar, or a list row rather than the bottom bar. */
    val secondaryRoutes: List<String> = listOf(
        AppRoutes.AUTHORITY_RISK,
        AppRoutes.AUTHORITY_ACCOUNT,
        AppRoutes.AUTHORITY_ZONE_EDITOR_ROUTE_PREFIX,
        AppRoutes.LOCAL_ZONES,
        AppRoutes.EDITOR_ROUTE_PREFIX,
    )

    val consoleRoutes: List<String> = tabRoutes + secondaryRoutes

    /** True for a console route *and* the parameterised routes built from one (the two editors). */
    fun isConsoleRoute(route: String?): Boolean {
        if (route == null) return false
        return consoleRoutes.any { base -> route == base || route.startsWith("$base?") }
    }
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

private val authorityTabSpecs:
    Map<String, Pair<String, androidx.compose.ui.graphics.vector.ImageVector>> = linkedMapOf(
    AppRoutes.AUTHORITY_DASHBOARD to ("Overview" to Icons.Filled.Home),
    AppRoutes.AUTHORITY_SOS to ("SOS" to Icons.Filled.Notifications),
    AppRoutes.ZONES to ("Zones" to Icons.Filled.Place),
    AppRoutes.AUTHORITY_TOURISTS to ("Tourists" to Icons.Filled.People),
    AppRoutes.AUTHORITY_LIVE to ("Live" to Icons.Filled.MyLocation),
)

private val authorityDestinations: List<BottomDestination> =
    AuthorityNavigation.tabRoutes.map { route ->
        val spec = requireNotNull(authorityTabSpecs[route]) { "no authority-bar spec for '$route'" }
        BottomDestination(route, spec.first, spec.second, listOf(route))
    }

/**
 * Routes that are part of entering the app rather than using it: no tab bar, no shortcut icons.
 *
 * [AppRoutes.BACKEND_SETTINGS] belongs here because it must be reachable before any sign-in; it is
 * also linked from the signed-in screens, where the same chrome (a Back button, no tab bar) is the
 * right chrome for a configuration page.
 *
 * `internal` rather than `private` so the navigation contract can be asserted by a plain JVM test —
 * "the launch route has no tab bar", "the address screen is reachable before a sign-in" — without
 * rendering Compose.
 */
internal val onboardingRoutes = setOf(
    AppRoutes.ROLE_SELECTION,
    AppRoutes.TOURIST_AUTH,
    AppRoutes.OFFICER,
    AppRoutes.BACKEND_SETTINGS,
)

/**
 * True when the route on screen may not be shown without an officer session.
 *
 * Every officer route calls `require_auth("authority")` on the server, so an unauthenticated visitor
 * could only ever watch controls fail. This states that rule once, in one place, so it can be tested:
 * the console — the dashboard, incidents, forecasts, the tourist register, live monitoring and both
 * zone editors — needs an officer, while the role chooser, the two sign-in screens and Backend
 * settings never do.
 */
internal fun requiresOfficerSignIn(route: String?, officerSignedIn: Boolean): Boolean =
    AuthorityNavigation.isConsoleRoute(route) && !officerSignedIn

/**
 * The app's navigation graph.
 *
 * The start destination depends on whether this phone already holds a valid **tourist** session: a
 * returning tourist goes straight to their safety home, everyone else is asked who they are. That
 * gate is resolved before the graph is built, so there is no flash of the wrong screen.
 *
 * The officer console is a second, separate set of destinations. It has its own bottom bar, all of it
 * officer-only, and it is the whole authority dashboard — the app does not need a browser to run it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    container: AppContainer,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val officerSession by container.authoritySessionStore.officerSession
        .collectAsStateWithLifecycle(initialValue = OfficerSession())
    val officerSignedIn = officerSession.isSignedIn(System.currentTimeMillis())

    val onConsole = AuthorityNavigation.isConsoleRoute(currentRoute)

    // The console's view models live for the lifetime of the activity, so the dashboard, incidents,
    // forecasts and live view all describe one read instead of four independent ones.
    val consoleViewModel: AuthorityConsoleViewModel =
        viewModel(factory = AppViewModelFactory(container))
    val zonesViewModel: AuthorityZonesViewModel = viewModel(factory = AppViewModelFactory(container))
    val officerViewModel: OfficerViewModel = viewModel(factory = AppViewModelFactory(container))

    val touristSession by container.authoritySessionStore.session
        .collectAsStateWithLifecycle(initialValue = AuthoritySession())

    // Every launch starts at the role chooser — never at tracking, registration, officer sign-in or
    // the console. A stored tourist session is *offered* on the Welcome screen as a button rather than
    // acted on, so the person holding the phone picks the role every time; a previous session, and a
    // previous route, decide nothing. It also means the first frame never waits on the session store,
    // so a slow or damaged DataStore cannot hold the app on a spinner.
    val start = AppRoutes.ROLE_SELECTION

    val signedIn = touristSession.isSignedIn(System.currentTimeMillis())
    val onOnboarding = currentRoute in onboardingRoutes
    val nowMillis = System.currentTimeMillis()

    /** Wipe the back stack: used for both finishing a sign-in and finishing a sign-out. */
    fun resetTo(route: String) {
        navController.navigate(route) {
            popUpTo(0) { inclusive = true }
            launchSingleTop = true
        }
    }

    // A console route without an officer session can only ever fail, so it is replaced by the
    // sign-in gate rather than rendering an empty console. This is the path an expired or revoked
    // officer token takes: the first refused call drops the session and the officer lands here.
    LaunchedEffect(currentRoute, officerSignedIn) {
        if (requiresOfficerSignIn(currentRoute, officerSignedIn)) {
            resetTo(AppRoutes.OFFICER)
        }
    }

    // Read the feed that the console route on screen is about. The console's four feeds are read
    // together on the dashboard, which is where an officer starts and where the numbers have to agree.
    LaunchedEffect(currentRoute, officerSignedIn) {
        if (!officerSignedIn) return@LaunchedEffect
        when (currentRoute) {
            AppRoutes.ZONES -> zonesViewModel.refresh()
            AppRoutes.AUTHORITY_DASHBOARD,
            AppRoutes.AUTHORITY_SOS,
            AppRoutes.AUTHORITY_RISK,
            AppRoutes.AUTHORITY_LIVE,
            -> consoleViewModel.refresh()

            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            when {
                currentRoute == AppRoutes.WELCOME -> Unit

                onConsole -> {
                    CenterAlignedTopAppBar(
                        title = { Text(titleFor(currentRoute)) },
                        navigationIcon = {
                            IconButton(
                                onClick = { navController.navigate(AppRoutes.AUTHORITY_DASHBOARD) },
                            ) {
                                Icon(Icons.Filled.Home, contentDescription = "Console overview")
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { navController.navigate(AppRoutes.AUTHORITY_ACCOUNT) },
                            ) {
                                Icon(Icons.Filled.AccountCircle, contentDescription = "Authority account")
                            }
                        },
                    )
                }

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
            when {
                onConsole -> NavigationBar {
                    authorityDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { navigateToTab(navController, destination.route) },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(destination.label) },
                        )
                    }
                }

                !onOnboarding -> NavigationBar {
                    bottomDestinations.forEach { destination ->
                        val selected = destination.matchPrefixes.any { prefix ->
                            currentRoute?.startsWith(prefix) == true
                        }
                        NavigationBarItem(
                            selected = selected,
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
                    onOpenBackendSettings = { navController.navigate(AppRoutes.BACKEND_SETTINGS) },
                )
            }

            composable(AppRoutes.BACKEND_SETTINGS) {
                BackendSettingsScreen(container = container)
            }

            composable(AppRoutes.TOURIST_AUTH) {
                TouristAuthScreen(
                    container = container,
                    onSignedIn = { resetTo(AppRoutes.HOME) },
                    onOpenBackendSettings = { navController.navigate(AppRoutes.BACKEND_SETTINGS) },
                )
            }

            composable(AppRoutes.OFFICER) {
                OfficerScreen(
                    container = container,
                    viewModel = officerViewModel,
                    onOpenBackendSettings = { navController.navigate(AppRoutes.BACKEND_SETTINGS) },
                    onEnterConsole = { resetTo(AppRoutes.AUTHORITY_DASHBOARD) },
                )
            }

            composable(AppRoutes.HOME) {
                HomeScreen(
                    container = container,
                    onOpenTracking = { navigateToTab(navController, AppRoutes.TRACKING) },
                    onOpenAlerts = { navigateToTab(navController, AppRoutes.ALERTS) },
                    onOpenContacts = { navController.navigate(AppRoutes.CONTACTS) },
                    onOpenSettings = { navController.navigate(AppRoutes.SETTINGS) },
                    onSignedOut = { resetTo(AppRoutes.ROLE_SELECTION) },
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
                val rawId = entry.arguments?.getLong(AppRoutes.EDITOR_ARG_ZONE_ID)
                    ?: AppRoutes.NEW_ZONE_ID
                GeoFenceEditorScreen(
                    container = container,
                    zoneId = rawId.takeIf { it != AppRoutes.NEW_ZONE_ID },
                    onFinished = { navController.popBackStack() },
                )
            }

            composable(
                route = AppRoutes.AUTHORITY_ZONE_EDITOR_PATTERN,
                arguments = listOf(
                    navArgument(AppRoutes.AUTHORITY_ZONE_ARG_ID) {
                        type = NavType.IntType
                        defaultValue = AppRoutes.NEW_AUTHORITY_ZONE_ID
                    },
                ),
            ) { entry ->
                val rawId = entry.arguments?.getInt(AppRoutes.AUTHORITY_ZONE_ARG_ID)
                    ?: AppRoutes.NEW_AUTHORITY_ZONE_ID
                AuthorityZoneEditorScreen(
                    container = container,
                    zoneId = rawId.takeIf { it != AppRoutes.NEW_AUTHORITY_ZONE_ID },
                    onFinished = { navController.popBackStack() },
                )
            }

            composable(AppRoutes.ZONES) {
                AuthorityZonesScreen(
                    viewModel = zonesViewModel,
                    onCreateZone = { navController.navigate(AppRoutes.authorityZoneEditor()) },
                    onEditZone = { zoneId ->
                        navController.navigate(AppRoutes.authorityZoneEditor(zoneId))
                    },
                    onOpenLocalZones = { navController.navigate(AppRoutes.LOCAL_ZONES) },
                )
            }

            composable(AppRoutes.LOCAL_ZONES) {
                ManageZonesScreen(
                    container = container,
                    onCreateZone = { navController.navigate(AppRoutes.editor()) },
                    onEditZone = { zoneId -> navController.navigate(AppRoutes.editor(zoneId)) },
                )
            }

            composable(AppRoutes.AUTHORITY_DASHBOARD) {
                AuthorityDashboardScreen(
                    viewModel = consoleViewModel,
                    nowMillis = nowMillis,
                    onOpenLive = { navigateToTab(navController, AppRoutes.AUTHORITY_LIVE) },
                    onOpenSos = { navigateToTab(navController, AppRoutes.AUTHORITY_SOS) },
                    onOpenRisk = { navController.navigate(AppRoutes.AUTHORITY_RISK) },
                    onOpenZones = { navigateToTab(navController, AppRoutes.ZONES) },
                    onOpenTourists = { navigateToTab(navController, AppRoutes.AUTHORITY_TOURISTS) },
                    onOpenAccount = { navController.navigate(AppRoutes.AUTHORITY_ACCOUNT) },
                )
            }

            composable(AppRoutes.AUTHORITY_LIVE) {
                AuthorityLiveScreen(viewModel = consoleViewModel, nowMillis = nowMillis)
            }

            composable(AppRoutes.AUTHORITY_SOS) {
                AuthoritySosScreen(
                    viewModel = consoleViewModel,
                    nowMillis = nowMillis,
                    onOpenRisk = { navController.navigate(AppRoutes.AUTHORITY_RISK) },
                )
            }

            composable(AppRoutes.AUTHORITY_RISK) {
                AuthorityRiskScreen(viewModel = consoleViewModel, nowMillis = nowMillis)
            }

            composable(AppRoutes.AUTHORITY_TOURISTS) {
                AuthorityTouristsScreen(viewModel = officerViewModel)
            }

            composable(AppRoutes.AUTHORITY_ACCOUNT) {
                AuthorityAccountScreen(
                    officerViewModel = officerViewModel,
                    consoleViewModel = consoleViewModel,
                    onOpenBackendSettings = { navController.navigate(AppRoutes.BACKEND_SETTINGS) },
                    onSignedOut = { resetTo(AppRoutes.ROLE_SELECTION) },
                )
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

private fun titleFor(route: String?): String = when {
    route == null -> "Tourist Safety"
    route == AppRoutes.WELCOME -> "Tourist Safety"
    route == AppRoutes.TOURIST_AUTH -> "Tourist sign-in"
    route == AppRoutes.OFFICER -> "Authority officer sign-in"
    route.startsWith(AppRoutes.EDITOR_ROUTE_PREFIX) -> "Local test zone (this phone only)"
    route.startsWith(AppRoutes.AUTHORITY_ZONE_EDITOR_ROUTE_PREFIX) -> "Restricted zone (authority)"
    route == AppRoutes.HOME -> "Safety Dashboard"
    route == AppRoutes.TRACKING -> "Live GPS Tracking"
    route == AppRoutes.ZONES -> "Restricted zones"
    route == AppRoutes.LOCAL_ZONES -> "Local test zones"
    route == AppRoutes.ALERTS -> "Alert History"
    route == AppRoutes.SETTINGS -> "Settings & Permissions"
    route == AppRoutes.SIMULATION -> "Simulation Mode"
    route == AppRoutes.WATCH -> "Simulated Smartwatch"
    route == AppRoutes.AUTHORITY -> "Authority Backend"
    route == AppRoutes.BACKEND_SETTINGS -> "Backend settings"
    route == AppRoutes.CONTACTS -> "Emergency Contacts"
    route == AppRoutes.AUTHORITY_DASHBOARD -> "Authority dashboard"
    route == AppRoutes.AUTHORITY_LIVE -> "Live monitoring"
    route == AppRoutes.AUTHORITY_SOS -> "SOS incidents"
    route == AppRoutes.AUTHORITY_RISK -> "Future-risk predictions"
    route == AppRoutes.AUTHORITY_TOURISTS -> "Tourist management"
    route == AppRoutes.AUTHORITY_ACCOUNT -> "Authority account"
    else -> "Tourist Safety"
}

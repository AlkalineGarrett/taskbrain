package org.alkaline.taskbrain

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.alkaline.taskbrain.service.NotificationSyncer
import org.alkaline.taskbrain.dsl.directives.ScheduleExecutionRepository
import org.alkaline.taskbrain.ui.Dimens
import org.alkaline.taskbrain.ui.alarms.AlarmsScreen
import org.alkaline.taskbrain.ui.auth.GoogleSignInScreen
import org.alkaline.taskbrain.ui.components.MissedSchedulesBanner
import org.alkaline.taskbrain.ui.currentnote.CurrentNoteScreen
import org.alkaline.taskbrain.ui.currentnote.CurrentNoteViewModel
import org.alkaline.taskbrain.ui.currentnote.RecentTabsViewModel
import org.alkaline.taskbrain.ui.notelist.NoteListScreen
import org.alkaline.taskbrain.ui.schedules.SchedulesScreen

sealed class Screen(val route: String, val titleResourceId: Int, val icon: ImageVector) {
    object CurrentNote : Screen("current_note", R.string.title_current_note, Icons.Filled.Description)
    object NoteList : Screen("note_list", R.string.title_note_list, Icons.Filled.Dashboard)
    object Notifications : Screen("notifications", R.string.title_notifications, Icons.Filled.Notifications)
    object Schedules : Screen("schedules", R.string.title_schedules, Icons.Filled.Schedule)
    object Login : Screen("login", R.string.google_title_text, Icons.Filled.Home) // Icon not used for login
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onSignInClick: () -> Unit,
    isUserSignedIn: Boolean,
    onSignOutClick: () -> Unit,
    isFingerDown: StateFlow<Boolean>,
    openAlarmId: StateFlow<String?> = MutableStateFlow(null),
    onOpenAlarmConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    val pendingAlarmId by openAlarmId.collectAsState()

    // Create ViewModels at MainScreen level so they're shared across all CurrentNoteScreen instances
    // (both the "current_note" and "current_note?noteId={noteId}" routes)
    val recentTabsViewModel: RecentTabsViewModel = viewModel()
    val currentNoteViewModel: CurrentNoteViewModel = viewModel()

    // Sync notification icons/titles with correct alarm stage types on startup
    val context = LocalContext.current
    LaunchedEffect(isUserSignedIn) {
        if (isUserSignedIn) {
            withContext(Dispatchers.IO) {
                NotificationSyncer(context).sync()
            }
        }
    }

    // Alarm ID to auto-open on the alarms screen (from notification tap)
    var alarmIdToOpen by remember { mutableStateOf<String?>(null) }

    // When a notification tap sets pendingAlarmId, navigate to the alarms screen
    LaunchedEffect(pendingAlarmId) {
        val alarmId = pendingAlarmId ?: return@LaunchedEffect
        onOpenAlarmConsumed()
        alarmIdToOpen = alarmId
        navController.navigate(Screen.Notifications.route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
        }
    }

    // Determine the start destination based on sign-in status
    val startDestination = if (isUserSignedIn) Screen.CurrentNote.route else Screen.Login.route

    // Track previous sign-in state so we only navigate on transitions. The
    // first composition is handled by `startDestination`; navigating on
    // first composition races with NavHost's graph setup (NavHost calls
    // setGraph in its own LaunchedEffect, which fires after this one).
    var previousSignedIn by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(isUserSignedIn) {
        val previous = previousSignedIn
        previousSignedIn = isUserSignedIn
        if (previous == null || previous == isUserSignedIn) return@LaunchedEffect
        if (isUserSignedIn) {
            // Skip if there's a pending alarm — the alarm LaunchedEffect will handle navigation
            if (pendingAlarmId != null) return@LaunchedEffect
            navController.navigate(Screen.CurrentNote.route) {
                popUpTo(Screen.Login.route) { inclusive = true }
            }
        } else {
            navController.navigate(Screen.Login.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    val items = listOf(
        Screen.CurrentNote,
        Screen.NoteList,
        Screen.Notifications,
    )

    Scaffold(
        topBar = {
            // Only show top bar if not in login screen
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination
            if (currentDestination?.route != Screen.Login.route) {
                var showMenu by remember { mutableStateOf(false) }

                TopAppBar(
                    modifier = Modifier.height(Dimens.TopAppBarHeight),
                    title = {
                        Text(
                            text = stringResource(R.string.app_name),
                            fontSize = Dimens.TopAppBarTitleTextSize
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = colorResource(R.color.titlebar_background),
                        titleContentColor = colorResource(R.color.titlebar_text),
                        actionIconContentColor = colorResource(R.color.titlebar_text),
                        navigationIconContentColor = colorResource(R.color.titlebar_text)
                    ),
                    actions = {
                        Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                            IconButton(onClick = { showMenu = !showMenu }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.action_settings)
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_sign_out)) },
                                    onClick = {
                                        showMenu = false
                                        onSignOutClick()
                                    }
                                )
                            }
                        }
                    }
                )
            }
        },
        bottomBar = {
             val navBackStackEntry by navController.currentBackStackEntryAsState()
             val currentDestination = navBackStackEntry?.destination
             
             // Hide bottom bar on login screen
             if (currentDestination?.route != Screen.Login.route) {
                 NavigationBar {
                     items.forEach { screen ->
                         NavigationBarItem(
                             icon = { Icon(screen.icon, contentDescription = null) },
                             label = { Text(
                                 text = stringResource(screen.titleResourceId),
                                 fontSize = Dimens.NavigationBarLabelTextSize
                             ) },
                             selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                             onClick = {
                                 navController.navigate(screen.route) {
                                     popUpTo(navController.graph.findStartDestination().id) {
                                         saveState = true
                                     }
                                     launchSingleTop = true
                                     restoreState = true
                                 }
                             }
                         )
                     }
                 }
             }
        }
    ) { innerPadding ->
        // Observe missed schedules count for the banner
        val missedCount by remember(isUserSignedIn) { ScheduleExecutionRepository().observeMissedCount() }.collectAsState(initial = 0)
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination
        val showBanner = isUserSignedIn &&
            currentDestination?.route != Screen.Login.route &&
            currentDestination?.route != Screen.Schedules.route

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
                .fillMaxSize()
        ) {
            // Show missed schedules banner when appropriate
            if (showBanner) {
                MissedSchedulesBanner(
                    missedCount = missedCount,
                    onClick = {
                        navController.navigate(Screen.Schedules.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }

            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.fillMaxSize()
            ) {
            composable(Screen.Login.route) {
                GoogleSignInScreen(
                    isLoading = false,
                    onSignInClick = onSignInClick
                )
            }
            composable(
                route = "${Screen.CurrentNote.route}?noteId={noteId}",
                arguments = listOf(navArgument("noteId") {
                    type = NavType.StringType
                    nullable = true
                })
            ) { backStackEntry ->
                val noteId = backStackEntry.arguments?.getString("noteId")
                CurrentNoteScreen(
                    noteId = noteId,
                    isFingerDownFlow = isFingerDown,
                    onNavigateBack = {
                        navController.navigate(Screen.NoteList.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToNote = { targetNoteId ->
                        navController.navigate("${Screen.CurrentNote.route}?noteId=$targetNoteId") {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    currentNoteViewModel = currentNoteViewModel,
                    recentTabsViewModel = recentTabsViewModel
                )
            }
            // Keep the basic route for direct navigation (tab clicks)
            composable(Screen.CurrentNote.route) {
                CurrentNoteScreen(
                    isFingerDownFlow = isFingerDown,
                    onNavigateBack = {
                        navController.navigate(Screen.NoteList.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToNote = { targetNoteId ->
                        navController.navigate("${Screen.CurrentNote.route}?noteId=$targetNoteId") {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    currentNoteViewModel = currentNoteViewModel,
                    recentTabsViewModel = recentTabsViewModel
                )
            }
            composable(Screen.NoteList.route) {
                NoteListScreen(
                    onNoteClick = { noteId ->
                        navController.navigate("${Screen.CurrentNote.route}?noteId=$noteId") {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onSaveCompleted = currentNoteViewModel.saveCompleted
                )
            }
            composable(Screen.Notifications.route) {
                AlarmsScreen(
                    openAlarmId = alarmIdToOpen,
                    onOpenAlarmConsumed = { alarmIdToOpen = null }
                )
            }
            composable(Screen.Schedules.route) {
                SchedulesScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
        }
    }
}
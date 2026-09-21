package com.ogenrwotdaniel.wakeproof.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.ogenrwotdaniel.wakeproof.ui.adaptive.PaneMode
import com.ogenrwotdaniel.wakeproof.ui.adaptive.READABLE_MAX_WIDTH_DP
import com.ogenrwotdaniel.wakeproof.ui.adaptive.paneModeForWidthDp
import kotlin.reflect.KClass

/**
 * One destination the permanent navigation can reach.
 *
 * Deliberately a small closed list rather than everything in the graph:
 * onboarding is a first-run flow that clears the back stack, and the alarm
 * verification screens live in another activity entirely. Neither belongs in a
 * list you can wander into sideways.
 */
private data class ShellDestination(
    val label: String,
    val route: Any,
    val type: KClass<*>
)

/** Fixed width for the navigation column — wide enough for the longest label. */
private val NAV_WIDTH = 220.dp

private val SHELL_DESTINATIONS = listOf(
    ShellDestination("Today", HomeRoute, HomeRoute::class),
    ShellDestination("Focus timer", PomodoroRoute, PomodoroRoute::class),
    ShellDestination("Flashcards", FlashcardsRoute, FlashcardsRoute::class),
    ShellDestination("Agenda", AgendaRoute, AgendaRoute::class),
    ShellDestination("Screen time", UsageRoute, UsageRoute::class),
    ShellDestination("Settings", SettingsRoute, SettingsRoute::class)
)

/**
 * Wraps the navigation graph in whatever shape the window can carry.
 *
 * On a phone this is a pass-through: [content] is drawn exactly as it was
 * before this file existed, so nothing about the compact layout can regress
 * through here. On a tablet a permanent navigation column appears beside the
 * content and the content itself is capped to a readable width.
 *
 * Width comes from [BoxWithConstraints] rather than the window size class API
 * or `LocalConfiguration`, because it reports the space *this composable*
 * actually got. That is the honest number in a multi-window split, where a
 * tablet's screen is 1200 dp but the app's half of it is 600.
 */
@Composable
fun AdaptiveShell(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    immersiveFocus: Boolean = false,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val mode = paneModeForWidthDp(maxWidth.value.toInt())
        val entry by navController.currentBackStackEntryAsState()
        val destination = entry?.destination

        // Onboarding owns the whole window: it is the one flow that must not
        // offer a way out to a half-configured app.
        val onboarding = destination?.hasRoute(OnboardingRoute::class) == true

        // A focus block owns it for the opposite reason — the point of the
        // full-screen timer is that there is nothing else to go and look at,
        // and a permanent list of six other screens is exactly something else
        // to go and look at.
        if (mode == PaneMode.COMPACT || onboarding || immersiveFocus) {
            content()
        } else {
            Row(Modifier.fillMaxSize()) {
                ShellNavigation(
                    current = destination,
                    onNavigate = { route -> navController.navigateTopLevel(route) }
                )
                ReadableColumn(Modifier.weight(1f)) { content() }
            }
        }
    }
}

/**
 * The permanent navigation column.
 *
 * A drawer rather than a [androidx.compose.material3.NavigationRail] because
 * the app ships no icon set and never has — every affordance in it is a word.
 * `NavigationRailItem` requires an icon; `NavigationDrawerItem` treats it as
 * optional, so the text-only list stays honest instead of acquiring a
 * dependency and six invented glyphs.
 */
@Composable
private fun ShellNavigation(
    current: NavDestination?,
    onNavigate: (Any) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        // Width first, then height. `fillMaxSize()` resolves the width against
        // the parent's constraints before any later `widthIn` can clamp it, so
        // the cap was silently ignored and this column ate the whole window.
        modifier = Modifier.width(NAV_WIDTH).fillMaxHeight()
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "WakeProof",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SHELL_DESTINATIONS.forEach { item ->
                NavigationDrawerItem(
                    label = { Text(item.label) },
                    selected = current?.hasRoute(item.type) == true,
                    onClick = { onNavigate(item.route) }
                )
            }
        }
    }
}

/**
 * Caps the content column and centres it in whatever is left.
 *
 * See [READABLE_MAX_WIDTH_DP]: past that width the extra space becomes margin
 * rather than longer lines of text.
 */
@Composable
private fun ReadableColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier.fillMaxHeight(),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            // widthIn BEFORE fillMaxWidth, for the same reason as the nav
            // column above: fillMaxWidth pins minWidth to the parent's width,
            // and a later widthIn cannot shrink below its own minimum. Reversed,
            // widthIn caps maxWidth first and fillMaxWidth then fills that cap.
            modifier = Modifier
                .widthIn(max = READABLE_MAX_WIDTH_DP.dp)
                .fillMaxWidth()
        ) {
            content()
        }
    }
}

/**
 * Navigate the way a permanent navigation list has to.
 *
 * `popUpTo(HomeRoute)` keeps the back stack from growing a trail of every
 * section visited — with a rail, moving between sections is not a drill-down
 * and back should return to Today, not retrace the tour. `launchSingleTop`
 * makes tapping the current entry a no-op rather than a reload.
 */
private fun NavHostController.navigateTopLevel(route: Any) {
    navigate(route) {
        popUpTo(HomeRoute) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

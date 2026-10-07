package com.ogenrwotdaniel.wakeproof.ui.forest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.data.ForestDay
import com.ogenrwotdaniel.wakeproof.pomodoro.FocusTree
import com.ogenrwotdaniel.wakeproof.ui.components.chartDayLabel
import com.ogenrwotdaniel.wakeproof.ui.formatDurationMinutes
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Forest-style garden: every focus block of the last four weeks is a tree, and
 * every block abandoned for a distracting app is a withered one. The streak
 * says whether you studied; this shows how — a day of long unbroken blocks
 * and a day of short scattered ones look different here and identical there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ForestScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ForestViewModel = hiltViewModel()
) {
    val days by viewModel.days.collectAsStateWithLifecycle()
    val week = days.take(7)
    val grownThisWeek = week.sumOf { it.trees.size - it.withered }
    val witheredThisWeek = week.sumOf { it.withered }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "Your forest", style = MaterialTheme.typography.headlineMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = "🌳 $grownThisWeek trees grown this week",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${FocusTree.WITHERED} $witheredThisWeek withered · " +
                            "${formatDurationMinutes(week.sumOf { it.minutes })} focused",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "One tree per focus block — longer blocks grow bigger trees " +
                            "(🌿 under 20 min, 🌳 under 45, 🌲 45+). Leave for a " +
                            "distracting app mid-block and it withers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            if (days.all { it.trees.isEmpty() }) {
                Text(
                    text = "Nothing planted yet. Start a focus session and your first " +
                        "seedling appears here when the block ends.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            days.filter { it.trees.isNotEmpty() }.forEach { day -> ForestRow(day) }

            TextButton(onClick = onBack) { Text("Back") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ForestRow(day: ForestDay, modifier: Modifier = Modifier) {
    val today = LocalDate.now().toEpochDay()
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (day.epochDay == today || today - day.epochDay < 7) {
                    chartDayLabel(day.epochDay, today)
                } else {
                    LocalDate.ofEpochDay(day.epochDay).format(DateTimeFormatter.ofPattern("d MMM"))
                },
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = formatDurationMinutes(day.minutes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            day.trees.forEach { tree ->
                Text(text = tree, style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}

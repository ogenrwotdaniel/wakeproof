package com.ogenrwotdaniel.wakeproof.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** One column of a [LabeledBarChart]. */
data class ChartBar(
    /** Under the bar, e.g. "Mon". */
    val label: String,
    val value: Float,
    /** Printed above the bar; blank prints nothing. */
    val valueLabel: String,
    /** Today: bold label, so the column you are still filling is obvious. */
    val emphasized: Boolean = false,
    /** Drawn in full colour; below-goal bars are drawn faded. */
    val reached: Boolean = false,
    /** A small red mark under the bar — e.g. a day with an emergency skip. */
    val flagged: Boolean = false
)

/**
 * A bar chart that can be read without a legend: each bar carries its own value,
 * days are named ("Mon", not "M" — two Ts and two Ss in a week of initials), the
 * goal is a dashed line across the plot, and bars that reached it are drawn in
 * full colour while the rest are faded.
 *
 * Replaces two unlabelled Canvas charts whose bars were scaled to an invisible
 * maximum, so a tall bar could mean 20 minutes or 6 hours.
 */
@Composable
fun LabeledBarChart(
    bars: List<ChartBar>,
    modifier: Modifier = Modifier,
    goal: Float? = null,
    goalLabel: String? = null,
    height: Dp = 148.dp
) {
    val primary = MaterialTheme.colorScheme.primary
    val faded = primary.copy(alpha = 0.4f)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val goalColor = MaterialTheme.colorScheme.tertiary
    val error = MaterialTheme.colorScheme.error

    val maxValue = maxOf(
        bars.maxOfOrNull { it.value } ?: 0f,
        // Head-room over the goal so the line never sits on the top edge.
        (goal ?: 0f) * 1.15f,
        1f
    )
    val valueLabelSpace = 18.dp
    val barArea = height - valueLabelSpace

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val areaPx = barArea.toPx()
                // Baseline.
                drawLine(
                    color = track,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 2.dp.toPx()
                )
                if (goal != null && goal > 0f) {
                    val y = size.height - areaPx * (goal / maxValue)
                    drawLine(
                        color = goalColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.Bottom
            ) {
                bars.forEach { bar ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        if (bar.valueLabel.isNotBlank()) {
                            Text(
                                text = bar.valueLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (bar.reached) primary else labelColor,
                                fontWeight = if (bar.emphasized) FontWeight.Bold else null,
                                maxLines = 1
                            )
                        }
                        val fraction = (bar.value / maxValue).coerceIn(0f, 1f)
                        // A zero day still gets a stub, so "nothing" reads as
                        // a measured nothing rather than missing data.
                        val barHeight = if (fraction > 0f) barArea * fraction else 3.dp
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.55f)
                                .height(barHeight)
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                .background(
                                    when {
                                        fraction == 0f -> track
                                        bar.reached -> primary
                                        else -> faded
                                    }
                                )
                        )
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            bars.forEach { bar ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = bar.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (bar.emphasized) MaterialTheme.colorScheme.onSurface else labelColor,
                        fontWeight = if (bar.emphasized) FontWeight.Bold else null,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                    if (bar.flagged) {
                        Box(
                            Modifier
                                .padding(top = 2.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(error)
                        )
                    }
                }
            }
        }
        if (goal != null && goalLabel != null) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendSwatch(goalColor, dashed = true)
                Text(
                    text = goalLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    modifier = Modifier.padding(start = 6.dp, end = 16.dp)
                )
                LegendSwatch(primary)
                Text(
                    text = "reached",
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    modifier = Modifier.padding(start = 6.dp, end = 16.dp)
                )
                LegendSwatch(faded)
                Text(
                    text = "below",
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun LegendSwatch(color: Color, dashed: Boolean = false) {
    if (dashed) {
        Canvas(Modifier.width(16.dp).height(8.dp)) {
            drawLine(
                color = color,
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
            )
        }
    } else {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
    }
}

/** "Mon", "Tue"… in the phone's language; today reads "Today". */
fun chartDayLabel(epochDay: Long, today: Long = LocalDate.now().toEpochDay()): String =
    if (epochDay == today) "Today"
    else DayOfWeek.from(LocalDate.ofEpochDay(epochDay))
        .getDisplayName(TextStyle.SHORT, Locale.getDefault())

package com.tripsplit.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One snackbar host for the whole app, so an Undo offered on the screen you
 * left still shows on the screen you land on.
 */
val LocalSnackbar = staticCompositionLocalOf { SnackbarHostState() }

/** "Chris Pryslak" -> "CP", "Dana" -> "D". */
fun initials(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(1)
        else -> parts.first().take(1) + parts.last().take(1)
    }.uppercase()
}

/** A person's coloured disc with their initials. The same colour everywhere they appear. */
@Composable
fun Avatar(trip: Trip, personId: String, size: Dp = 34.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(personColor(trip, personId)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initials(trip.nameOf(personId)),
            color = OnAvatar,
            fontSize = (size.value * 0.40f).sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = (size.value * 0.40f).sp,
            maxLines = 1
        )
    }
}

/** A run of small avatars, wrapping when there are many. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AvatarRow(trip: Trip, personIds: List<String>, size: Dp = 20.dp, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        personIds.forEach { Avatar(trip, it, size) }
    }
}

/** A tiny dot in a person's colour, for chip leading icons. */
@Composable
fun ColorDot(color: Color, size: Dp = 12.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

/** Small caps heading used above groups of fields. */
@Composable
fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MoneySlate
    )
    Spacer(Modifier.height(8.dp))
}

/** A label on the left, an amount on the right. The workhorse row of every list. */
@Composable
fun LabelAmountRow(
    label: String,
    amountText: String,
    amountColor: Color,
    labelColor: Color = MoneySlate,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = labelColor,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(12.dp))
        Text(amountText, style = MaterialTheme.typography.bodyMedium, color = amountColor)
    }
}

/**
 * A single stacked bar showing how a total divides — spending by category, say.
 * Segments are in the order given; anything under a percent still gets a sliver.
 */
@Composable
fun ProportionBar(
    parts: List<Pair<String, Long>>,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp
) {
    val total = parts.sumOf { it.second }.coerceAtLeast(1L)
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        parts.forEachIndexed { i, (_, amount) ->
            val weight = (amount.toFloat() / total.toFloat()).coerceAtLeast(0.01f)
            Box(
                Modifier
                    .weight(weight)
                    .height(height)
                    .background(colors[i % colors.size])
            )
        }
    }
}

/** Distinct colours for category segments, in the order categories are listed. */
val CategoryColors: List<Color> = listOf(
    Color(0xFFCFA829), Color(0xFF64B5F6), Color(0xFFBA68C8), Color(0xFF81C784),
    Color(0xFFFFB74D), Color(0xFF4DB6AC), Color(0xFFE57373), Color(0xFF93A5B5),
    Color(0xFF7986CB)
)

package com.tripsplit.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/* Night: cream on ink, gold for money coming in, a warm red for money owed. */
private val Ink = Color(0xFF12181F)
private val Ink2 = Color(0xFF1A2430)
private val Cream = Color(0xFFF2EFE6)
private val Gold = Color(0xFFCFA829)
private val Slate = Color(0xFF93A5B5)
private val Owed = Color(0xFFE0705F)

/* Day: the same palette turned over. The gold and red are deepened so they
   still clear 4.5:1 against cream at body size. */
private val Cream2 = Color(0xFFE7E2D4)
private val GoldDeep = Color(0xFF85690C)
private val SlateDeep = Color(0xFF5B6B7A)
private val OwedDeep = Color(0xFFB8442F)

private val darkScheme = darkColorScheme(
    primary = Gold,
    onPrimary = Ink,
    secondary = Cream,
    onSecondary = Ink,
    background = Ink,
    onBackground = Cream,
    surface = Ink,
    onSurface = Cream,
    surfaceVariant = Ink2,
    onSurfaceVariant = Slate,
    surfaceContainer = Ink2,
    surfaceContainerHigh = Color(0xFF212D3B),
    outline = Color(0xFF33465A),
    outlineVariant = Color(0xFF26343F),
    error = Owed,
    onError = Ink
)

private val lightScheme = lightColorScheme(
    primary = GoldDeep,
    onPrimary = Cream,
    secondary = Ink,
    onSecondary = Cream,
    background = Cream,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = Cream2,
    onSurfaceVariant = SlateDeep,
    surfaceContainer = Cream2,
    surfaceContainerHigh = Color(0xFFDDD7C7),
    outline = Color(0xFFC9C2B2),
    outlineVariant = Color(0xFFDDD7C7),
    error = OwedDeep,
    onError = Cream
)

/** Sized up a step throughout — this is a tablet held at arm's length. */
private val typography = Typography(
    displaySmall = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.SemiBold, lineHeight = 46.sp),
    headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 30.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Medium, lineHeight = 25.sp),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.2.sp)
)

/*
 * One colour language for money, everywhere:
 *   gold  = money coming to you (you're owed, you covered something, a repayment you sent)
 *   red   = money going out (you owe, your share, a repayment you received)
 *   slate = neutral / explanatory
 * These read the active scheme so they flip correctly between day and night.
 */
val MoneyGold: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary

val MoneyOwed: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.error

val MoneySlate: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant

/** Text colour that reads on any avatar colour below. */
val OnAvatar: Color = Ink

/**
 * Twelve hues that stay distinct on both ink and cream, ordered so neighbours
 * in the people list never look alike. A person keeps their colour for the life
 * of the trip because it's assigned by position in the people list, which only
 * ever grows.
 */
private val PersonPalette = listOf(
    Color(0xFFE57373), // red
    Color(0xFF64B5F6), // blue
    Color(0xFF81C784), // green
    Color(0xFFFFD54F), // amber
    Color(0xFFBA68C8), // purple
    Color(0xFF4DB6AC), // teal
    Color(0xFFFFB74D), // orange
    Color(0xFF7986CB), // indigo
    Color(0xFFF06292), // pink
    Color(0xFFDCE775), // lime
    Color(0xFF4DD0E1), // cyan
    Color(0xFFA1887F)  // brown
)

fun personColor(trip: Trip, personId: String): Color {
    val i = trip.people.indexOfFirst { it.id == personId }
    return if (i < 0) Color(0xFF9E9E9E) else personColorAt(i)
}

/** The colour the person at this position in the list will get. */
fun personColorAt(index: Int): Color = PersonPalette[index.coerceAtLeast(0) % PersonPalette.size]

@Composable
fun TripSplitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkScheme else lightScheme,
        typography = typography,
        content = content
    )
}

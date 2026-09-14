package com.qtotp.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The palette is taken straight off the app icon (art/qt-otp.svg): the badge
 * green, the shield's two lighter greens, and the kitten's nose pink as the
 * accent. Dynamic colour is deliberately not used — a vault is one of the few
 * apps you want to recognise instantly, and wallpaper-derived colours would
 * mean the icon and the app it opens rarely agree.
 */
private val BadgeGreen = Color(0xFF294A2F)
private val ShieldGreen = Color(0xFFABD4B2)
private val LockGreen = Color(0xFFC0DDBA)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E5B39),
    onPrimary = Color.White,
    primaryContainer = LockGreen,
    onPrimaryContainer = Color(0xFF0E2614),
    inversePrimary = ShieldGreen,

    secondary = Color(0xFF52634F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E8CF),
    onSecondaryContainer = Color(0xFF101F0F),

    tertiary = Color(0xFF8D4F5E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD9E0),
    onTertiaryContainer = Color(0xFF3A101C),

    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFFAFCF6),
    onBackground = Color(0xFF191D17),
    surface = Color(0xFFFAFCF6),
    onSurface = Color(0xFF191D17),
    surfaceVariant = Color(0xFFDEE5D9),
    onSurfaceVariant = Color(0xFF424940),
    surfaceTint = Color(0xFF2E5B39),
    inverseSurface = Color(0xFF2E322C),
    inverseOnSurface = Color(0xFFF0F3EA),

    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F7EF),
    surfaceContainer = Color(0xFFEEF2E8),
    surfaceContainerHigh = Color(0xFFE8ECE2),
    surfaceContainerHighest = Color(0xFFE2E6DC),

    outline = Color(0xFF72796F),
    outlineVariant = Color(0xFFC2C9BD),
    scrim = Color.Black,
)

private val DarkColors = darkColorScheme(
    primary = ShieldGreen,
    onPrimary = Color(0xFF133721),
    primaryContainer = BadgeGreen,
    onPrimaryContainer = Color(0xFFC6F0CC),
    inversePrimary = Color(0xFF2E5B39),

    secondary = Color(0xFFB9CCB3),
    onSecondary = Color(0xFF253423),
    secondaryContainer = Color(0xFF3B4B38),
    onSecondaryContainer = Color(0xFFD5E8CF),

    tertiary = Color(0xFFFFB1C1),
    onTertiary = Color(0xFF542230),
    tertiaryContainer = Color(0xFF703846),
    onTertiaryContainer = Color(0xFFFFD9E0),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF11140F),
    onBackground = Color(0xFFE1E4DB),
    surface = Color(0xFF11140F),
    onSurface = Color(0xFFE1E4DB),
    surfaceVariant = Color(0xFF424940),
    onSurfaceVariant = Color(0xFFC2C9BD),
    surfaceTint = ShieldGreen,
    inverseSurface = Color(0xFFE1E4DB),
    inverseOnSurface = Color(0xFF2E322C),

    surfaceContainerLowest = Color(0xFF0C0F0A),
    surfaceContainerLow = Color(0xFF191D17),
    surfaceContainer = Color(0xFF1D211B),
    surfaceContainerHigh = Color(0xFF282B25),
    surfaceContainerHighest = Color(0xFF333630),

    outline = Color(0xFF8C9388),
    outlineVariant = Color(0xFF424940),
    scrim = Color.Black,
)

/** A little rounder than the Material defaults, to match the icon's badge. */
private val QtOtpShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Every screen's top bar, so they agree: a tinted band rather than the default
 * transparent one, which on this palette would leave the bar and the list it
 * scrolls over the same colour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun qtOtpTopAppBarColors() = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
)

@Composable
fun QtOtpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = QtOtpShapes,
        content = content,
    )
}

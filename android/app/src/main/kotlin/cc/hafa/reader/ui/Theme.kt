package cc.hafa.reader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import cc.hafa.reader.R

/** The web app's palette (web/src/app.css), one set per light or dark. */
@Immutable
data class ReaderColors(
    /** Main background. */
    val page: Color,
    /** Sidebar and pressed backgrounds. */
    val side: Color,
    /** Raised surfaces: fields and the selected sidebar row. */
    val panel: Color,
    /** Borders and dividers. */
    val line: Color,
    /** Main text. */
    val text: Color,
    /** Secondary text and icons. */
    val muted: Color,
    /** Links, switches that are on, and the selected view's icon. */
    val accent: Color,
    /** Tinted background behind accented things. */
    val accentSoft: Color,
    /** A set star, and sync needing attention. */
    val star: Color,
    /** Sync up to date. */
    val ok: Color,
    /** Titles of read articles. */
    val read: Color,
)

private val LightColors = ReaderColors(
    page = Color(0xFFFFFFFF),
    side = Color(0xFFEEF0EA),
    panel = Color(0xFFFFFFFF),
    line = Color(0xFFDCDED8),
    text = Color(0xFF20231F),
    muted = Color(0xFF666B63),
    accent = Color(0xFF2F6FD0),
    accentSoft = Color(0xFFE2ECFA),
    star = Color(0xFFD99A00),
    ok = Color(0xFF2F9A5C),
    read = Color(0xFF8A8F86),
)

private val DarkColors = ReaderColors(
    page = Color(0xFF151713),
    side = Color(0xFF20231D),
    panel = Color(0xFF1B1E19),
    line = Color(0xFF2D312A),
    text = Color(0xFFE6E8E2),
    muted = Color(0xFF9BA096),
    accent = Color(0xFF7AA8EE),
    accentSoft = Color(0xFF22324C),
    star = Color(0xFFF0BE3A),
    ok = Color(0xFF5CC48A),
    read = Color(0xFF7D8279),
)

private val LocalReaderColors = staticCompositionLocalOf { LightColors }

private fun publicSans(weight: Int) =
    Font(R.font.public_sans, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

private fun fraunces(weight: Int) =
    Font(R.font.fraunces, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Public Sans, the web app's text face. */
val PublicSans = FontFamily(publicSans(400), publicSans(500), publicSans(600), publicSans(650), publicSans(700), publicSans(800))

/** Fraunces, the web app's heading face. */
val Fraunces = FontFamily(fraunces(400), fraunces(600), fraunces(700), fraunces(800))

// The web sets no letter spacing, so neither does any style here.
private val ReaderTypography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = Fraunces, letterSpacing = 0.sp),
        displayMedium = base.displayMedium.copy(fontFamily = Fraunces, letterSpacing = 0.sp),
        displaySmall = base.displaySmall.copy(fontFamily = Fraunces, letterSpacing = 0.sp),
        headlineLarge = base.headlineLarge.copy(fontFamily = Fraunces, letterSpacing = 0.sp),
        headlineMedium = base.headlineMedium.copy(fontFamily = Fraunces, letterSpacing = 0.sp),
        headlineSmall = base.headlineSmall.copy(fontFamily = Fraunces, letterSpacing = 0.sp),
        titleLarge = base.titleLarge.copy(fontFamily = PublicSans, letterSpacing = 0.sp),
        titleMedium = base.titleMedium.copy(fontFamily = PublicSans, letterSpacing = 0.sp),
        titleSmall = base.titleSmall.copy(fontFamily = PublicSans, letterSpacing = 0.sp),
        bodyLarge = base.bodyLarge.copy(fontFamily = PublicSans, letterSpacing = 0.sp, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.copy(fontFamily = PublicSans, letterSpacing = 0.sp, fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = base.bodySmall.copy(fontFamily = PublicSans, letterSpacing = 0.sp, fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = base.labelLarge.copy(fontFamily = PublicSans, letterSpacing = 0.sp),
        labelMedium = base.labelMedium.copy(fontFamily = PublicSans, letterSpacing = 0.sp),
        labelSmall = base.labelSmall.copy(fontFamily = PublicSans, letterSpacing = 0.sp),
    )
}

/** The reader's colors, following the system's light or dark setting. */
@Composable
fun ReaderTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        typography = ReaderTypography,
        colorScheme = scheme.copy(
            primary = colors.accent,
            onPrimary = colors.page,
            primaryContainer = colors.accentSoft,
            onPrimaryContainer = colors.text,
            secondaryContainer = colors.accentSoft,
            onSecondaryContainer = colors.text,
            background = colors.page,
            onBackground = colors.text,
            surface = colors.page,
            onSurface = colors.text,
            surfaceVariant = colors.side,
            onSurfaceVariant = colors.muted,
            surfaceContainer = colors.panel,
            surfaceContainerLow = colors.side,
            surfaceContainerHigh = colors.panel,
            outline = colors.line,
            outlineVariant = colors.line,
        ),
    ) {
        CompositionLocalProvider(LocalReaderColors provides colors, content = content)
    }
}

/** Access to the reader's palette. */
object ReaderTheme {
    /** The palette in use. */
    val colors: ReaderColors
        @Composable get() = LocalReaderColors.current
}

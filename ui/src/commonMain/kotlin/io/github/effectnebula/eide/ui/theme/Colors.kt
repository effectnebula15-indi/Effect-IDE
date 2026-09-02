package io.github.effectnebula.eide.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Палитра. Своя, а не копия Darcula: выглядит так же, но не тащит чужое имя и чужие права.
 *
 * material3 намеренно не используется — свой набор токенов честнее описывает то, что нужно
 * IDE (гаттер, выделение, синтаксис), и не тянет мегабайты компонентов, которых мы не рисуем.
 */
@Immutable
class EideColors(
    val background: Color,
    val panel: Color,
    val border: Color,
    val text: Color,
    val textDim: Color,
    val accent: Color,
    val selection: Color,
    val caret: Color,
    val gutter: Color,
    val gutterText: Color,
    val error: Color,
    val warning: Color,
    val syntaxKeyword: Color,
    val syntaxString: Color,
    val syntaxNumber: Color,
    val syntaxComment: Color,
    val syntaxFunction: Color,
)

val EideDarkColors: EideColors = EideColors(
    background = Color(0xFF1E1F22),
    panel = Color(0xFF2B2D30),
    border = Color(0xFF393B40),
    text = Color(0xFFBCBEC4),
    textDim = Color(0xFF6F737A),
    accent = Color(0xFF3574F0),
    selection = Color(0xFF214283),
    caret = Color(0xFFCED0D6),
    gutter = Color(0xFF1E1F22),
    gutterText = Color(0xFF4E5157),
    error = Color(0xFFDB5C5C),
    warning = Color(0xFFE3A008),
    syntaxKeyword = Color(0xFFCF8E6D),
    syntaxString = Color(0xFF6AAB73),
    syntaxNumber = Color(0xFF2AACB8),
    syntaxComment = Color(0xFF7A7E85),
    syntaxFunction = Color(0xFF56A8F5),
)

val EideLightColors: EideColors = EideColors(
    background = Color(0xFFFFFFFF),
    panel = Color(0xFFF7F8FA),
    border = Color(0xFFEBECF0),
    text = Color(0xFF000000),
    textDim = Color(0xFF818594),
    accent = Color(0xFF3574F0),
    selection = Color(0xFFA6D2FF),
    caret = Color(0xFF000000),
    gutter = Color(0xFFFFFFFF),
    gutterText = Color(0xFFA8ADBD),
    error = Color(0xFFE55765),
    warning = Color(0xFFC48F00),
    syntaxKeyword = Color(0xFF0033B3),
    syntaxString = Color(0xFF067D17),
    syntaxNumber = Color(0xFF1750EB),
    syntaxComment = Color(0xFF8C8C8C),
    syntaxFunction = Color(0xFF00627A),
)

val LocalEideColors = staticCompositionLocalOf { EideDarkColors }

object Eide {
    val colors: EideColors
        @Composable @ReadOnlyComposable get() = LocalEideColors.current
}

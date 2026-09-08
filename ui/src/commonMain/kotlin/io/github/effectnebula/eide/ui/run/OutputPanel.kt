package io.github.effectnebula.eide.ui.run

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.effectnebula.eide.ui.theme.Eide

/**
 * Панель вывода программы.
 *
 * Сама доезжает до конца при новых строках — но только если человек и так
 * смотрел на конец. Иначе прокрутка вырывается из рук у того, кто отлистал
 * назад посмотреть на трассировку, а программа в это время продолжает писать.
 */
@Composable
fun OutputPanel(text: String, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()

    // Порог, а не точное равенство: пока идёт прокрутка, значение отстаёт от
    // максимума на несколько пикселей, и строгая проверка не срабатывает почти
    // никогда.
    val wasAtBottom = remember(text) { scroll.value >= scroll.maxValue - STICK_THRESHOLD_PX }

    LaunchedEffect(text) {
        if (wasAtBottom) scroll.scrollTo(scroll.maxValue)
    }

    Column(
        modifier
            .fillMaxSize()
            .background(Eide.colors.panel)
            .verticalScroll(scroll)
            .padding(12.dp)
    ) {
        BasicText(
            text = text.ifEmpty { "вывод программы появится здесь" },
            style = TextStyle(
                color = if (text.isEmpty()) Eide.colors.textDim else Eide.colors.text,
                fontSize = 13.sp,
                fontFamily = Eide.editorFont,
            ),
        )
    }
}

private const val STICK_THRESHOLD_PX = 48

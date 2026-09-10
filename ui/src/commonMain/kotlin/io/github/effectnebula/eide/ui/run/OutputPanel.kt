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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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

    // Следим за концом, пока человек сам не отлистал назад.
    //
    // Прежняя редакция спрашивала «был ли он внизу» прямо в момент прихода
    // текста — и не работала никогда. До первой раскладки `maxValue` равен
    // Int.MAX_VALUE, сравнение с ним даёт «не внизу», а вычислялось это один раз
    // на каждое значение текста. Панель так и оставалась на первой строке,
    // сколько бы программа ни писала. Ни один тест этого не показал: в стенде
    // Compose начальный `maxValue` равен нулю, и там всё «работало».
    var follow by remember { mutableStateOf(true) }

    // Решение принимается только по окончании настоящей прокрутки: важен переход
    // «крутили → перестали», а не само состояние. Состояние в первый раз
    // спрашивается до раскладки, когда maxValue ещё Int.MAX_VALUE, и любой ответ
    // по нему — «не внизу». Ровно на этом всё и ломалось дважды.
    //
    // Своя прокрутка тоже даёт этот переход и оставляет follow включённым: она
    // заканчивается ровно в конце.
    LaunchedEffect(scroll) {
        var wasScrolling = false
        snapshotFlow { scroll.isScrollInProgress }.collect { scrolling ->
            // Порог, а не точное равенство: значение отстаёт от максимума на
            // несколько пикселей, и строгая проверка не срабатывает почти никогда.
            if (wasScrolling && !scrolling) {
                follow = scroll.value >= scroll.maxValue - STICK_THRESHOLD_PX
            }
            wasScrolling = scrolling
        }
    }

    // Ключом идёт и maxValue: в момент, когда меняется текст, разметки нового
    // текста ещё нет, и maxValue отвечает про старую.
    LaunchedEffect(text, scroll.maxValue) {
        if (follow) scroll.scrollTo(scroll.maxValue)
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

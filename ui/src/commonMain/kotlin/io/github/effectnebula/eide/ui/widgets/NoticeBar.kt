package io.github.effectnebula.eide.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.effectnebula.eide.ui.theme.Eide

/**
 * Полоса с сообщением о том, что не получилось.
 *
 * Существует ради простого правила: неудача, о которой человек не узнал, хуже
 * падения. Файл, который не открылся, обязан сказать об этом — а не оставить
 * экран прежним, как будто по нему не щёлкали.
 *
 * Диалога здесь нет намеренно: сообщение не требует ответа и не должно
 * перегораживать работу.
 */
@Composable
fun NoticeBar(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .background(Eide.colors.panel)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        style = TextStyle(color = Eide.colors.error, fontSize = 12.sp),
    )
}

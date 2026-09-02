package io.github.effectnebula.eide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.effectnebula.eide.core.text.Rope
import io.github.effectnebula.eide.ui.theme.Eide

/**
 * Корень приложения. Пока это заглушка ровно одного назначения: доказать, что связка
 * Compose → ui → core собирается и запускается на обеих платформах.
 *
 * Настоящий рендер редактора (виртуализация строк, курсор, выделение) приходит следующим
 * модулем — до него BasicText здесь стоит намеренно, чтобы не создавать иллюзию,
 * будто редактор уже есть.
 */
@Composable
fun EideRoot(document: Rope) {
    Column(Modifier.fillMaxSize().background(Eide.colors.background)) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).background(Eide.colors.panel).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BasicText("Effect IDE", style = TextStyle(color = Eide.colors.text, fontSize = 13.sp))
            BasicText(
                "${document.lineCount} строк · ${document.length} символов",
                style = TextStyle(color = Eide.colors.textDim, fontSize = 12.sp),
            )
        }

        Box(Modifier.fillMaxSize().padding(12.dp)) {
            BasicText(
                text = document.substring(0, minOf(document.length, 2_000)),
                style = TextStyle(
                    color = Eide.colors.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                ),
            )
        }
    }
}

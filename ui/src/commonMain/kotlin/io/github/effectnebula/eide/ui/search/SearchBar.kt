package io.github.effectnebula.eide.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.effectnebula.eide.core.editor.SearchQuery
import io.github.effectnebula.eide.core.editor.SearchSession
import io.github.effectnebula.eide.ui.theme.Eide

/**
 * Строка поиска и замены.
 *
 * Поля ввода здесь — `BasicTextField`, и это не противоречит ADR-001. Его
 * отвергли для редактора кода, где текст в мегабайты и нужна виртуализация;
 * строка поиска — это десяток символов в одну строку, ровно то, для чего он
 * и сделан. Писать ради неё второй редактор было бы упрямством.
 *
 * [onChanged] дёргается после всего, что меняет документ или курсор: снаружи
 * по нему обновляют экран.
 */
@Composable
fun SearchBar(
    session: SearchSession,
    onClose: () -> Unit,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pattern by remember { mutableStateOf(session.query.pattern) }
    var replacement by remember { mutableStateOf("") }
    var caseSensitive by remember { mutableStateOf(session.query.caseSensitive) }
    var isRegex by remember { mutableStateOf(session.query.isRegex) }
    var wholeWord by remember { mutableStateOf(session.query.wholeWord) }
    var showReplace by remember { mutableStateOf(false) }

    fun apply() {
        session.setQuery(
            SearchQuery(
                pattern = pattern,
                isRegex = isRegex,
                caseSensitive = caseSensitive,
                wholeWord = wholeWord,
            )
        )
        onChanged()
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(Eide.colors.panel)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Field(
                value = pattern,
                placeholder = "найти",
                onValueChange = {
                    pattern = it
                    apply()
                },
                onSubmit = {
                    session.findNext()
                    onChanged()
                },
            )

            Toggle("Aa", caseSensitive, "учитывать регистр") { caseSensitive = !caseSensitive; apply() }
            Toggle(".*", isRegex, "регулярное выражение") { isRegex = !isRegex; apply() }
            Toggle("W", wholeWord, "слово целиком") { wholeWord = !wholeWord; apply() }

            Action("◀") { session.findPrevious(); onChanged() }
            Action("▶") { session.findNext(); onChanged() }

            Status(session, pattern)

            Toggle("замена", showReplace, "показать замену") { showReplace = !showReplace }
            Action("×", onClose)
        }

        if (showReplace) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Field(
                    value = replacement,
                    placeholder = if (isRegex) "заменить на (можно \$1)" else "заменить на",
                    onValueChange = { replacement = it },
                    onSubmit = {
                        session.replaceCurrent(replacement)
                        onChanged()
                    },
                )
                Action("заменить") { session.replaceCurrent(replacement); onChanged() }
                Action("все") { session.replaceAll(replacement); onChanged() }
            }
        }
    }
}

@Composable
private fun Status(session: SearchSession, pattern: String) {
    val error = session.error
    val text = when {
        error != null -> error
        pattern.isEmpty() -> ""
        else -> {
            val count = session.count()
            when {
                count == 0 -> "нет совпадений"
                session.countIsExact() -> "совпадений: $count"
                // Потолок счёта: врать точным числом нельзя.
                else -> "совпадений: больше $count"
            }
        }
    }

    BasicText(
        text = text,
        style = TextStyle(
            color = if (error != null) Eide.colors.error else Eide.colors.textDim,
            fontSize = 12.sp,
        ),
    )
}

@Composable
private fun Field(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Box(
        Modifier
            .widthIn(min = 160.dp)
            .background(Eide.colors.background)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (value.isEmpty()) {
            BasicText(
                placeholder,
                style = TextStyle(color = Eide.colors.textDim, fontSize = 13.sp),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = Eide.colors.text,
                fontSize = 13.sp,
                fontFamily = Eide.editorFont,
            ),
            cursorBrush = SolidColor(Eide.colors.caret),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        )
    }
}

/**
 * Кнопки — через `pointerInput`, а не `clickable`, по той же причине, что и
 * в ряду клавиш: `clickable` забирает фокус, а вместе с ним уходит и курсор
 * из поля ввода.
 */
@Composable
private fun Action(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .background(Eide.colors.border)
            .pointerInput(label, onClick) { detectTapGestures { onClick() } }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        BasicText(label, style = TextStyle(color = Eide.colors.text, fontSize = 12.sp))
    }
}

@Composable
private fun Toggle(label: String, active: Boolean, hint: String, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (active) Eide.colors.accent else Eide.colors.border)
            .pointerInput(label, active) { detectTapGestures { onClick() } }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = if (active) Color.White else Eide.colors.textDim,
                fontSize = 12.sp,
            ),
        )
    }
    // hint пока не показывается: всплывающих подсказок в своём UI-ките ещё нет,
    // а придумывать их ради трёх кнопок — не сейчас.
    @Suppress("UNUSED_EXPRESSION")
    hint
}

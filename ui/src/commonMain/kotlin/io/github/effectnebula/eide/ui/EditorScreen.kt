package io.github.effectnebula.eide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.effectnebula.eide.core.editor.EditorState
import io.github.effectnebula.eide.core.editor.FoldState
import io.github.effectnebula.eide.core.editor.SearchSession
import io.github.effectnebula.eide.core.syntax.LineHighlighter
import io.github.effectnebula.eide.core.syntax.TokenKind
import io.github.effectnebula.eide.ui.editor.CodeEditor
import io.github.effectnebula.eide.ui.editor.EditorColors
import io.github.effectnebula.eide.ui.editor.GutterMark
import io.github.effectnebula.eide.ui.editor.observeEditor
import io.github.effectnebula.eide.ui.theme.Eide

/** Размер шрифта по умолчанию. Тот же, что в IntelliJ на десктопе. */
const val DEFAULT_FONT_SIZE_SP = 13f

/**
 * Экран редактирования: сам редактор и строка состояния.
 *
 * Строка состояния показывает позицию курсора в человеческих координатах —
 * с единицы, а не с нуля: внутри всё считается от нуля, но пользователю
 * показывают то, что показывают все редакторы.
 */
@Composable
fun EditorScreen(
    state: EditorState,
    modifier: Modifier = Modifier,
    search: SearchSession? = null,
    highlighter: LineHighlighter = LineHighlighter.None,
    fontSizeSp: Float = DEFAULT_FONT_SIZE_SP,
    /**
     * Куда сообщать размер, выбранный двумя пальцами. `null` — жест выключен.
     *
     * Размер живёт снаружи потому, что переживает и смену файла, и уход в дерево:
     * человек настроил его один раз, а не для каждой вкладки.
     */
    onFontSizeChange: ((Float) -> Unit)? = null,
    gutterMarks: Map<Int, GutterMark> = emptyMap(),
    /**
     * Свёртка блоков. Живёт снаружи вместе с файлом: свёрнутое должно
     * переживать переключение вкладок.
     */
    folds: FoldState? = null,
) {
    Column(modifier.fillMaxSize().background(Eide.colors.background)) {
        CodeEditor(
            state = state,
            colors = editorColors(),
            modifier = Modifier.fillMaxWidth().weight(1f),
            search = search,
            highlighter = highlighter,
            fontSizeSp = fontSizeSp,
            onFontSizeChange = onFontSizeChange,
            gutterMarks = gutterMarks,
            folds = folds,
        )

        StatusBar(state)
    }
}

/**
 * Строка состояния вынесена отдельной функцией не ради красоты: Compose
 * пересобирает ближайшую функцию, а не весь экран. Здесь подписка на ревизию
 * уместна, а в редакторе рядом — нет, там правка должна доходить до отрисовки,
 * минуя пересборку.
 */
@Composable
private fun StatusBar(state: EditorState) {
    // Чтение ревизии — это и есть подписка: без него строка застынет на месте.
    observeEditor(state)

    Row(
        Modifier
            .fillMaxWidth()
            .background(Eide.colors.panel)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val caret = state.carets.primary
        val line = state.text.lineOf(caret.head)
        val column = caret.head - state.text.lineStart(line)

        Status("строка ${line + 1}, столбец ${column + 1}")
        if (state.carets.carets.size > 1) Status("курсоров ${state.carets.carets.size}")
        if (!caret.isEmpty) Status("выделено ${caret.end - caret.start}")
        Status("строк ${state.text.lineCount}")
    }
}

/** Цвета редактора из темы. Нужны и снаружи — например, ряду клавиш. */
@Composable
fun editorColors(): EditorColors = EditorColors(
    background = Eide.colors.background,
    text = Eide.colors.text,
    gutterBackground = Eide.colors.gutter,
    gutterText = Eide.colors.gutterText,
    currentLineGutterText = Eide.colors.text,
    selection = Eide.colors.selection,
    caret = Eide.colors.caret,
    // Бледнее выделения: подсветка совпадений лежит под ним, и выделенное
    // совпадение должно оставаться отличимым от остальных.
    searchMatch = Eide.colors.selection.copy(alpha = 0.35f),
    vcs = mapOf(
        GutterMark.Added to Eide.colors.vcsAdded,
        GutterMark.Modified to Eide.colors.vcsModified,
        GutterMark.DeletedBelow to Eide.colors.vcsDeleted,
    ),
    syntax = mapOf(
        TokenKind.Keyword to Eide.colors.syntaxKeyword,
        TokenKind.String to Eide.colors.syntaxString,
        TokenKind.Number to Eide.colors.syntaxNumber,
        TokenKind.Comment to Eide.colors.syntaxComment,
        TokenKind.Declaration to Eide.colors.syntaxFunction,
    ),
)

@Composable
private fun Status(text: String) {
    BasicText(
        text = text,
        style = TextStyle(
            color = Eide.colors.textDim,
            fontSize = 11.sp,
            fontFamily = Eide.editorFont,
        ),
    )
}

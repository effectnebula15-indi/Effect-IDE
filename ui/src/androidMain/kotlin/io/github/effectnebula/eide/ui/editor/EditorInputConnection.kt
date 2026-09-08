package io.github.effectnebula.eide.ui.editor

import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import io.github.effectnebula.eide.core.editor.InputSession

/**
 * Мост между `InputConnection` Android и [InputSession].
 *
 * Здесь намеренно нет ни одного решения о том, что должно случиться с текстом:
 * всё это в `:core`, где проверяется тестами. Здесь — только перевод вызовов и
 * оповещение клавиатуры о том, что текст изменился.
 *
 * Наследование от `BaseInputConnection` — не лень: его `sendKeyEvent`
 * пересылает событие обратно во View, и клавиши, которые экранная клавиатура
 * шлёт как настоящие нажатия (стрелки, Backspace у некоторых клавиатур),
 * приходят в тот же обработчик, что и с аппаратной клавиатуры. Дублировать эту
 * логику здесь означало бы разъехаться с ней через месяц.
 *
 * Ожидание, которое надо проверить на устройстве: методы `InputConnection`
 * вызываются в главном потоке. Так работает механизм по умолчанию (`getHandler`
 * не переопределён), и на это же рассчитывает `BasicTextField`, но `EditorState`
 * не потокобезопасен, и цена ошибки здесь — редкие необъяснимые порчи текста.
 */
internal class EditorInputConnection(
    view: View,
    private val ime: InputSession,
    private val onSelectionChanged: () -> Unit,
) : BaseInputConnection(view, /* fullEditor = */ true) {

    private var batchDepth = 0

    /** Правки внутри batch не оповещают клавиатуру: она узнает результат целиком. */
    private fun changed() {
        if (batchDepth == 0) onSelectionChanged()
    }

    override fun beginBatchEdit(): Boolean {
        batchDepth++
        return true
    }

    override fun endBatchEdit(): Boolean {
        if (batchDepth > 0) batchDepth--
        if (batchDepth == 0) onSelectionChanged()
        return batchDepth > 0
    }

    // --- чтение ----------------------------------------------------------------

    override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence =
        ime.textBeforeCursor(length)

    override fun getTextAfterCursor(length: Int, flags: Int): CharSequence =
        ime.textAfterCursor(length)

    override fun getSelectedText(flags: Int): CharSequence? =
        ime.selectedText().ifEmpty { null }

    /**
     * Заглавные буквы после точки нам не нужны: это редактор кода.
     */
    override fun getCursorCapsMode(reqModes: Int): Int = 0

    /**
     * Полный текст клавиатуре не отдаём.
     *
     * `ExtractedText` задумывался для полноэкранного режима IME, который мы
     * выключаем (`IME_FLAG_NO_EXTRACT_UI`), а документ может быть в мегабайты —
     * отдавать его целиком на каждый запрос нельзя, а отдавать кусок, выдавая
     * за целое, значит врать клавиатуре о позициях.
     */
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null

    // --- правка ----------------------------------------------------------------

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        ime.commitText(text?.toString().orEmpty(), newCursorPosition)
        changed()
        return true
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        ime.setComposingText(text?.toString().orEmpty(), newCursorPosition)
        changed()
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        ime.setComposingRegion(start, end)
        changed()
        return true
    }

    override fun finishComposingText(): Boolean {
        ime.finishComposing()
        changed()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        ime.deleteSurrounding(beforeLength, afterLength)
        changed()
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        ime.deleteSurroundingInCodePoints(beforeLength, afterLength)
        changed()
        return true
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        ime.setSelection(start, end)
        changed()
        return true
    }

    /**
     * Подсказку из списка завершения не принимаем.
     *
     * Своё автодополнение будет через LSP и своим списком; принимать словарные
     * подсказки клавиатуры в коде — вред, а не польза.
     */
    override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?): Boolean = false

    /**
     * Координаты курсора клавиатуре пока не сообщаем.
     *
     * Без этого не работает плавающее окно подсказок над курсором у некоторых
     * клавиатур. Чтобы это заработало, нужен `CursorAnchorInfo` с реальной
     * геометрией строки — она есть в отрисовке, но доставать её сюда рано.
     */
    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
}

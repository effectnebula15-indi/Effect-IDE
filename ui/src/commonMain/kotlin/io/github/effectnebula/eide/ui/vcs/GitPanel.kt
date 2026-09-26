package io.github.effectnebula.eide.ui.vcs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.effectnebula.eide.ui.theme.Eide

/**
 * Что случилось с файлом относительно последнего коммита.
 *
 * Свой перечень, а не тот, что в `:vcs`: `:ui` не имеет права зависеть от `:vcs`
 * (граница проверяется `checkArchitecture`), и точка сборки переводит одно в
 * другое — ровно как с пометками гаттера.
 */
enum class ChangeKind { Untracked, Added, Modified, Deleted, Conflicted }

data class ChangedFile(val path: String, val kind: ChangeKind)

/**
 * Панель git: ветка, изменённые файлы, сообщение и коммит.
 *
 * **Отдельного добавления в индекс здесь нет.** «Зафиксировать» добавляет всё и
 * коммитит. Частичный индекс — это своя половина интерфейса (галочки у файлов,
 * разница между «в индексе» и «в рабочей копии», понятие staged hunk), а нужен
 * он тому, кто уже знает git настолько, что откроет консоль. Цена названа здесь,
 * чтобы не выглядело недоделкой: коммит здесь всегда «всё, что изменено».
 *
 * Панель ничего не делает сама: она показывает переданное и зовёт обратные вызовы.
 * Работа с репозиторием — чтение статуса, запись коммита — идёт в фоне у того,
 * кто панель показывает; здесь ей заниматься нельзя, это главный поток.
 */
@Composable
fun GitPanel(
    branch: String?,
    files: List<ChangedFile>,
    identity: String?,
    busy: Boolean,
    notice: String?,
    onRefresh: () -> Unit,
    onCommit: (String) -> Unit,
    onOpen: (ChangedFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    var message by remember { mutableStateOf("") }

    val blocker = when {
        identity == null -> "не задан автор: git config user.name и user.email"
        files.isEmpty() -> "нечего фиксировать"
        message.isBlank() -> "нужно сообщение коммита"
        busy -> "работаю…"
        else -> null
    }

    Column(modifier.fillMaxSize().background(Eide.colors.background)) {
        Row(
            Modifier.fillMaxWidth().background(Eide.colors.panel).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = branch?.let { "ветка $it" } ?: "нет репозитория",
                style = TextStyle(color = Eide.colors.text, fontSize = 13.sp),
            )
            BasicText(
                text = identity ?: "автор не задан",
                modifier = Modifier.weight(1f),
                style = TextStyle(color = Eide.colors.textDim, fontSize = 11.sp),
            )
            Chip("обновить", Eide.colors.border, onClick = onRefresh)
        }

        BasicTextField(
            value = message,
            onValueChange = { message = it },
            modifier = Modifier.fillMaxWidth().background(Eide.colors.background).padding(10.dp),
            textStyle = TextStyle(color = Eide.colors.text, fontSize = 13.sp),
            cursorBrush = SolidColor(Eide.colors.caret),
            decorationBox = { field ->
                if (message.isEmpty()) {
                    BasicText(
                        "сообщение коммита",
                        style = TextStyle(color = Eide.colors.textDim, fontSize = 13.sp),
                    )
                }
                field()
            },
        )

        Row(
            Modifier.fillMaxWidth().background(Eide.colors.border).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip(
                label = "зафиксировать (${files.size})",
                color = if (blocker == null) Eide.colors.accent else Eide.colors.panel,
            ) {
                if (blocker == null) {
                    onCommit(message.trim())
                    message = ""
                }
            }
            // Причина, по которой кнопка не работает, написана рядом с кнопкой.
            // Серая кнопка без объяснения — это «сломалось», а не «не хватает».
            BasicText(
                text = notice ?: blocker.orEmpty(),
                style = TextStyle(color = Eide.colors.textDim, fontSize = 11.sp),
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(files) { file ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(file) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BasicText(
                        text = file.kind.letter,
                        style = TextStyle(color = file.kind.color, fontSize = 12.sp, fontFamily = Eide.editorFont),
                    )
                    BasicText(
                        text = file.path,
                        style = TextStyle(color = Eide.colors.text, fontSize = 12.sp, fontFamily = Eide.editorFont),
                    )
                }
            }
        }
    }
}

/** Буква статуса — та же, что показывает `git status --short`. */
private val ChangeKind.letter: String
    get() = when (this) {
        ChangeKind.Untracked -> "?"
        ChangeKind.Added -> "A"
        ChangeKind.Modified -> "M"
        ChangeKind.Deleted -> "D"
        ChangeKind.Conflicted -> "!"
    }

private val ChangeKind.color: Color
    @Composable get() = when (this) {
        ChangeKind.Untracked -> Eide.colors.textDim
        ChangeKind.Added -> Eide.colors.vcsAdded
        ChangeKind.Deleted -> Eide.colors.vcsDeleted
        ChangeKind.Conflicted -> Eide.colors.vcsDeleted
        ChangeKind.Modified -> Eide.colors.vcsModified
    }

@Composable
private fun Chip(label: String, color: Color, onClick: () -> Unit) {
    BasicText(
        text = label,
        modifier = Modifier
            .background(color)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        style = TextStyle(color = Eide.colors.text, fontSize = 12.sp),
    )
}

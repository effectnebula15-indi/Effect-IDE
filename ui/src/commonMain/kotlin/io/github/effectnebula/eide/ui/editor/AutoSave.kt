package io.github.effectnebula.eide.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import io.github.effectnebula.eide.core.editor.EditorState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Пауза в наборе, после которой буфер уходит на диск.
 *
 * Цена выбрана в обе стороны: меньше — лишние записи на каждое слово, больше —
 * больше потерянного текста, если систему не устроит наше существование.
 */
const val AUTOSAVE_QUIET_MILLIS: Long = 1_000

/**
 * Автосохранение буфера после паузы в наборе.
 *
 * Android вправе убить процесс в любой момент и без предупреждения. Редактор,
 * теряющий текст, не редактор — а «сохрани сам, я не обещал» здесь не работает:
 * на телефоне приложение уходит в фон от входящего звонка.
 *
 * Сохранение — `suspend`: решать, в каком потоке писать файл, должен тот, кто
 * знает его размер. Здесь известно только, когда писать.
 *
 * Это ещё не журнал правок из плана: при убийстве процесса теряется последняя
 * секунда набора. Настоящая защита — запись правок, а не файла целиком, и она
 * появится вместе с несколькими открытыми файлами.
 *
 * Считаются версии документа, а не ревизии редактора. Ревизия растёт и от
 * движения курсора, и это давало две беды сразу: файл переписывался на диск
 * после каждого тыка в текст, и — хуже — пауза отсчитывалась заново, то есть
 * человек, водивший курсором по уже набранному тексту, откладывал запись
 * своих правок на всё это время.
 */
/**
 * Когда пора писать: версии документа, успокоившиеся после паузы.
 *
 * Вынесено из composable отдельной функцией не ради красоты, а ради проверки:
 * здесь всё решают паузы, а паузы в стенде Compose идут по настоящим часам.
 * Обычным flow-тестом с виртуальным временем то же самое проверяется точно и
 * без ожиданий.
 */
internal fun saveSignals(
    revisions: Flow<Long>,
    quietMillis: Long,
    versionOf: () -> Long,
): Flow<Long> = revisions
    // Ревизия растёт и от движения курсора; версия документа — только от правки.
    // Различие важно дважды: лишняя запись не нужна, а отсчёт паузы заново
    // откладывает запись настоящей правки на всё время, пока водят курсором.
    .map { versionOf() }
    .distinctUntilChanged()
    .debounce(quietMillis)

@Composable
fun AutoSave(
    state: EditorState,
    quietMillis: Long = AUTOSAVE_QUIET_MILLIS,
    save: suspend () -> Unit,
) {
    val revision = rememberEditorRevision(state)
    val currentSave by rememberUpdatedState(save)

    LaunchedEffect(revision, quietMillis) {
        // Первое значение snapshotFlow — текущее состояние, а не изменение:
        // сохранять сразу после открытия файла незачем.
        val opened = state.document.version

        saveSignals(snapshotFlow { revision.longValue }, quietMillis) { state.document.version }
            .collect { version -> if (version != opened) currentSave() }
    }
}

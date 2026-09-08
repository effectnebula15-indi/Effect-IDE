package io.github.effectnebula.eide.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.effectnebula.eide.runner.android.CanvasArea
import io.github.effectnebula.eide.ui.canvas.fitInside
import kotlin.math.abs

/**
 * Вывод графики программы пользователя.
 *
 * Живёт в модуле приложения, а не в `:ui`: `:ui` не имеет права знать про
 * `:runner:android`, а `CanvasArea` — часть контракта с раннером. Когда
 * появится графика на десктопе, общая часть переедет в `:ui` за интерфейсом
 * источника кадров; до тех пор выдумывать этот интерфейс не на чем.
 *
 * Экран полноэкранный намеренно (Шаг 3): свайп возвращает к коду, а программа
 * продолжает работать. Android при этом уничтожает поверхность вывода, но
 * кадры живут в разделяемой памяти — поверхность лишь потребитель, и
 * останавливать программу незачем.
 */
@Composable
fun CanvasScreen(area: CanvasArea, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val bitmap = remember(area) { area.createBitmap() }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var frame by remember(area) { mutableLongStateOf(0L) }

    /*
     * Опрос в такт экрана: смысла читать чаще нет, показать всё равно не выйдет.
     * Копия кадра идёт в главном потоке — полтора мегабайта это порядка 0.2 мс,
     * и это дешевле, чем синхронизировать фоновый поток с отрисовкой.
     */
    LaunchedEffect(area) {
        var shown = 0L
        while (true) {
            withFrameNanos { }
            val received = area.readFrameInto(bitmap, shown)
            if (received != 0L) {
                shown = received
                frame = received
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(onBack) {
                var travelled = 0f
                detectHorizontalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragEnd = { if (abs(travelled) > SWIPE_BACK_PX) onBack() },
                ) { _, delta -> travelled += delta }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Чтение — подписка: без него холст не перерисуется на новый кадр.
            @Suppress("UNUSED_EXPRESSION")
            frame

            val fit = fitInside(area.width, area.height, size.width, size.height)
            drawImage(
                image = image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(area.width, area.height),
                dstOffset = fit.offset,
                dstSize = fit.size,
                // Ближайший сосед: канва пиксельная, сглаживание превратило бы
                // её в кашу, да ещё и не бесплатно.
                filterQuality = FilterQuality.None,
            )
        }
    }
}

private const val SWIPE_BACK_PX = 120f

package io.github.effectnebula.eide.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.effectnebula.eide.runner.android.CanvasArea
import io.github.effectnebula.eide.ui.canvas.ContentPoint
import io.github.effectnebula.eide.ui.canvas.FitResult
import io.github.effectnebula.eide.ui.canvas.fitInside
import io.github.effectnebula.eide.ui.canvas.pointInContent

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

    /*
     * Возврат к коду — системной кнопкой «назад», а не свайпом по холсту.
     *
     * В плане было записано «свайп возвращает к коду», и пока графика была
     * картинкой, свайп по холсту работал. С появлением ввода он перестал:
     * программа, которой нужен палец, и жест возврата не могут делить один и
     * тот же экран — горизонтальное движение либо рисует, либо уводит, третьего
     * нет. Системный жест «назад» на современном Android это тоже свайп, только
     * от края, и он не отнимает у программы ни пикселя.
     */
    BackHandler(onBack = onBack)

    // Куда вписан кадр — знает отрисовка, а нужно это и вводу.
    val placement = remember(area) { FramePlacement(area.width, area.height) }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(area) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val type = when (event.type) {
                            PointerEventType.Press -> EVENT_POINTER_DOWN
                            PointerEventType.Move -> EVENT_POINTER_MOVE
                            PointerEventType.Release -> EVENT_POINTER_UP
                            else -> continue
                        }
                        for ((index, change) in event.changes.withIndex()) {
                            val point = placement.contentPoint(change.position) ?: continue
                            area.postEvent(type, pointer = index, x = point.x, y = point.y)
                        }
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Чтение — подписка: без него холст не перерисуется на новый кадр.
            @Suppress("UNUSED_EXPRESSION")
            frame

            val fit = fitInside(area.width, area.height, size.width, size.height)
            placement.fit = fit
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

/**
 * Где на экране лежит кадр — общее знание отрисовки и ввода.
 *
 * Обычный объект, а не состояние Compose: значение меняется каждый кадр, и
 * подписка означала бы пересборку шестьдесят раз в секунду ради числа, нужного
 * только обработчику пальца.
 */
private class FramePlacement(val contentWidth: Int, val contentHeight: Int) {
    var fit: FitResult? = null

    fun contentPoint(position: Offset): ContentPoint? {
        val current = fit ?: return null
        return pointInContent(current, contentWidth, contentHeight, position.x, position.y)
    }
}

/* Виды событий: те же числа, что EC_EVENT_* в eide_canvas.h. */
private const val EVENT_POINTER_DOWN = 1
private const val EVENT_POINTER_MOVE = 2
private const val EVENT_POINTER_UP = 3

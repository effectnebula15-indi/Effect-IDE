package io.github.effectnebula.eide.app

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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.effectnebula.eide.platform.desktop.DesktopCanvasArea
import io.github.effectnebula.eide.ui.canvas.fitInside
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * Вывод графики программы на десктопе.
 *
 * Формат кадра — RGBA по байту на канал без предумножения, ровно
 * `ColorType.RGBA_8888` с `UNPREMUL`. Совпадение не случайно: раскладка
 * выбиралась так, чтобы кадр уезжал в текстуру без пересчёта ни здесь, ни на
 * Android (там это `Bitmap.ARGB_8888`, чьё имя врёт — раскладка та же).
 */
@Composable
fun CanvasView(area: DesktopCanvasArea, modifier: Modifier = Modifier) {
    val pixels = remember(area) { ByteArray(area.frameBytes) }
    val bitmap = remember(area) {
        Bitmap().apply {
            allocPixels(ImageInfo(area.width, area.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL))
        }
    }
    var frame by remember(area) { mutableLongStateOf(0L) }

    // Опрос в такт экрана: читать чаще смысла нет, показать всё равно не выйдет.
    LaunchedEffect(area) {
        var shown = 0L
        while (true) {
            withFrameNanos { }
            val received = area.readFrame(pixels, shown)
            if (received != 0L) {
                bitmap.installPixels(pixels)
                shown = received
                frame = received
            }
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        Canvas(Modifier.fillMaxSize()) {
            // Чтение — подписка: без него холст не перерисуется на новый кадр.
            @Suppress("UNUSED_EXPRESSION")
            frame

            if (frame == 0L) return@Canvas

            val fit = fitInside(area.width, area.height, size.width, size.height)
            drawImage(
                image = bitmap.asComposeImageBitmap(),
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

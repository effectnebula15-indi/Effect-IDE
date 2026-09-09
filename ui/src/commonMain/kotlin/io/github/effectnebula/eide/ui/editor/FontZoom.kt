package io.github.effectnebula.eide.ui.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/** Границы размера шрифта. Ниже нижней читать нечего, выше верхней — не код, а плакат. */
internal const val MIN_FONT_SIZE_SP = 8f
internal const val MAX_FONT_SIZE_SP = 32f

/**
 * Изменение размера шрифта двумя пальцами.
 *
 * Написано вручную, а не через `detectTransformGestures`: тот срабатывает и на
 * одном пальце, забирая себе обычную прокрутку. Здесь события трогаются только
 * когда пальцев на экране два, поэтому прокрутка одним пальцем проходит мимо
 * нетронутой.
 *
 * [onZoom] получает не размер, а множитель за один шаг: накопление живёт снаружи,
 * вместе с самим размером.
 */
internal fun Modifier.fontZoom(onZoom: (Float) -> Unit): Modifier = pointerInput(onZoom) {
    awaitEachGesture {
        var previous = 0f
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }

            if (pressed.size < 2) {
                if (pressed.isEmpty()) return@awaitEachGesture
                // Второй палец подняли — следующее сведение начинается заново,
                // иначе первый же его шаг даст скачок на всю разницу.
                previous = 0f
                continue
            }

            val distance = (pressed[0].position - pressed[1].position).getDistance()
            if (previous > 0f && distance > 0f) {
                onZoom(distance / previous)
                // Гасим только то, что взяли себе: иначе прокрутка увидит остаток
                // жеста и поедет вместе с изменением размера.
                pressed.forEach { it.consume() }
            }
            previous = distance
        }
    }
}

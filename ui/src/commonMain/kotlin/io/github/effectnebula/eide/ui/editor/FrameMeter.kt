package io.github.effectnebula.eide.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos

/**
 * Замер кадров.
 *
 * Считает не средний fps, а то, что видно глазом: долю кадров, не уложившихся
 * в бюджет. Среднее в 58 fps с одним кадром по 200 мс выглядит прилично в отчёте
 * и отвратительно на экране.
 */
class FrameMeterState internal constructor() {
    var fps: Float by mutableFloatStateOf(0f)
        internal set
    var worstFrameMs: Float by mutableFloatStateOf(0f)
        internal set
    var jankFrames: Int by mutableIntStateOf(0)
        internal set
    var totalFrames: Int by mutableIntStateOf(0)
        internal set

    fun reset() {
        fps = 0f
        worstFrameMs = 0f
        jankFrames = 0
        totalFrames = 0
    }
}

/**
 * Включает непрерывную перерисовку и меряет интервалы между кадрами.
 *
 * Цена честная: пока замер включён, приложение рисует кадры постоянно и жжёт
 * батарею. Это инструмент измерения, а не режим по умолчанию.
 */
@Composable
fun rememberFrameMeter(enabled: Boolean, jankThresholdMs: Float = 17f): FrameMeterState {
    val state = remember { FrameMeterState() }

    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        state.reset()

        var previous = 0L
        var windowStart = 0L
        var framesInWindow = 0

        while (true) {
            withFrameNanos { now ->
                if (previous != 0L) {
                    val deltaMs = (now - previous) / 1_000_000f
                    state.totalFrames++
                    if (deltaMs > jankThresholdMs) state.jankFrames++
                    if (deltaMs > state.worstFrameMs) state.worstFrameMs = deltaMs

                    framesInWindow++
                    if (windowStart == 0L) windowStart = now
                    val windowMs = (now - windowStart) / 1_000_000f
                    if (windowMs >= 500f) {
                        state.fps = framesInWindow * 1000f / windowMs
                        framesInWindow = 0
                        windowStart = now
                    }
                }
                previous = now
            }
        }
    }

    return state
}

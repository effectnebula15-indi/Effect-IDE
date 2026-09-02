package io.github.effectnebula.eide.app

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import io.github.effectnebula.eide.core.text.Rope
import io.github.effectnebula.eide.ui.EideRoot

fun main() = application {
    val document = Rope.of(
        """
        # Effect IDE — десктопная сборка
        #
        # Здесь пока заглушка: рендер редактора ещё не написан.
        # Смысл этой сборки в том, что она собирается из тех же модулей,
        # что и Android-версия, и доказывает, что границы из Шага 3 держатся.

        def main():
            print("привет")

        main()
        """.trimIndent()
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "Effect IDE",
        state = WindowState(size = DpSize(1100.dp, 720.dp)),
    ) {
        EideRoot(document)
    }
}

package io.github.effectnebula.eide.app

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/**
 * JetBrains Mono из ресурсов classpath.
 *
 * Вариант NL — без лигатур. JetBrains Mono по умолчанию рисует `>=` как `≥`,
 * а `!=` как `≠`: в тексте одно, на экране другое. Учащемуся это мешает прямо,
 * а IntelliJ и сам поставляется с выключенными лигатурами. Когда появятся
 * настройки, это станет переключателем.
 *
 * Два начертания, а не десять: обычное и жирное. Остальные весят по четверти
 * мегабайта каждое и понадобятся не раньше, чем появится подсветка, которая
 * ими различает.
 *
 * Если шрифт не прочитается, останется системный моноширинный: редактор без
 * фирменного шрифта неприятен, редактор без шрифта — бесполезен.
 */
fun jetBrainsMono(): FontFamily = FontFamily(
    Font("fonts/JetBrainsMonoNL-Regular.ttf", FontWeight.Normal),
    Font("fonts/JetBrainsMonoNL-Bold.ttf", FontWeight.Bold),
)

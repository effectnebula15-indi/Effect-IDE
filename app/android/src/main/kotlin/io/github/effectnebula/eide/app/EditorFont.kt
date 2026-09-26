package io.github.effectnebula.eide.app

import android.content.res.AssetManager
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * JetBrains Mono из assets.
 *
 * Вариант NL — без лигатур. JetBrains Mono по умолчанию рисует `>=` как `≥`,
 * а `!=` как `≠`: в тексте одно, на экране другое. Учащемуся это мешает прямо,
 * а IntelliJ и сам поставляется с выключенными лигатурами. Когда появятся
 * настройки, это станет переключателем.
 *
 * Два начертания, а не десять: обычное и жирное. Остальные весят по четверти
 * мегабайта каждое, а бюджет APK — шестьдесят.
 *
 * Если файл не прочитается, останется системный моноширинный: редактор без
 * фирменного шрифта неприятен, редактор без шрифта — бесполезен.
 */
fun jetBrainsMono(assets: AssetManager): FontFamily = FontFamily(
    Font("fonts/JetBrainsMonoNL-Regular.ttf", assets, FontWeight.Normal),
    Font("fonts/JetBrainsMonoNL-Bold.ttf", assets, FontWeight.Bold),
)

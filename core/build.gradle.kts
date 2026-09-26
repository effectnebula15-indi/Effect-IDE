plugins {
    id("eide.android-jvm")
}

dependencies {
    // Границы графем зависят от платформы: движение курсора не может быть
    // посимвольным по code unit'ам (ADR-005).
    api(project(":platform:api"))

    // JSON для LSP. Деревом (`JsonElement`), а не сгенерированными сериализаторами:
    // сообщения JSON-RPC по природе динамические, а плагин компилятора ради них —
    // лишняя сборочная сложность. Своего разборщика не пишем: на ответе с тысячей
    // вариантов автодополнения он был бы и медленнее, и с ошибками.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    // Есть ли pylsp, решает, пройдёт тест с настоящим сервером или будет
    // пропущен. Для Gradle переменная окружения не вход задачи, и без этой
    // строки результат «пропущено» из кэша подтянулся бы и тогда, когда сервер
    // уже поставлен, — зелёный прогон, в котором ничего не проверялось.
    inputs.property("pylsp", providers.environmentVariable("EIDE_PYLSP").orElse(""))
}

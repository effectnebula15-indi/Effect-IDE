// JVM-модуль, чей байткод исполняется ещё и на Android.
//
// `-Xjdk-release=17` в eide.kotlin-jvm от этой беды не защищает: он запрещает
// API новее Java 17, а на Android нет и части того, что в Java 17 есть.
// `ProcessHandle` — Java 9, есть в 17, отсутствует на Android вовсе. Ровно он
// и попал в клиент LSP; сборка пропустила, lint тоже — для JVM-модуля у него нет
// minSdk, проверять не по чему (проверено опытом, в том числе с
// checkDependencies). Упало бы на устройстве у пользователя.
//
// Поэтому байткод главного набора сверяется с сигнатурами Android API 27 —
// нашего minSdk. Тесты не сверяются: они идут только на JVM и вправе звать
// что угодно.

plugins {
    id("eide.kotlin-jvm")
    id("ru.vyarus.animalsniffer")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
val signatureVersion = catalog.findVersion("androidSignature").get().requiredVersion

dependencies {
    "signature"("net.sf.androidscents.signature:android-api-level-27:$signatureVersion@signature")
}

animalsniffer {
    sourceSets = listOf(the<SourceSetContainer>()["main"])
}

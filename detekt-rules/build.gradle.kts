plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure-JVM module: custom detekt rules + their unit tests. No Android SDK needed.
// Consumed by :app via detektPlugins(project(":detekt-rules")).

dependencies {
    compileOnly("io.gitlab.arturbosch.detekt:detekt-api:${libs.versions.detekt.get()}")

    testImplementation("io.gitlab.arturbosch.detekt:detekt-test:${libs.versions.detekt.get()}")
    testImplementation("junit:junit:${libs.versions.junit.get()}")
}

tasks.withType<Test>().configureEach {
    useJUnit()
}

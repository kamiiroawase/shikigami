plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.spotless)
    alias(libs.plugins.ksp)
    application
}

repositories {
    mavenCentral()
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
    format("xml") {
        target("src/**/*.xml")
        trimTrailingWhitespace()
        leadingTabsToSpaces(4)
        endWithNewline()
    }
}

dependencies {
    implementation(platform(libs.openai.client.bom))
    implementation(libs.openai.client)
    implementation(libs.vendeli.telegram.bot)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.slf4j.simple)

    runtimeOnly(libs.ktor.client.okhttp)

    testImplementation(libs.kotlin.test)

    ksp(libs.vendeli.ktnip)
}

application {
    mainClass = "com.github.shikigami.App"
}

kotlin {
    jvmToolchain(21)
}

tasks.named<JavaExec>("run") {
    jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8")
}

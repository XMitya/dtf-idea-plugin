import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Community edition on purpose: makes an accidental Ultimate-only API dependency impossible.
        intellijIdeaCommunity("2025.2.3")
        bundledPlugin("com.intellij.java")    // UAST + Java PSI
        bundledPlugin("org.jetbrains.kotlin") // Kotlin UAST
        pluginVerifier()
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
    }
}

intellijPlatform {
    // No settings UI in this plugin, so skip the IDE round-trip during the build.
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
            // Keep the plugin installable in newer IDEs; the default would pin it to 252.*
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            // The IDE we build against, rather than recommended(): the latter fetches the
            // product-releases listing during configuration and needs another IDE download.
            current()
        }
    }
}

tasks.test {
    useJUnit()
}

/**
 * The verifier runs in a forked JVM and downloads from the Marketplace. Pass through whatever proxy
 * settings this build was started with, so that it works behind a proxy without any host being
 * written into the project. Supply them as usual, e.g.
 * `./gradlew -Dhttps.proxyHost=... -Dhttps.proxyPort=... verifyPlugin`.
 */
tasks.withType<VerifyPluginTask>().configureEach {
    val proxyProperties = listOf(
        "http.proxyHost", "http.proxyPort", "http.nonProxyHosts",
        "https.proxyHost", "https.proxyPort",
    )
    proxyProperties.forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}

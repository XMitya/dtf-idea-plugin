import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

/** Claimed compatibility floor, shared by the plugin descriptor and the repository manifest. */
val pluginSinceBuild = "252"

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
        // YAML PSI, for reading `distributed-task.task-properties-group.task-properties.<NAME>.cron`.
        // Optional at runtime - see the <depends optional> in plugin.xml - but needed here to compile
        // against it and to have the plugin enabled in the test and sandbox IDEs.
        bundledPlugin("org.jetbrains.plugins.yaml")
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

    // Only the Marketplace requires a signature. `signPlugin` runs by itself right before
    // `publishPlugin` and stays silent while the chain and key are absent, so a plain
    // `buildPlugin` — including the one behind a GitHub release — needs no certificate.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        // Personal access token from the Marketplace profile, My Tokens. Shown once.
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = pluginSinceBuild
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

/**
 * Writes the `updatePlugins.xml` that a custom plugin repository serves, so the IDE can offer new
 * versions through its normal update flow instead of someone installing a zip by hand each time.
 *
 * Name, vendor and description are copied out of plugin.xml rather than repeated here: the IDE
 * renders the repository listing from this manifest alone — it downloads the zip only on install —
 * so anything missing here shows up as a plugin with no title and no description.
 *
 *   ./gradlew generateUpdatePluginsXml -PpluginBaseUrl=https://host/path
 */
tasks.register("generateUpdatePluginsXml") {
    val baseUrl = providers.gradleProperty("pluginBaseUrl")
    val pluginVersion = providers.gradleProperty("pluginVersion")
    val archiveBaseName = rootProject.name
    val descriptor = layout.projectDirectory.file("src/main/resources/META-INF/plugin.xml").asFile
    val output = layout.buildDirectory.file("distributions/updatePlugins.xml")
    val since = pluginSinceBuild

    inputs.file(descriptor)
    // The URL and the version are what the manifest is mostly made of, so they have to take part
    // in up-to-date checks: without them a re-run for a new release would be skipped as current.
    inputs.property("pluginBaseUrl", baseUrl.orElse(""))
    inputs.property("pluginVersion", pluginVersion)
    outputs.file(output)

    doLast {
        val url = baseUrl.orNull?.trimEnd('/')
            ?: error("Pass -PpluginBaseUrl=<url of the directory holding the zip>")
        val version = pluginVersion.get()

        val root = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(descriptor)
            .documentElement
        // Direct children only: `id` and `name` also occur deeper in the descriptor.
        fun field(tag: String): String {
            val children = root.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node.nodeName == tag) return node.textContent.trim()
            }
            return ""
        }

        // Built line by line rather than from a raw string: the description is multi-line and
        // unindented, which makes trimIndent() see a common indent of zero and strip nothing.
        fun escape(text: String) = text
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            buildString {
                appendLine("<plugins>")
                appendLine(
                    "  <plugin id=\"${escape(field("id"))}\"" +
                        " url=\"${escape("$url/$archiveBaseName-$version.zip")}\"" +
                        " version=\"${escape(version)}\">",
                )
                appendLine("    <name>${escape(field("name"))}</name>")
                appendLine("    <vendor>${escape(field("vendor"))}</vendor>")
                appendLine("    <idea-version since-build=\"$since\"/>")
                appendLine("    <description><![CDATA[")
                appendLine(field("description"))
                appendLine("    ]]></description>")
                appendLine("  </plugin>")
                appendLine("</plugins>")
            },
        )
        logger.lifecycle("Wrote $file")
    }
}

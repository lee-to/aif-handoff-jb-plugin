plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

group = "com.aifhandoff"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.1.1")
    }
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    signing {
        certificateChainFile = file("chain.crt")
        privateKeyFile = file("private.pem")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginConfiguration {
        id = "com.aifhandoff.plugin"
        name = "AIF Handoff"
        version = project.version.toString()
        description = "Embedded Kanban board for AIF Handoff task management system"
        vendor {
            name = "AIF Handoff"
            url = "https://github.com/lee-to"
            email = "thecutcode@gmail.com"
        }
        ideaVersion {
            sinceBuild = "241"
        }
    }
}

tasks {
    buildSearchableOptions {
        enabled = false
    }
}

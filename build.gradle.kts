plugins {
	kotlin("jvm") version "2.3.10"
	kotlin("plugin.serialization") version "2.3.10"
	id("io.ktor.plugin") version "3.4.1"
}

group = "org.qo"
version = "1.0-SNAPSHOT"

repositories {
	mavenCentral()
}

dependencies {
	testImplementation(kotlin("test"))
	implementation("io.ktor:ktor-server-core")
	implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
	implementation("com.google.code.gson:gson:2.13.2")
	implementation("org.slf4j:slf4j-simple:2.0.17")
	implementation("io.ktor:ktor-server-netty")
	implementation("io.ktor:ktor-server-cors")
	implementation("com.akuleshov7:ktoml-core:0.7.1")
	implementation("com.akuleshov7:ktoml-file:0.7.1")
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.9.0")
	implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:2.3.10")
	implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:2.3.10")
}

kotlin {
	jvmToolchain(22)
}

application {
	mainClass.set("org.qo.MainKt")
}
tasks.test {
	useJUnitPlatform()
}

tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
    from(listOf("LICENSE", "COPYING")) {
        into("META-INF/licenses/orin")
    }
}

val webUiDir = when {
    file("webui/package.json").exists() -> file("webui")
    file("../Orin-WebUI/package.json").exists() -> file("../Orin-WebUI")
    else -> file("webui")
}
val webResourceDir = layout.projectDirectory.dir("src/main/resources/web")

val buildWebUi by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Orin-WebUI React project and syncs output to resources"
    onlyIf {
        webUiDir.exists() && file("${webUiDir}/package.json").exists()
    }
    workingDir = webUiDir
    val isWindows = System.getProperty("os.name").lowercase().contains("windows")
    commandLine = if (isWindows) {
        listOf("cmd", "/c", "npm", "run", "build")
    } else {
        listOf("npm", "run", "build")
    }
    doLast {
        val distDir = file("${webUiDir}/dist")
        if (distDir.exists()) {
            val targetDir = webResourceDir.asFile
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            targetDir.mkdirs()
            copy {
                from(distDir)
                into(targetDir)
            }
        }
    }
}

tasks.named("processResources") {
    dependsOn(buildWebUi)
}

val packageExecutable by tasks.registering {
    group = "distribution"
    description = "Packages a self-executing standalone binary containing both Orin backend and embedded WebUI"
    dependsOn(tasks.named("buildFatJar"))

    val fatJarFile = layout.buildDirectory.file("libs/Orin-all.jar")
    val executableFile = layout.buildDirectory.file("bin/orin")

    inputs.file(fatJarFile)
    outputs.file(executableFile)

    doLast {
        val binDir = executableFile.get().asFile.parentFile
        binDir.mkdirs()

        val stub = """
            #!/bin/sh
            exec java -jar "$0" "$@"

        """.trimIndent().toByteArray(Charsets.UTF_8)

        val jarBytes = fatJarFile.get().asFile.readBytes()
        val targetFile = executableFile.get().asFile
        targetFile.writeBytes(stub + jarBytes)
        targetFile.setExecutable(true, false)
    }
}

tasks.named("assemble") {
    dependsOn(packageExecutable)
}

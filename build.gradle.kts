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
	implementation("com.akuleshov7:ktoml-core:0.7.1")
	implementation("com.akuleshov7:ktoml-file:0.7.1")
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.9.0")
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

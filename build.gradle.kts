plugins {
	kotlin("jvm") version "2.3.10"
}

group = "org.qo"
version = "1.0-SNAPSHOT"

repositories {
	mavenCentral()
}

dependencies {
	testImplementation(kotlin("test"))
}

kotlin {
	jvmToolchain(22)
}

tasks.test {
	useJUnitPlatform()
}
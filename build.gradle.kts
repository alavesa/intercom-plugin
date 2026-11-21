import java.security.MessageDigest

plugins {
    java
}

group = "fi.alavesa"
version = "1.0.0"

// Paper publishes the 26.x API as <mc>.build.<n>-<channel> (there is no -R0.1-SNAPSHOT any more).
// Pinned to an exact stable build so the build is reproducible; bump the build number to move forward.
val paperApi = "io.papermc.paper:paper-api:26.3.build.257-stable"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Adventure/MiniMessage, Brigadier and Gson all come with the Paper API - nothing is shaded, so the plain
    // `jar` task output is the plugin.
    compileOnly(paperApi)
    // The server's own netty, compile-only: same-port pack delivery (pack/SamePortHttp) hooks the listening
    // channel of the Minecraft port. The jars in libs/ are the ones Paper 26.3 ships; nothing is bundled.
    compileOnly(fileTree("libs") { include("netty-*.jar") })

    testImplementation(paperApi)
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    // Minecraft / Paper 26.3 run on Java 25. --release 25 lets any JDK >= 25 build it (no toolchain download).
    options.release.set(25)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed", "skipped") }
}

tasks.jar {
    archiveBaseName.set("Intercom")
}

// The resource pack as a stand-alone zip, for servers that host the pack themselves (resourcepack.delivery: url
// or none): build/pack/Intercom-pack.zip plus Intercom-pack.zip.sha1. It is the pack the plugin builds with the
// default config.yml and the vanilla text shader (no other plugin's HUD hooks merged in). The plugin itself does
// not need this task: it builds the same pack at start-up from the copy inside the jar.
val packZip by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Builds build/pack/Intercom-pack.zip and prints its SHA-1"
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath + sourceSets.main.get().compileClasspath
    mainClass.set("fi.alavesa.intercom.pack.PackCli")
    val resources = layout.projectDirectory.dir("src/main/resources")
    val out = layout.buildDirectory.dir("pack")
    inputs.dir(resources)
    outputs.dir(out)
    args(resources.asFile.absolutePath, out.get().asFile.absolutePath)
    doLast {
        val zip = out.get().file("Intercom-pack.zip").asFile
        val sha1 = MessageDigest.getInstance("SHA-1").digest(zip.readBytes()).joinToString("") { "%02x".format(it) }
        check(sha1 == out.get().file("Intercom-pack.zip.sha1").asFile.readText().trim()) { "sha1 file does not match the zip" }
        println("resource pack: $zip")
        println("sha1:          $sha1")
    }
}

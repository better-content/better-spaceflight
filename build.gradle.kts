import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

plugins {
    id("net.minecraftforge.gradle") version "6.0.54"
}

group = "com.bettercontent"
version = "0.1.0"
base { archivesName.set("better-spaceflight") }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(17)); withSourcesJar() }

repositories {
    mavenCentral()
    maven("https://www.cursemaven.com") { content { includeGroup("curse.maven") } }
    maven("https://maven.valkyrienskies.org") { content { includeGroup("org.valkyrienskies.core") } }
}

val probe by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
}
configurations[probe.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[probe.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())
// Pure probe assertions are unit-tested, but remain excluded from the shipping main JAR.
sourceSets.test.get().compileClasspath += probe.output
sourceSets.test.get().runtimeClasspath += probe.output

// Independent, NONSHIPPING qualification authoring only. Nothing in the
// historical probe/main/test runtime, existing gameTestServer, jar or default
// task graph depends on this source set. No run configuration is registered.
val qualificationSupport by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    runtimeClasspath += sourceSets.main.get().output + sourceSets.main.get().runtimeClasspath
}
configurations[qualificationSupport.implementationConfigurationName]
    .extendsFrom(configurations.implementation.get())
configurations[qualificationSupport.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.runtimeOnly.get())

val attemptId = providers.gradleProperty("attemptId").orElse(UUID.randomUUID().toString()).get()
require(attemptId.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid attemptId" }
val attemptRoot = rootDir.resolve("instances/verification/$attemptId")
val probeGate = providers.gradleProperty("probeGate").orElse("static").get()
require(probeGate in setOf("static", "dynamic")) { "Invalid probeGate: $probeGate" }

minecraft {
    mappings("official", "1.20.1")
    copyIdeResources = true
    runs {
        configureEach {
            property("forge.logging.console.level", "info")
            property("mixin.env.remapRefMap", "true")
            property("mixin.env.refMapRemappingFile", file("build/createSrgToMcp/output.srg").absolutePath)
            mods {
                create("better_spaceflight") { source(sourceSets.main.get()) }
                create("better_spaceflight_probe") { source(probe) }
            }
        }
        create("gameTestServer") {
            workingDirectory(attemptRoot.resolve("native"))
            property("forge.enableGameTest", "true")
            property("forge.gameTestServer", "true")
            property("forge.enabledGameTestNamespaces", "better_spaceflight_probe")
            property("spaceflight.probe.gate", probeGate)
            arg("--nogui")
        }
        create("client") { workingDirectory(attemptRoot.resolve("client")) }
        create("server") { workingDirectory(attemptRoot.resolve("server")); arg("--nogui") }
    }
}

dependencies {
    minecraft("net.minecraftforge:forge:1.20.1-47.4.22")
    implementation(fg.deobf("curse.maven:valkyrien-skies-258371:7906689"))
    implementation("org.valkyrienskies.core:api:1.1.0+cf208d8b56")
    runtimeOnly(fg.deobf("curse.maven:kotlin-for-forge-351264:7291067"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

dependencyLocking { lockAllConfigurations() }

// VS ships these Jackson libraries as nested JarJar dependencies. Gradle does not put
// nested JARs on javac's classpath, so use only the exact VS-provided bytes for compilation.
// They are not bundled into our runtime JAR and no new dependency is downloaded.
val vsJacksonHashes = mapOf(
    "jackson-annotations-2.14.0.jar" to "efaff8693acbae673468d251b5e5ea8fc7ce1b852327bccf1cce72244c2e5f1c",
    "jackson-core-2.14.0.jar" to "ab4793e5df4fbfae445ca55e9e1439311c80fa8b34fc13162c1260902b4dbea0",
    "jackson-databind-2.14.0.jar" to "54377fa855f52ed87e8f689b35249971840b16870dee76806d5d200cbcd66f27",
    "jackson-dataformat-cbor-2.14.0.jar" to "f93c35d8b48ebc8bfe96a9d9e6d7e473f4744eb74819213e1d3af6ba40c940d2",
    "jackson-kotlin-dsl-1.2.0.jar" to "e3edb2ac4424032c7743cb4677ae17f11ff9fb3dfaebe524202d9881538dc3a6",
    "jackson-module-kotlin-2.14.0-rubyfork.jar" to "94cb703fa9da12113cf0af2c6e87e3327d5b22338d5d7199a4f51f3f033477e8",
    "jackson-module-parameter-names-2.14.0.jar" to "9022863dca199b2f30f8f684eeb049af8c60de5d9c8a04122d7b96f62f90681d",
)
val vsJacksonDirectory = layout.buildDirectory.dir("verified-vs-jackson")
val extractVsJackson by tasks.registering {
    outputs.dir(vsJacksonDirectory)
    outputs.upToDateWhen { false } // Reverify nested bytes even when Gradle reuses its cache.
    doLast {
        val vsJar = configurations.compileClasspath.get().resolvedConfiguration.resolvedArtifacts
            .single { it.moduleVersion.id.group == "curse.maven" && it.name == "valkyrien-skies-258371" }.file
        val destination = vsJacksonDirectory.get().asFile.also { it.mkdirs() }
        ZipFile(vsJar).use { archive ->
            vsJacksonHashes.forEach { (filename, expected) ->
                val entry = archive.getEntry("META-INF/jars/$filename")
                    ?: error("Pinned VS artifact lacks $filename")
                val bytes = archive.getInputStream(entry).use { it.readBytes() }
                val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                check(actual == expected) { "VS nested $filename checksum mismatch: $actual" }
                destination.resolve(filename).writeBytes(bytes)
            }
        }
    }
}
val vsJacksonClasspath = files(fileTree(vsJacksonDirectory) { include("*.jar") }).builtBy(extractVsJackson)
sourceSets.main.get().compileClasspath += vsJacksonClasspath
sourceSets.test.get().compileClasspath += vsJacksonClasspath
sourceSets.test.get().runtimeClasspath += vsJacksonClasspath

// The byte-integrity test must inspect the pinned original JAR, never the ForgeGradle
// mapped compile artifact. Resolve it through the existing verified CurseMaven input.
tasks.named<Test>("test") {
    doFirst {
        val originalVs = configurations.detachedConfiguration(
            dependencies.create("curse.maven:valkyrien-skies-258371:7906689"))
            .also { it.isTransitive = false }.singleFile
        val actual = MessageDigest.getInstance("SHA-256").digest(originalVs.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(actual == "f99f24de62015451a047f90484cf9d25970cac350105b581c22b2d5247ebfd53") {
            "Original VS artifact checksum mismatch: $actual"
        }
        systemProperty("spaceflight.vs.originalJar", originalVs.absolutePath)
    }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.withType<Test>().configureEach { useJUnitPlatform() }
tasks.named<Jar>("jar") {
    manifest.attributes("MixinConfigs" to "better_spaceflight.vs2411.mixins.json,better_spaceflight.eureka163.mixins.json")
    finalizedBy("reobfJar")
}
val stageRuntimeJar by tasks.registering(Copy::class) {
    dependsOn("reobfJar")
    from(layout.buildDirectory.file("reobfJar/output.jar"))
    into(layout.buildDirectory.dir("libs"))
    rename { "better-spaceflight-$version.jar" }
}

val provenance = tasks.register("writeBuildProvenance") {
    val output = layout.buildDirectory.dir("generated/provenance")
    outputs.dir(output)
    outputs.upToDateWhen { false }
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
        (fileTree("src/main").files + listOf(file("build.gradle.kts"), file("settings.gradle.kts"),
            file("gradle.properties"), file("gradle/wrapper/gradle-wrapper.properties")))
            .sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }.forEach {
                digest.update(it.relativeTo(rootDir).invariantSeparatorsPath.toByteArray())
                digest.update(0.toByte()); digest.update(it.readBytes()); digest.update(0.toByte())
            }
        val revision = ProcessBuilder("git", "rev-parse", "--verify", "HEAD")
            .directory(rootDir).redirectErrorStream(true).start()
        val sourceHead = revision.inputStream.bufferedReader().use { it.readText().trim() }
        check(revision.waitFor() == 0 && sourceHead.matches(Regex("[0-9a-f]{40}"))) {
            "Production provenance requires a committed source checkout"
        }
        val status = ProcessBuilder("git", "status", "--porcelain", "--untracked-files=all", "--",
            "src/main", "build.gradle.kts", "settings.gradle.kts", "gradle.properties",
            "gradle/wrapper/gradle-wrapper.properties")
            .directory(rootDir).redirectErrorStream(true).start()
        val sourceStatus = status.inputStream.bufferedReader().use { it.readText().trim() }
        check(status.waitFor() == 0 && sourceStatus.isEmpty()) {
            "Production provenance refuses uncommitted runtime inputs: $sourceStatus"
        }
        val properties = output.get().file("META-INF/better-content-source.properties").asFile
        properties.parentFile.mkdirs()
        properties.writeText("repository=better-spaceflight\nmod_id=better_spaceflight\nsource_head=$sourceHead\n" +
            "runtime_inputs_sha256=${digest.digest().joinToString("") { "%02x".format(it) }}\n")
    }
}
sourceSets.main.get().resources.srcDir(layout.buildDirectory.dir("generated/provenance"))
tasks.processResources { dependsOn(provenance) }

// A native fixture is always fresh; explicit manual restarts require our exact instance marker.
// No task writes eula=true or silently adopts another installation/world.
tasks.matching { it.name in listOf("runGameTestServer", "runServer", "runClient") }.configureEach {
    dependsOn(tasks.named(probe.classesTaskName))
    doFirst {
        val child = when (name) { "runGameTestServer" -> "native"; "runServer" -> "server"; else -> "client" }
        val directory = attemptRoot.resolve(child)
        val marker = directory.resolve(".spaceflight-probe-instance")
        val identity = "schema=1\nattempt=$attemptId\nkind=$child\n"
        val restart = providers.gradleProperty("probeRestart").orNull == "true"
        if (restart) {
            check(child != "native" && marker.isFile && marker.readText() == identity) {
                "Restart requires an owned matching manual probe installation: $directory"
            }
        } else {
            check(!marker.exists() && !directory.resolve("world").exists() && !directory.resolve("saves").exists()) {
                "Refusing existing fixture: $directory; use a new ID or explicit owned manual probeRestart"
            }
            directory.mkdirs()
            marker.writeText(identity)
        }
        logger.lifecycle("Retained probe fixture: $directory restart=$restart (no automatic EULA acceptance)")
    }
}
tasks.matching { it.name == "runGameTestServer" }.configureEach {
    doLast {
        val log = attemptRoot.resolve("native/logs/latest.log")
        val expectedMarker = if (probeGate == "static") "SPACEFLIGHT_NATIVE_SUBGATE" else "SPACEFLIGHT_DYNAMIC_SUBGATE"
        check(log.isFile && log.readText().contains("1 GAME TESTS COMPLETE")
            && log.readText().contains("All 1 required tests passed")
            && log.readText().contains(expectedMarker)) {
            "Native $probeGate sub-gate did not report exactly one required authored test and its scope marker: $log"
        }
    }
}
tasks.register("verifyFast") { group = "verification"; dependsOn("test") }

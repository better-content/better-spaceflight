import java.security.MessageDigest
import java.util.UUID

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
        val properties = output.get().file("META-INF/better-content-source.properties").asFile
        properties.parentFile.mkdirs()
        properties.writeText("repository=better-spaceflight\nmod_id=better_spaceflight\nsource_head=UNCOMMITTED\n" +
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

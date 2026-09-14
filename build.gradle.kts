import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.jvm.toolchain.JavaToolchainService

// Needed so each per-target compile task can request its own javac (21 for the 1.x jar, 25 for the
// 26.x one) rather than inheriting the single toolchain declared in the java {} block.
val javaToolchains = extensions.getByType<JavaToolchainService>()

plugins {
    `java-library`
}

group = "me.juancayc"
// version comes from gradle.properties — Gradle reads that file's `version` key into
// project.version automatically, and CI's "Get version" step reads the same key without a
// second Gradle invocation. Keep it in exactly one place.
description = "Custom Totems of Undying: per-type stack sizes, inventory-wide activation, configurable effects."

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        name = "nexo"
        url = uri("https://repo.nexomc.com/releases")
    }
    // MythicMobs is a PAID plugin, but its API artifacts are published here without authentication
    // — no credentials, no token, no `mavenLocal()` workaround. Only the runtime jar is gated.
    maven {
        name = "lumine"
        url = uri("https://mvn.lumine.io/repository/maven-public/")
    }
}

// ---------------------------------------------------------------------------------------------
// Two target eras, one source tree.
//
// Minecraft's 1.x line ended at 1.21.11 and was replaced by calendar versioning (26.1, 26.2, …).
// Every API call this plugin makes was verified byte-identical across both: compiling the same
// sources against paper-api 1.21.11 and against paper-api 26.2 emits identical call-site
// descriptors. So the ONLY thing that differs between the two jars is the `api-version` line in
// paper-plugin.yml — there is no compatibility shim and no duplicated class.
//
// Note the coordinate FORMS differ, not just the numbers. 26.x is not published as a SNAPSHOT:
// `26.2-R0.1-SNAPSHOT` does not exist. Its scheme is `<mcversion>.build.<n>-<status>`.
val paper1211 = "io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT"

// Pinned deliberately rather than `26.2.build.+`. That wildcard is a PREFIX match, so it also spans
// `-alpha` and `-beta` builds; it currently resolves to a stable one only by the accident that
// alphas stopped at build 58 while stables reached 123. A reproducible build is worth an occasional
// manual bump.
val paper262 = "io.papermc.paper:paper-api:26.2.build.123-stable"

dependencies {
    // Compiled against the 1.x coordinate. This is the classpath the IDE and `./gradlew jar` use.
    // The 26.2 jar is produced by a separate compile task (see `compile262` below) that swaps this
    // coordinate — the sources are the same either way.
    compileOnly(paper1211)

    // Nexo is used for ITEMS only (nexo:id → ItemStack), so a server owner can back a totem type
    // with a Nexo-textured item. Glyphs need no class from here: Nexo registers its <glyph:id> tags
    // into Paper's bootstrap tag registry, so the server-global MiniMessage already resolves them.
    // See MiniMessageProvider.
    compileOnly("com.nexomc:nexo:1.28.0")

    // MythicMobs is used for SKILLS only (cast a named skill when a totem saves a player). The
    // artifact is `Mythic-Dist`, not `MythicMobs` — v5 renamed it, and the old coordinate resolves
    // to abandoned v4 builds. See MythicSkillHook, which is the only class that touches it.
    compileOnly("io.lumine:Mythic-Dist:5.13.0")

    // No database: this plugin persists nothing per player. Totem identity travels in the item's
    // own PDC, so there is no loader class, no `loader:` key and no Hikari/JDBC dependency.

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(paper1211)
}

// The 26.2 API jar declares `org.gradle.jvm.version = 25`, so Gradle refuses to hand it to a
// configuration that advertises a Java 21 runtime — it fails resolution outright:
//
//   > ...looking for a library compatible with JVM runtime version 21, but
//     'paper-api:26.2.build.123-stable' is only compatible with JVM runtime version 25 or newer.
//
// This configuration therefore advertises 25. The BYTECODE we emit stays at 21 (see the
// `options.release` block): a Java 25 runtime loads Java 21 class files without complaint, and
// targeting 21 keeps the 26.2 jar loadable on any 1.x server too.
val paper262Classpath: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25)
    }
}

dependencies {
    paper262Classpath(paper262)

    // Nexo WITHOUT its transitive tree. We compile against exactly one Nexo class (NexoItems, in
    // NexoItemHook), so its own dependencies are noise here — and one of them, triumph-gui
    // 3.2.0-SNAPSHOT, is not published to any repository we declare, which fails resolution
    // outright. The default compileOnly configuration never hits this because it is not resolved
    // transitively the same way.
    paper262Classpath("com.nexomc:nexo:1.28.0") { isTransitive = false }

    // MythicMobs WITHOUT its transitive tree, for the same reason as Nexo above. Mythic-Dist is a
    // shaded distribution that still declares a wide dependency tree (adventure, cloud, MythicLib's
    // siblings, various NMS shims); we compile against a handful of its own classes in
    // MythicSkillHook and need none of that. Several of those coordinates are not published to any
    // repository we declare, so pulling them in fails resolution outright rather than merely
    // bloating the classpath.
    paper262Classpath("io.lumine:Mythic-Dist:5.13.0") { isTransitive = false }

    // Declared explicitly because the line above cuts transitives. The sources annotate with
    // @Nullable/@NotNull, which normally arrive transitively through paper-api; with a hand-built
    // configuration they have to be named. Compile-time only — annotations are not retained.
    paper262Classpath("org.jetbrains:annotations:26.0.2")
}

tasks.withType<JavaCompile> {
    // The actual portability guarantee: bytecode targets Java 21 (class file major version 65)
    // even when the toolchain above resolves a newer javac to run the compiler itself. A Java 25
    // runtime loads Java 21 bytecode without complaint.
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events(TestLogEvent.PASSED, TestLogEvent.FAILED, TestLogEvent.SKIPPED)
    }
}

// The default processResources still runs (the IDE and `./gradlew test` expect populated
// resources), and targets the 1.x line so a plain `./gradlew build` produces something loadable.
// The two shipping jars use their own copies below.
tasks.processResources {
    val props = mapOf("version" to project.version.toString(), "apiVersion" to "1.21")
    inputs.properties(props)
    filesMatching("paper-plugin.yml") {
        expand(props)
    }
}

// CI passes BUILD_NUMBER (the run number) so each build produces a distinct, traceable jar name
// that the release step can reference deterministically. Absent locally, so a plain
// `./gradlew allJars` still produces clean PolaroidTotems-1.0.0-mc<target>.jar names.
val buildSuffix = System.getenv("BUILD_NUMBER")?.let { "-b$it" } ?: ""

/**
 * Builds one shipping jar for one Minecraft era.
 *
 * @param taskSuffix  distinguishes the generated task names
 * @param apiVersion  the value substituted into paper-plugin.yml's `api-version`
 * @param classifier  appended to the jar name, e.g. `mc26.2`
 * @param classpath   the paper-api variant to compile against
 */
fun registerTargetJar(
    taskSuffix: String,
    apiVersion: String,
    classifier: String,
    classpath: FileCollection,
    release: Int,
): TaskProvider<Jar> {
    // Each target gets its OWN resources copy, because api-version is the one line that differs
    // between the two jars. Sharing one output directory would make the jars race each other.
    val resources = tasks.register<Copy>("processResources$taskSuffix") {
        from(sourceSets.main.get().resources)
        into(layout.buildDirectory.dir("resources-$classifier"))
        val props = mapOf("version" to project.version.toString(), "apiVersion" to apiVersion)
        inputs.properties(props)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }

    val classes = tasks.register<JavaCompile>("compile$taskSuffix") {
        source = sourceSets.main.get().allJava
        this.classpath = classpath
        destinationDirectory.set(layout.buildDirectory.dir("classes-$classifier"))
        // The bytecode level is per target, and NOT a free choice — it is forced by the API jar.
        //
        // `--release N` bars javac from reading class files newer than N. paper-api 26.2 is
        // compiled to Java 25 (class file major 69), so compiling against it with release 21 fails
        // outright:
        //
        //   bad class file: .../paper-api-26.2.build.123-stable.jar(JavaPlugin.class)
        //     class file has wrong version 69.0, should be 65.0
        //
        // Hence 21 for the 1.x jar and 25 for the 26.x jar. That costs nothing in reach: 26.x
        // servers require a Java 25 runtime anyway, so Java 25 bytecode is already the floor there,
        // and the 1.x jar keeps targeting 21 for servers still on the older runtime.
        options.release.set(release)
        options.encoding = "UTF-8"

        // javac itself must be new enough to EMIT the requested release. The 21 toolchain declared
        // in the java {} block cannot emit 25, so each target pulls a matching compiler.
        javaCompiler.set(javaToolchains.compilerFor {
            languageVersion.set(JavaLanguageVersion.of(release))
        })
    }

    return tasks.register<Jar>("jar$taskSuffix") {
        archiveBaseName.set("PolaroidTotems")
        archiveVersion.set("${project.version}$buildSuffix-$classifier")
        from(classes.map { it.destinationDirectory })
        from(resources)
    }
}

val jar1211 =
    registerTargetJar("1211", "1.21", "mc1.21.11", configurations.compileClasspath.get(), 21)
val jar262 = registerTargetJar("262", "26.2", "mc26.2", paper262Classpath, 25)

tasks.register("allJars") {
    group = "build"
    description = "Builds both shipping jars: one for the 1.21.x line, one for the 26.x line."
    dependsOn(jar1211, jar262)
}

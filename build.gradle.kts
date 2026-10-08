
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

apply(from = "gen-stdlib.gradle.kts")

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.9.2")
}

tasks.test {
    useJUnitPlatform()
    workingDir(projectDir)
    // Main classes use the modern compiler; tests must run on its toolchain too.
    javaLauncher.set(tasks.compileJava.flatMap { it.javaCompiler }.flatMap { compiler ->
        javaToolchains.launcherFor { languageVersion.set(compiler.metadata.languageVersion) }
    })
}

// GTNH's downgrader rewrites test inputs after evaluation. Use the original
// classes on the compiler's JDK so an empty downgrade directory on the first
// invocation cannot turn this suite into a successful NO-SOURCE run.
afterEvaluate {
    tasks.test {
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
    }
}
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    exclude("META-INF/versions/**")
    exclude("META-INF/services/java.nio.file.spi.FileSystemProvider")
    mergeServiceFiles()
}

tasks.compileJava {
    // this is a dummy file to keep eclipse compiler shut, so do not actually compile it
    project.sourceSets {
        main {
            java {
                exclude("cpw/mods/fml/common/patcher/ClassPatchManager.java")
            }
        }
    }
}

// Regenerates src/main/java/net/mpy/vm/StdLibData.java (the frozen Python standard library)
// from stdlib/*.py, compiling each module with the mod's own Java mpy compiler -- no native
// mpy-cross involved. Run after editing or adding a module under stdlib/:
//
//     ./gradlew genStdlib
//
// then rebuild. The generator class itself is net.mpy.compiler.tools.GenStdlib.
tasks.register<JavaExec>("genStdlib") {
    group = "build"
    description = "Regenerate StdLibData.java from stdlib/*.py with the built-in Java compiler"
    classpath = project.extensions.getByType<org.gradle.api.tasks.SourceSetContainer>()["main"].runtimeClasspath
    mainClass.set("net.mpy.compiler.tools.GenStdlib")
    args(
        layout.projectDirectory.dir("stdlib").asFile.path,
        layout.projectDirectory.file("src/main/java/net/mpy/vm/StdLibData.java").asFile.path,
    )
    // The main classes are compiled by GTNHGradle's modern toolchain (and only downgraded
    // to Java 8 inside the jar), so run them on that same toolchain, not the PATH java.
    javaLauncher.set(tasks.named<org.gradle.api.tasks.compile.JavaCompile>("compileJava").flatMap { it.javaCompiler }.flatMap { compiler ->
        project.extensions.getByType<org.gradle.jvm.toolchain.JavaToolchainService>().launcherFor {
            languageVersion.set(compiler.metadata.languageVersion)
        }
    })
}

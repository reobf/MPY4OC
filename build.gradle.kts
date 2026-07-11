
plugins {
    id("com.gtnewhorizons.gtnhconvention")
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

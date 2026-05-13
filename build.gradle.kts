import java.io.File

plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.kapt") version "1.9.24" apply false
    id("com.google.gms.google-services") version "4.4.4" apply false

}

val gramaKhataBuildDir = File(
    System.getProperty("user.home"),
    ".gradle/grama-khata-build"
)

layout.buildDirectory.set(gramaKhataBuildDir.resolve("root"))

subprojects {
    layout.buildDirectory.set(gramaKhataBuildDir.resolve(project.name))
}

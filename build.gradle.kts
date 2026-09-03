import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

val javaTarget = JvmTarget.fromTarget(libs.versions.jvmTarget.get())

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(javaTarget)
        freeCompilerArgs.addAll(
            "-Xno-call-assertions",
            "-Xno-param-assertions",
            "-Xno-receiver-assertions"
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
}

val androidSdkDir = System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")
    ?: "C:/Users/sehga/AppData/Local/Android/Sdk"

dependencies {
    compileOnly(files("$androidSdkDir/platforms/android-36/android.jar"))
    implementation(project(":library"))
    implementation(libs.jsoup)
    implementation(libs.nicehttp)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlinx.serialization.json)
}

val dexOutputDir = layout.buildDirectory.dir("dex")
val cs3OutputDir = layout.buildDirectory.dir("cs3")
val compileClasspath = configurations.named("compileClasspath")

val dexPlugin = tasks.register("dexPlugin") {
    dependsOn("compileKotlin")
    val classesDir = layout.buildDirectory.dir("classes/kotlin/main")
    val outDir = dexOutputDir
    val d8Bat = file("$androidSdkDir/build-tools/36.0.0/d8.bat")
    val androidJar = file("$androidSdkDir/platforms/android-36/android.jar")
    val cpFiles = compileClasspath.map { it.files }

    inputs.dir(classesDir)
    inputs.files(cpFiles)
    outputs.dir(outDir)

    doLast {
        val outDirFile = outDir.get().asFile
        outDirFile.mkdirs()
        val classFiles = fileTree(classesDir.get().asFile) {
            include("**/*.class")
        }.files

        if (classFiles.isEmpty()) {
            throw GradleException("No class files found in ${classesDir.get().asFile}")
        }

        val cpArgs = mutableListOf<String>()
        cpFiles.get().forEach { cpFile ->
            if (cpFile.exists()) {
                cpArgs.add("--classpath")
                cpArgs.add(cpFile.absolutePath)
            }
        }

        val cmd = mutableListOf(
            "cmd.exe", "/c", d8Bat.absolutePath,
            "--min-api", "21",
            "--lib", androidJar.absolutePath,
            "--output", outDirFile.absolutePath
        )
        cmd.addAll(cpArgs)
        cmd.addAll(classFiles.map { it.absolutePath })

        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            println(output)
            throw GradleException("d8 failed with exit code $exitCode")
        }
        println("d8 output: $output")
    }
}

val makeCs3 = tasks.register<Zip>("makeCs3") {
    dependsOn(dexPlugin)
    archiveFileName.set("ECorn.cs3")
    destinationDirectory.set(cs3OutputDir)

    from(dexOutputDir) {
        include("classes.dex")
    }
    from("src/main/resources") {
        include("manifest.json")
    }
}

tasks.register("packagePlugin") {
    dependsOn(makeCs3)
    doLast {
        val cs3File = makeCs3.get().archiveFile.get().asFile
        val targetCs3 = file("C:/SimpleStreamExt/ECorn.cs3")
        cs3File.copyTo(targetCs3, overwrite = true)
        println("Successfully generated CS3: ${targetCs3.absolutePath} (${targetCs3.length()} bytes)")

        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(targetCs3.readBytes())
        val sha256 = digest.joinToString("") { "%02x".format(it) }

        val pluginsJson = """
        [
          {
            "name": "E Corn",
            "pluginClassName": "com.eporner.ECornPlugin",
            "version": 1,
            "url": "ECorn.cs3",
            "hash": "$sha256",
            "filesize": ${targetCs3.length()},
            "types": ["NSFW"],
            "authors": ["SimpleStream"],
            "description": "High performance provider for Eporner with 4K, full category rails, and anti-hotlink protection."
          }
        ]
        """.trimIndent()

        file("C:/SimpleStreamExt/plugins.json").writeText(pluginsJson)
        println("Successfully generated plugins.json with SHA-256: $sha256")
    }
}

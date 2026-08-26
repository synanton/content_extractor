plugins {
    java
}

allprojects {
    group = "com.synanton"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all", "-Xlint:-processing"))
    }
}

tasks.register("buildAll") {
    group = "content-extraction-plane"
    description = "Build every module"
    dependsOn(subprojects.map { it.tasks.named("build") })
}

tasks.register<Exec>("verifyContractMirror") {
    group = "verification"
    description = "Verify the extraction contract matches the platform repository copy"
    commandLine("./scripts/verify-contract-mirror.sh")
    isIgnoreExitValue = false
}

tasks.named("check") {
    dependsOn("verifyContractMirror")
}

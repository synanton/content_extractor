plugins {
    java
}

allprojects {
    group = "com.synanton"
    version = "0.1.0-SNAPSHOT"
    // Override Spring Boot's managed Testcontainers version (defaults to 1.19.8 in Boot 3.3.5)
    extra["testcontainers.version"] = "1.21.4"
}

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
        // org.opendataloader:opendataloader-pdf-core (adapter-document-pdf) pulls in
        // org.verapdf:validation-model / wcag-validation for PDF/UA accessibility checks,
        // which are not published to Maven Central.
        maven {
            name = "vera-dev"
            url = uri("https://artifactory.openpreservation.org/artifactory/vera-dev")
        }
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

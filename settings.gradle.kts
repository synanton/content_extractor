rootProject.name = "content-extractor"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
    }
}

// SCEP-1 delivers the contract module only. The service modules
// (extraction-gateway, extraction-spi, adapter-document-*) land in SCEP-2/3.
include(
    "java:extraction-contract",
)

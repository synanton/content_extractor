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

include(
    "java:extraction-contract",
    "java:extraction-spi",
    "java:extraction-gateway",
    "java:adapter-document-text",
    "java:adapter-document-pdf",
    "java:adapter-stubs",
)

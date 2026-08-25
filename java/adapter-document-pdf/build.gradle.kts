// adapter-document-pdf: OpenDataLoader-backed PDF adapter (SCEP-3).
//
// Calls an OpenDataLoader HTTP service; normalizes its JSON output into NormalizedDocument.
// The OpenDataLoader service URL is configurable; the adapter returns UNSUPPORTED when not set.

plugins {
    java
}

dependencies {
    implementation(project(":java:extraction-spi"))

    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    // Spring annotations used at compile time; runtime provided by gateway
    compileOnly(libs.spring.boot.starter)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit)
    testAnnotationProcessor(libs.lombok)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

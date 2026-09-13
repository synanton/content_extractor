// adapter-document-pdf: OpenDataLoader-backed PDF adapter (SCEP-3/SCEP-6).
//
// Calls the real org.opendataloader:opendataloader-pdf-core library in-process
// (file-in/file-out via OpenDataLoaderPDF.processFile); normalizes its JSON
// output into NormalizedDocument.

plugins {
    java
}

dependencies {
    implementation(project(":java:extraction-spi"))

    implementation(libs.opendataloader.pdf.core)
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

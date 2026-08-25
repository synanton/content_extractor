// adapter-stubs: capability-declining stubs for audio, image, and video modalities.

plugins {
    java
}

dependencies {
    implementation(project(":java:extraction-spi"))
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    // Spring annotations used at compile time; runtime provided by gateway
    compileOnly(libs.spring.boot.starter)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testAnnotationProcessor(libs.lombok)
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

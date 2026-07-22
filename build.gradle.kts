plugins {
    `java-library`
}

group = "dev.mackerel"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
    withSourcesJar()
}

repositories {
    mavenCentral()
}

dependencies {
    // ZERO runtime dependencies by design. Only the JDK is used
    // (java.net.http.HttpClient + a hand-rolled JSON reader/writer).

    // Tests are optional and must never leak into the runtime classpath.
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
}

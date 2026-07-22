plugins {
    `java-library`
    `maven-publish`
}

group = "dev.mackerel"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<Javadoc>().configureEach {
    // Javadoc completeness is not a release gate; don't fail on doclint nits.
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name = "d1-jdbc"
                description =
                    "JDBC driver for Cloudflare D1 — zero-dependency, dual transport (REST API / self-deployed Worker proxy)"
                url = "https://github.com/mack-erel/d1-jdbc"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
                    }
                }
                developers {
                    developer {
                        id = "mack-erel"
                        name = "Henry Jang"
                    }
                }
                scm {
                    url = "https://github.com/mack-erel/d1-jdbc"
                    connection = "scm:git:https://github.com/mack-erel/d1-jdbc.git"
                }
            }
        }
    }
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

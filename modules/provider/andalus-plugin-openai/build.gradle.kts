plugins {
    `java-library`
    `maven-publish`
}

group = "tech.kayys.andalus"
version = "0.0.1"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    // This plugin now uses the same published framework coordinates as the Maven reactor.
    implementation("tech.kayys.andalus:andalus-provider:0.0.1")
    implementation("tech.kayys.andalus:andalus-core:0.0.1")
    implementation("tech.kayys.andalus:andalus-spi:0.0.1")
    implementation("tech.kayys.andalus:andalus-client-schemas:0.0.1")
    implementation("tech.kayys.andalus:andalus-embedding:0.0.1")

    implementation("io.quarkus:quarkus-arc:3.32.2")
    implementation("io.smallrye.reactive:mutiny:2.9.0")
    compileOnly("org.jboss.logging:jboss-logging:3.6.1.Final")
    compileOnly("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("org.mockito:mockito-core:5.14.2")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
    repositories {
        mavenLocal()
    }
}

tasks.jar {
    manifest {
        attributes(
            mapOf(
                "Plugin-Class" to "tech.kayys.aqli.plugin.cloud.openai.OpenAiCloudProvider",
                "Plugin-Id" to "openai-cloud-provider",
                "Plugin-Version" to project.version.toString()
            )
        )
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

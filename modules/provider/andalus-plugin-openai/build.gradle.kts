plugins {
    `java-library`
    `maven-publish`
}

group = "tech.kayys.aqli"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation(project(":spi:aqli-spi-plugin"))
    implementation(project(":spi:aqli-spi"))
    implementation(project(":spi:aqli-spi-inference"))
    implementation("tech.kayys.aljabr:aljabr-error-code:0.1.0-SNAPSHOT")
    implementation("tech.kayys.andalus:andalus-provider:1.0.0-SNAPSHOT")
    implementation("tech.kayys.andalus:andalus-core:1.0.0-SNAPSHOT")
    implementation("tech.kayys.andalus:andalus-spi:1.0.0-SNAPSHOT")
    implementation("tech.kayys.andalus:andalus-client-schemas:1.0.0-SNAPSHOT")
    implementation("tech.kayys.andalus:andalus-embedding:1.0.0-SNAPSHOT")
    implementation(group = "io.quarkus", name = "quarkus-arc")
    implementation(group = "io.smallrye.config", name = "smallrye-config", version = "3.8.0")
    compileOnly(group = "io.smallrye.reactive", name = "mutiny")
    compileOnly(group = "org.jboss.logging", name = "jboss-logging")
    compileOnly(group = "com.fasterxml.jackson.core", name = "jackson-databind")
    testImplementation(group = "org.junit.jupiter", name = "junit-jupiter")
    testImplementation(group = "org.mockito", name = "mockito-core")
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
                "Plugin-Version" to "0.1.0-SNAPSHOT"
            )
        )
    }
}

val installPluginJar by tasks.registering(Copy::class) {
    dependsOn(tasks.jar)
    from(tasks.jar)
    into(file("${System.getProperty("user.home")}/.andalus/plugins"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

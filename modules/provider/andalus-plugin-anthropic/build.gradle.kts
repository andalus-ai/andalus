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
    implementation("tech.kayys.andalus:andalus-provider:1.0.0-SNAPSHOT")
    implementation("tech.kayys.andalus:andalus-core:1.0.0-SNAPSHOT")
    implementation("tech.kayys.andalus:andalus-spi:1.0.0-SNAPSHOT")
    implementation("tech.kayys.andalus:andalus-client-schemas:1.0.0-SNAPSHOT")
    implementation("tech.kayys.andalus:andalus-embedding:1.0.0-SNAPSHOT")
    compileOnly(group = "io.smallrye.reactive", name = "mutiny")
    implementation(group = "com.fasterxml.jackson.core", name = "jackson-databind")
    implementation(group = "com.fasterxml.jackson.datatype", name = "jackson-datatype-jsr310")
    compileOnly(group = "jakarta.validation", name = "jakarta.validation-api", version = "3.0.2")
    compileOnly(group = "jakarta.enterprise", name = "jakarta.enterprise.cdi-api", version = "4.0.1")
    compileOnly(group = "org.jboss.logging", name = "jboss-logging", version = "3.5.3.Final")
    compileOnly(group = "org.jetbrains", name = "annotations", version = "24.1.0")
    testImplementation(group = "org.junit.jupiter", name = "junit-jupiter")
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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes(
            mapOf(
                "Plugin-Class" to "tech.kayys.aqli.provider.anthropic.AnthropicProvider",
                "Plugin-Id" to "anthropic-provider",
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

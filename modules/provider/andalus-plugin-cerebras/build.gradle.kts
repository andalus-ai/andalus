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
    implementation(group = "io.quarkus", name = "quarkus-vertx")
    implementation(group = "io.quarkus", name = "quarkus-rest-jackson")
    implementation(group = "io.smallrye.reactive", name = "mutiny")
    implementation(group = "com.fasterxml.jackson.core", name = "jackson-databind")
    implementation(group = "com.fasterxml.jackson.datatype", name = "jackson-datatype-jsr310")
    implementation(group = "jakarta.validation", name = "jakarta.validation-api")
    compileOnly(group = "org.jetbrains", name = "annotations", version = "24.1.0")
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
                "Plugin-Class" to "tech.kayys.aqli.provider.cerebras.CerebrasProvider",
                "Plugin-Id" to "cerebras-provider",
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

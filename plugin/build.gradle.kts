import java.net.URI

plugins {
    id("org.openstreetmap.josm") version "0.8.2"
    java
}

dependencies {
    implementation(project(":core"))
    // Must be packed: JOSM loads the plugin jar in isolation (no Gradle runtime classpath).
    packIntoJar(project(":core"))
    packIntoJar("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    packIntoJar("org.openstreetmap.osmosis:osmosis-osm-binary:0.48.3")
    packIntoJar("com.google.protobuf:protobuf-java:3.25.5")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

josm {
    pluginName = "nvdb_incline"
    debugPort = 1731
    josmCompileVersion = "19613"
    manifest {
        description =
            "Suggest incline=* from NVDB (layer / bbox / kommune; review-only; never uploads). Menu: Data and More tools."
        mainClass = "org.openstreetmap.josm.plugins.nvdbincline.NvdbInclinePlugin"
        minJosmVersion = "19067"
        author = "josm-elevation contributors"
        canLoadAtRuntime = true
        website = URI("https://github.com/Supermagnum/josm-elevation").toURL()
        minJavaVersion = 17
    }
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    archiveBaseName.set("nvdb_incline")
    // packIntoJar expands the core jar; without this, clean/rebuild can pack a stale/missing artifact.
    dependsOn(":core:jar")
}

/**
 * Convenience copy of the installable plugin jar to the repo-root {@code compiled/}
 * directory (same artifact as {@code plugin/build/dist/nvdb_incline.jar}).
 */
val copyJarToCompiled =
    tasks.register<Copy>("copyJarToCompiled") {
        group = "build"
        description =
            "Copies nvdb_incline.jar to <repo>/compiled/ for easy install (see README)."
        dependsOn("dist")
        from(layout.buildDirectory.dir("dist")) {
            include("nvdb_incline.jar")
        }
        into(rootProject.layout.projectDirectory.dir("compiled"))
    }

tasks.named("dist") {
    finalizedBy(copyJarToCompiled)
}

// Ensure ./gradlew build / :plugin:jar also refresh compiled/ (via dist packaging).
tasks.named("jar") {
    finalizedBy(copyJarToCompiled)
}

tasks.named("build") {
    finalizedBy(copyJarToCompiled)
}

tasks.test {
    systemProperty("java.awt.headless", "true")
}

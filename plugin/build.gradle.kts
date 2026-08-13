import java.net.URI

plugins {
    id("org.openstreetmap.josm") version "0.8.2"
    java
}

dependencies {
    implementation(project(":core"))
    packIntoJar("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

josm {
    pluginName = "nvdb_incline"
    debugPort = 1731
    josmCompileVersion = "19613"
    manifest {
        description = "Suggest incline=* and snow-chain advisory points from NVDB elevation (review-only; never uploads)."
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
}

tasks.test {
    systemProperty("java.awt.headless", "true")
}

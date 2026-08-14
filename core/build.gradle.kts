plugins {
    `java-library`
}

dependencies {
    // PBF schema only (pure JVM; no osmium). Packed into the plugin jar.
    api("org.openstreetmap.osmosis:osmosis-osm-binary:0.48.3")
    api("com.google.protobuf:protobuf-java:3.25.5")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.jar {
    archiveBaseName.set("nvdb-incline-core")
}

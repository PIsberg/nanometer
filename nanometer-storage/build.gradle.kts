plugins {
    `java-library`
}

dependencies {
    api(project(":nanometer-model"))
    api(project(":nanometer-core"))
    api("org.xerial:sqlite-jdbc:3.45.1.0")
}

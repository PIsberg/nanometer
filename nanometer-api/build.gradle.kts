plugins {
    `java-library`
}

dependencies {
    api(project(":nanometer-model"))
    api(project(":nanometer-core"))
    api(project(":nanometer-storage"))
    api(project(":nanometer-agent"))
    api(project(":nanometer-visualizer"))

    testImplementation("se.deversity.async-test-lib:async-test-lib:1.12.0")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

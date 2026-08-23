plugins {
    `java-platform`
}

dependencies {
    constraints {
        api(project(":nanometer-model"))
        api(project(":nanometer-core"))
        api(project(":nanometer-storage"))
        api(project(":nanometer-agent"))
        api(project(":nanometer-visualizer"))
        api(project(":nanometer-api"))
    }
}

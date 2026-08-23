plugins {
    `java-library`
}

dependencies {
    api(project(":nanometer-model"))
    api(project(":nanometer-core"))
    api("net.bytebuddy:byte-buddy:1.14.12")
    api("net.bytebuddy:byte-buddy-agent:1.14.12")
}

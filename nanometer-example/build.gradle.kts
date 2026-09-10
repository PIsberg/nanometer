plugins {
    `java-library`
    application
}

application {
    mainClass.set("com.example.order.OrderApplication")
    applicationDefaultJvmArgs = listOf(
        "-XX:+EnableDynamicAgentLoading",
        "-Djdk.attach.allowAttachSelf=true"
    )
}

dependencies {
    api(project(":nanometer-api"))

    testImplementation("se.deversity.async-test-lib:async-test-lib:1.12.0")
}

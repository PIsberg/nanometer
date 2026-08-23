plugins {
    `java-library`
    application
}

application {
    mainClass.set("com.example.order.OrderApplication")
}

dependencies {
    api(project(":nanometer-api"))

    testImplementation("se.deversity.async-test-lib:async-test-lib:1.9.7")
}

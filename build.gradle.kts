allprojects {
    group = "io.nanometer"
    version = "0.1.0"

    repositories {
        mavenCentral()
        mavenLocal()
    }
}

subprojects {
    if (name == "nanometer-bom") {
        apply(plugin = "java-platform")
        return@subprojects
    }

    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    dependencies {
        val jspecifyVersion = "1.0.0"
        val spotbugsVersion = "4.8.3"
        val vibetagsVersion = "1.2.5"
        val junitVersion = "5.10.2"

        "api"("org.jspecify:jspecify:$jspecifyVersion")
        "compileOnly"("com.github.spotbugs:spotbugs-annotations:$spotbugsVersion")
        "compileOnly"("se.deversity.vibetags:vibetags-annotations:$vibetagsVersion")
        "annotationProcessor"("se.deversity.vibetags:vibetags-processor:$vibetagsVersion")

        "testImplementation"("org.junit.jupiter:junit-jupiter:$junitVersion")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Avibetags.root=${rootProject.projectDir.absolutePath}")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        systemProperty("license.mock.mode", "true")
        jvmArgs(
            "-XX:+EnableDynamicAgentLoading",
            "-Djdk.attach.allowAttachSelf=true"
        )
    }
}

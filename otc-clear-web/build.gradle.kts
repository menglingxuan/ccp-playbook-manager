plugins {
    java
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":otc-clear-core"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.slf4j:slf4j-api")
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName = "otc-clear-testdata.jar"
}

tasks.register<JavaExec>("generateSamples") {
    group = "documentation"
    description = "Regenerates the demo files under samples/"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "ac.tools.SampleWorkbookGenerator"
    args(rootProject.layout.projectDirectory.dir("samples").asFile.absolutePath)
}

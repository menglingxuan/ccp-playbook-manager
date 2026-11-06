plugins {
    java
}

dependencies {
    api(project(":otc-clear-domain"))

    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework:spring-web")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.slf4j:slf4j-api")

    runtimeOnly("com.h2database:h2")
}

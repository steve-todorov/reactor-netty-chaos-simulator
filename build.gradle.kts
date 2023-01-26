plugins {
    id("java")
}

group = "org.example.chaos.simulation"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // logging
    implementation("org.slf4j:slf4j-api:1.7.30")
    implementation("ch.qos.logback:logback-core:1.2.11")
    implementation("ch.qos.logback:logback-classic:1.2.11")
    // /logging

    // project reactor
    compileOnly("io.projectreactor:reactor-tools:3.5.2")
    implementation("io.projectreactor:reactor-core:3.5.2")
    implementation("io.projectreactor.netty:reactor-netty-core:1.1.2")
    implementation("io.projectreactor.netty:reactor-netty-http:1.1.2")
    implementation("io.projectreactor.addons:reactor-extra:3.5.0")
    // /project reactor

    // testing
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
    // - testcontainers to help with simulating network issues via docker
    testImplementation("org.testcontainers:testcontainers:1.17.6")
    testImplementation("org.testcontainers:junit-jupiter:1.17.6")
    testImplementation("org.testcontainers:toxiproxy:1.17.6")
    // - testing reactor
    testImplementation("io.projectreactor:reactor-test:3.5.2")
    testRuntimeOnly("io.projectreactor.tools:blockhound:1.0.7.RELEASE")
    // /testing
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter()
        }
    }
}

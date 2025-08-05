plugins {
    kotlin("jvm") version "2.2.0"
}

group = "kr.pincoin"
version = "1.0.0"

repositories {
    mavenCentral()
}

val keycloakVersion = "26.3.1"

dependencies {
    // Keycloak SPI 의존성 (compileOnly - 런타임에 Keycloak이 제공)
    compileOnly("org.keycloak:keycloak-core:${keycloakVersion}")
    compileOnly("org.keycloak:keycloak-server-spi:${keycloakVersion}")
    compileOnly("org.keycloak:keycloak-services:${keycloakVersion}")

    // 로깅 (SLF4J는 Keycloak이 제공하므로 compileOnly)
    compileOnly("org.slf4j:slf4j-api:2.0.9")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

// JAR 빌드 설정
tasks.jar {
    archiveBaseName.set("keycloak-spi-pincoin")
    archiveVersion.set(project.version.toString())

    // Manifest 설정
    manifest {
        attributes(
            "Implementation-Title" to "Keycloak SPIs for Pincoin",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "Pincoin",
        )
    }

    // 의존성 포함 (implementation 의존성만)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.RSA")
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
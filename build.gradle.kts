plugins {
    kotlin("jvm") version "2.2.0"
}

group = "kr.pincoin"
version = "1.0.0"

repositories {
    mavenCentral()
}

val keycloakVersion = "26.3.1"
val redisVersion = "6.0.0"
val slf4jVersion = "2.0.17"

dependencies {
    // 런타임에 keyclaok이 제공하는 의존성 compileOnly
    compileOnly("org.keycloak:keycloak-core:${keycloakVersion}")
    compileOnly("org.keycloak:keycloak-server-spi:${keycloakVersion}")
    compileOnly("org.keycloak:keycloak-services:${keycloakVersion}")

    // SLF4J - 최신 버전 사용하되 JAR에 포함시키지 않음
    compileOnly("org.slf4j:slf4j-api:${slf4jVersion}")

    // Jedis - SLF4J 의존성은 제외하고 사용
    implementation("redis.clients:jedis:${redisVersion}") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
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
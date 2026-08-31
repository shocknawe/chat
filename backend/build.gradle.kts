plugins {
	id("org.springframework.boot") version "3.4.1"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("jvm") version "2.1.0"
	kotlin("plugin.spring") version "2.1.0"
	kotlin("plugin.jpa") version "2.1.0"
}

group = "com.example.chat"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	// Spring Web (MVC)
	implementation("org.springframework.boot:spring-boot-starter-web")
	// Spring WebSocket (application-owned realtime layer, no STOMP broker)
	implementation("org.springframework.boot:spring-boot-starter-websocket")
	// Spring Security (X-User-Id / handshake identity binding)
	implementation("org.springframework.boot:spring-boot-starter-security")
	// Spring Data JPA
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	// Validation (Bean Validation on request payloads)
	implementation("org.springframework.boot:spring-boot-starter-validation")
	// Actuator (health/readiness for docker compose healthcheck + ops)
	implementation("org.springframework.boot:spring-boot-starter-actuator")

	// Jackson Kotlin module for idiomatic (de)serialization of sealed types/data classes
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

	// Kotlin
	implementation("org.jetbrains.kotlin:kotlin-reflect")

	// PostgreSQL driver
	runtimeOnly("org.postgresql:postgresql")

	// Testing
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.security:spring-security-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.junit.jupiter:junit-jupiter")
	testImplementation("org.testcontainers:junit-jupiter:1.20.4")
	testImplementation("org.testcontainers:postgresql:1.20.4")
	// Validates docs/openapi.yaml parses as a well-formed OpenAPI document
	// (OpenApiSpecValidationTest) — the contract-first workflow's one
	// automated guardrail against a hand-edited spec silently breaking.
	testImplementation("io.swagger.parser.v3:swagger-parser:2.1.47")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
	// docs/openapi.yaml lives outside this module (repo-root docs/), so
	// Gradle's up-to-date check would not otherwise notice it changed and
	// would skip re-running OpenApiSpecValidationTest — declare it as an
	// explicit input so editing the spec always invalidates the cache.
	inputs.file(layout.projectDirectory.file("../docs/openapi.yaml"))
		.withPropertyName("openApiSpec")
		.withPathSensitivity(PathSensitivity.RELATIVE)
}

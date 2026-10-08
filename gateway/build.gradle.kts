plugins {
    `java-library`
    alias(libs.plugins.dependency.check)
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

val mongoConnectorModl = rootProject.file("module-init/MongoDB Connector-module.modl")

val extractMongoConnectorApi by tasks.registering(Sync::class) {
    description = "Extracts the MongoDB Connector module's API jars (+ the bson jar its signatures use)."
    from(zipTree(mongoConnectorModl)) {
        include("mongodb-common-*.jar", "mongodb-gateway-*.jar", "bson-[0-9]*.jar")
    }
    into(layout.buildDirectory.dir("mongo-connector-api"))
}

tasks.named("compileJava") { dependsOn(extractMongoConnectorApi) }

dependencies {
    implementation(projects.common)

    // The two front-end bundles are jars of mounted/ resources; modlImplementation puts them in the .modl.
    modlImplementation(projects.web)
    modlImplementation(projects.webUi)

    // Bundled third-party runtime dep (ends up in the .modl next to the gateway jar).
    modlImplementation(libs.whisper.jni)

    // MongoDB access goes through Inductive Automation's MongoDB Connector module (a declared module
    // dependency), so its API jars are compile-only and never bundled. They are not on a Maven repo, so
    // they are extracted from the connector .modl kept in module-init/.
    compileOnly(fileTree(extractMongoConnectorApi.map { it.destinationDir }) { include("*.jar") })

    // Provided by the gateway at runtime.
    compileOnly(libs.ignition.common)
    compileOnly(libs.ignition.gateway.api)
    compileOnly(libs.ignition.perspective.common)
    compileOnly(libs.ignition.perspective.gateway)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.ignition.common)
    testImplementation(libs.ignition.gateway.api)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// OWASP dependency-check over everything this subproject puts in the .modl (whisper-jni + its embedded
// native libraries; SDK and MongoDB Connector jars are compileOnly and are not shipped by us).
dependencyCheck {
    failBuildOnCVSS = 7.0f
    formats = listOf("HTML", "JSON")
    suppressionFile = rootProject.file("config/dependency-check-suppressions.xml").path
    scanConfigurations = listOf("runtimeClasspath")
    // dependency-check 13 requires an NVD API key (free: https://nvd.nist.gov/developers/request-an-api-key).
    System.getenv("NVD_API_KEY")?.takeIf { it.isNotBlank() }?.let { nvd.apiKey = it }
    analyzers.assemblyEnabled = false
    analyzers.nodeAudit.enabled = false
}

tasks.named("dependencyCheckAnalyze") {
    doFirst {
        if (System.getenv("NVD_API_KEY").isNullOrBlank()) {
            throw GradleException(
                "OWASP dependency-check needs an NVD API key. Request one (free) at " +
                    "https://nvd.nist.gov/developers/request-an-api-key and export NVD_API_KEY.")
        }
    }
}

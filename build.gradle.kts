plugins {
    base
    // Ignition module packaging: https://github.com/inductiveautomation/ignition-module-tools
    alias(libs.plugins.ia.modl)
    // OWASP dependency-check: applied in :gateway, whose runtime classpath is what the .modl bundles
    alias(libs.plugins.dependency.check) apply false
}

// --- Module signing: credentials come from sign.props (gitignored), never from this file ---
// key.file / cert.file are resolved relative to sign.props, so it may point at keys kept in another project.
// No sign.props -> unsigned dev build (the test gateway allows unsigned modules).
val signPropsFile = file(providers.gradleProperty("signProps").getOrElse("sign.props"))
val signingConfigured = signPropsFile.exists()
if (signingConfigured) {
    val signProps = java.util.Properties()
    signPropsFile.inputStream().use { signProps.load(it) }
    fun path(key: String) = signPropsFile.parentFile.resolve(signProps.getProperty(key)).canonicalPath
    // The IA plugin reads exactly these property names.
    extra.set("ignition.signing.keystoreFile", path("key.file"))
    extra.set("ignition.signing.keystorePassword", signProps.getProperty("key.pass"))
    extra.set("ignition.signing.certFile", path("cert.file"))
    extra.set("ignition.signing.certAlias", signProps.getProperty("cert.alias"))
    extra.set("ignition.signing.certPassword", signProps.getProperty("cert.pass"))
}

allprojects {
    version = "1.0.0"
    group = "com.wargoetz.voicetranscription"

    // Sources are UTF-8 (LanguageDetector has non-ASCII stop-words). Without this, javac on Windows uses
    // windows-1252 and silently corrupts string literals.
    tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
    tasks.withType<Test>().configureEach { systemProperty("file.encoding", "UTF-8") }
}

ignitionModule {
    name.set("OfflineVoiceTranscription")
    fileName.set("OfflineVoiceTranscription")
    id.set("com.wargoetz.voicetranscription")
    moduleVersion.set("${project.version}")
    moduleDescription.set("Locally transcribes voice via Whisper JNI and persists to MongoDB.")
    // Floor, not target: the oldest 8.3 whose API surface we use. We compile against this same version
    // (gradle/libs.versions.toml -> ignition) so a newer-only API fails the build instead of the gateway.
    requiredIgnitionVersion.set("8.3.0")
    freeModule.set(true)

    // Gradle subproject -> Ignition scope. There is no "P" scope: Perspective runs inside the Gateway (G);
    // the browser only renders. The component's JS ships from the gateway jar (:web) and the Designer
    // needs the component descriptor (:common) to put it on the palette.
    projectScopes.putAll(
        mapOf(
            ":common" to "GD",
            ":gateway" to "G",
            ":web" to "G",
            ":web-ui" to "G",
            ":designer" to "D",
        )
    )

    hooks.putAll(
        mapOf(
            "com.wargoetz.voicetranscription.gateway.GatewayHook" to "G",
            "com.wargoetz.voicetranscription.designer.DesignerHook" to "D",
        )
    )

    // 8.3+ dependency specs: the gateway faults this module (rather than half-loading it) if a dependency
    // is absent. Declaring the MongoDB Connector also puts its classes (GatewayMongoFunctions) on our
    // module's classpath at runtime - that is how we reuse its connections instead of opening our own.
    moduleDependencySpecs {
        register("com.inductiveautomation.perspective") {
            scope = "GD"
            required = true
        }
        register("com.inductiveautomation.connectors.mongodb") {
            scope = "G"
            required = true
        }
    }

    // Signed when sign.props exists (see top of file); otherwise an unsigned dev build.
    skipModlSigning.set(!signingConfigured)
}

val deepClean by tasks.registering {
    dependsOn(allprojects.map { "${it.path}:clean" })
    description = "Executes clean tasks and removes node_modules."
    doLast {
        delete(file("web/node_modules"), file("web-ui/node_modules"))
    }
}

// One entry point for Step 7 of the build spec: npm audit (both web packages) + OWASP dependency-check over
// every jar bundled in the .modl. Needs network; set NVD_API_KEY for a fast NVD sync.
val securityAudit by tasks.registering {
    group = "verification"
    description = "Runs npm audit (high+) on both web packages and the OWASP dependency-check (CVSS >= 7)."
    dependsOn(":web:npmAudit", ":web-ui:npmAudit", ":gateway:dependencyCheckAnalyze")
}

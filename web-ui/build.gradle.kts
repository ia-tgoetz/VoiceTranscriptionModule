// Gateway config page (8.3 React web UI). webpack writes a UMD bundle into
// build/generated-resources/mounted/web-ui/, which the GatewayHook registers as a SystemJsModule
// served from /res/voicetranscription/web-ui/.
plugins {
    java
}

val isWindows = System.getProperty("os.name").lowercase().contains("windows")
fun npm(vararg args: String): List<String> =
    if (isWindows) listOf("cmd", "/c", "npm") + args else listOf("npm") + args

val generated = layout.buildDirectory.dir("generated-resources")

val npmInstall by tasks.registering(Exec::class) {
    group = "Ignition Module"
    description = "npm ci for the gateway config page package."
    workingDir = projectDir
    commandLine(npm("ci", "--no-audit", "--no-fund"))
    inputs.files("package.json", "package-lock.json")
    outputs.dir("node_modules")
}

val webpack by tasks.registering(Exec::class) {
    group = "Ignition Module"
    description = "Builds the gateway config page bundle with webpack."
    dependsOn(npmInstall)
    workingDir = projectDir
    commandLine(npm("run", "build"))
    inputs.dir("src")
    inputs.files("package.json", "package-lock.json", "webpack.config.js", "tsconfig.json")
    outputs.dir(generated)
}

val npmAudit by tasks.registering(Exec::class) {
    group = "verification"
    description = "Fails on high/critical advisories in the config page's npm dependencies."
    dependsOn(npmInstall)
    workingDir = projectDir
    commandLine(npm("audit", "--audit-level=high"))
}

sourceSets {
    main {
        output.dir(mapOf("builtBy" to webpack), generated)
    }
}

tasks.named("clean") { doLast { delete(generated) } }

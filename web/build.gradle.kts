// Perspective client component bundle. webpack writes VoiceTranscription.js/.css into
// build/generated-resources/mounted/, which becomes part of this project's jar and is served by the
// gateway at /res/voicetranscription/*.
plugins {
    java
}

val isWindows = System.getProperty("os.name").lowercase().contains("windows")
fun npm(vararg args: String): List<String> =
    if (isWindows) listOf("cmd", "/c", "npm") + args else listOf("npm") + args

val generated = layout.buildDirectory.dir("generated-resources")

val npmInstall by tasks.registering(Exec::class) {
    group = "Ignition Module"
    description = "npm ci for the Perspective component package."
    workingDir = projectDir
    commandLine(npm("ci", "--no-audit", "--no-fund"))
    inputs.files("package.json", "package-lock.json")
    outputs.dir("node_modules")
}

val webpack by tasks.registering(Exec::class) {
    group = "Ignition Module"
    description = "Builds the Perspective component bundle with webpack."
    dependsOn(npmInstall)
    workingDir = projectDir
    commandLine(npm("run", "build"))
    inputs.dir("src")
    inputs.files("package.json", "package-lock.json", "webpack.config.js", "tsconfig.json")
    outputs.dir(generated)
}

val npmAudit by tasks.registering(Exec::class) {
    group = "verification"
    description = "Fails on high/critical advisories in the Perspective component's npm dependencies."
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

plugins {
    `java-library`
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

dependencies {
    // compileOnly == Maven "provided": the gateway/designer supply these at runtime, never bundle them.
    compileOnly(libs.ignition.common)
    compileOnly(libs.ignition.perspective.common)
}

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven { url = uri("https://nexus.inductiveautomation.com/repository/public/") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        maven { url = uri("https://nexus.inductiveautomation.com/repository/public/") }
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "offline-voice-transcription"

// common   - module id, Perspective component descriptor + props schema (Gateway + Designer)
// gateway  - GatewayHook, REST routes, Whisper/Mongo/LLM services
// designer - DesignerHook, registers the component on the Perspective palette
// web      - Perspective client component (React/TS, webpack) -> mounted JS/CSS
// web-ui   - Gateway config page (React/TS, webpack) -> mounted SystemJS module
include(":common", ":gateway", ":designer", ":web", ":web-ui")

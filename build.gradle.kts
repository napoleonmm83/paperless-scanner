plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
}

// Version management tasks using Exec to be configuration-cache compatible
tasks.register<Exec>("versionBumpMajor") {
    group = "versioning"
    description = "Bump major version (X.0.0)"
    workingDir = projectDir
    commandLine("sh", "-c", """
        FILE="version.properties"
        MAJOR=${'$'}(grep VERSION_MAJOR ${'$'}FILE | cut -d'=' -f2)
        NEW_MAJOR=${'$'}((MAJOR + 1))
        sed -i '' "s/VERSION_MAJOR=.*/VERSION_MAJOR=${'$'}NEW_MAJOR/" ${'$'}FILE
        sed -i '' "s/VERSION_MINOR=.*/VERSION_MINOR=0/" ${'$'}FILE
        sed -i '' "s/VERSION_PATCH=.*/VERSION_PATCH=0/" ${'$'}FILE
        echo "Version bumped to ${'$'}NEW_MAJOR.0.0"
    """.trimIndent())
}

tasks.register<Exec>("versionBumpMinor") {
    group = "versioning"
    description = "Bump minor version (x.X.0)"
    workingDir = projectDir
    commandLine("sh", "-c", """
        FILE="version.properties"
        MAJOR=${'$'}(grep VERSION_MAJOR ${'$'}FILE | cut -d'=' -f2)
        MINOR=${'$'}(grep VERSION_MINOR ${'$'}FILE | cut -d'=' -f2)
        NEW_MINOR=${'$'}((MINOR + 1))
        sed -i '' "s/VERSION_MINOR=.*/VERSION_MINOR=${'$'}NEW_MINOR/" ${'$'}FILE
        sed -i '' "s/VERSION_PATCH=.*/VERSION_PATCH=0/" ${'$'}FILE
        echo "Version bumped to ${'$'}MAJOR.${'$'}NEW_MINOR.0"
    """.trimIndent())
}

tasks.register<Exec>("versionBumpPatch") {
    group = "versioning"
    description = "Bump patch version (x.x.X)"
    workingDir = projectDir
    commandLine("sh", "-c", """
        FILE="version.properties"
        MAJOR=${'$'}(grep VERSION_MAJOR ${'$'}FILE | cut -d'=' -f2)
        MINOR=${'$'}(grep VERSION_MINOR ${'$'}FILE | cut -d'=' -f2)
        PATCH=${'$'}(grep VERSION_PATCH ${'$'}FILE | cut -d'=' -f2)
        NEW_PATCH=${'$'}((PATCH + 1))
        sed -i '' "s/VERSION_PATCH=.*/VERSION_PATCH=${'$'}NEW_PATCH/" ${'$'}FILE
        echo "Version bumped to ${'$'}MAJOR.${'$'}MINOR.${'$'}NEW_PATCH"
    """.trimIndent())
}

tasks.register<Exec>("versionPrint") {
    group = "versioning"
    description = "Print current version"
    workingDir = projectDir
    commandLine("sh", "-c", """
        FILE="version.properties"
        MAJOR=${'$'}(grep VERSION_MAJOR ${'$'}FILE | cut -d'=' -f2)
        MINOR=${'$'}(grep VERSION_MINOR ${'$'}FILE | cut -d'=' -f2)
        PATCH=${'$'}(grep VERSION_PATCH ${'$'}FILE | cut -d'=' -f2)
        CODE=${'$'}((MAJOR * 10000 + MINOR * 100 + PATCH))
        echo "Version: ${'$'}MAJOR.${'$'}MINOR.${'$'}PATCH (code: ${'$'}CODE)"
    """.trimIndent())
}

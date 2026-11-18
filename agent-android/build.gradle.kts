// Root build script. Plugins are declared here `apply false` so that the version
// catalog pins one version fleet-wide; each module opts in via its own build script.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt)
}

// One root-level detekt run over every module's sources (one JVM, one report) instead of a
// per-module task. No type resolution: plain `detekt` is fast and needs no compiled classpath.
// Pre-existing findings live in the baseline; new code must be clean. Regenerate the baseline only
// when deliberately accepting findings: ./gradlew detektBaseline
detekt {
    buildUponDefaultConfig = true
    parallel = true
    baseline = file("config/detekt/baseline.xml")
    source.setFrom(files(subprojects.map { "${it.projectDir}/src" }))
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        xml.required.set(false)
        txt.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
}
tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
    jvmTarget = "17"
}

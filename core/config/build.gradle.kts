plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(project(":core:parser"))
}

val parserFixtures = rootProject.layout.projectDirectory.dir("core/parser/src/test/resources/fixtures")

// `-PupdateGolden` rewrites src/test/resources/golden instead of comparing against it.
val updateGolden = providers.gradleProperty("updateGolden").isPresent
tasks.test {
    systemProperty("golden.dir", layout.projectDirectory.dir("src/test/resources/golden").asFile.absolutePath)
    systemProperty("golden.update", updateGolden.toString())
    systemProperty("parser.fixtures", parserFixtures.asFile.absolutePath)
    inputs.dir(parserFixtures).withPropertyName("parserFixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    if (updateGolden) outputs.upToDateWhen { false }
}

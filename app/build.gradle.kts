import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.legacy.kapt)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

fun secret(name: String): String? =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }

fun explicitTag(): String? {
    providers.gradleProperty("gitaraTag").orNull?.takeIf { it.isNotBlank() }?.let { return it }
    if (providers.environmentVariable("GITHUB_REF_TYPE").orNull == "tag") {
        return providers.environmentVariable("GITHUB_REF_NAME").orNull?.takeIf { it.isNotBlank() }
    }
    return null
}

fun describedTag(): String? = try {
    providers.exec {
        commandLine("git", "describe", "--tags", "--exact-match")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }
} catch (e: Exception) {
    null
}

fun parseVersion(tag: String): Pair<String, Int>? {
    val match = Regex("^v?(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(?:-([0-9A-Za-z.-]+))?$").matchEntire(tag.trim())
        ?: return null
    val major = match.groupValues[1].toInt()
    val minor = match.groupValues[2].toInt()
    val patch = match.groupValues[3].toInt()
    val suffix = match.groupValues[4]
    if (major > 209) throw GradleException("The major version cannot exceed 209 (Android versionCode limit).")
    val stage = if (suffix.isEmpty()) {
        9
    } else {
        (Regex("(\\d+)$").find(suffix)?.groupValues?.get(1)?.toIntOrNull() ?: 0).coerceAtMost(8)
    }
    val code = (major * 1_000_000 + minor * 1_000 + patch) * 10 + stage
    return tag.trim().removePrefix("v") to code
}

fun appVersion(): Pair<String, Int> {
    val explicit = explicitTag()
    if (explicit != null) {
        return parseVersion(explicit) ?: throw GradleException(
            "The tag '$explicit' is not a valid version. Use vMAJOR.MINOR.PATCH or vMAJOR.MINOR.PATCH-suffix, for example v1.2.3 or v1.2.3-rc.1."
        )
    }
    return describedTag()?.let { parseVersion(it) } ?: ("0.0.0-dev" to 1)
}

val (appVersionName, appVersionCode) = appVersion()

val signingStore = secret("GITARA_KEYSTORE")
val signingStorePassword = secret("GITARA_KEYSTORE_PASSWORD")
val signingAlias = secret("GITARA_KEY_ALIAS")
val signingKeyPassword = secret("GITARA_KEY_PASSWORD") ?: signingStorePassword
val canSign = signingStore != null && signingStorePassword != null && signingAlias != null

val prootCommit = "01f83b8841358450c78333d1b33ab30d4943bec4"
val prootSha256 = "9629eb30cdf86e95c6ba681f8ab89c6fdaa9eca093d5577163513c99af5ca281"
val prootArchive = layout.buildDirectory.file("proot/proot-android-aarch64.tar.gz")

fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val downloadProot by tasks.registering {
    outputs.file(prootArchive)
    doLast {
        val file = prootArchive.get().asFile
        file.parentFile.mkdirs()
        if (!file.isFile || sha256Of(file) != prootSha256) {
            val url = "https://raw.githubusercontent.com/green-green-avk/build-proot-android/$prootCommit/packages/proot-android-aarch64.tar.gz"
            uri(url).toURL().openStream().use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val actual = sha256Of(file)
            check(actual == prootSha256) { "proot archive checksum mismatch: $actual" }
        }
    }
}

abstract class ProotLibsTask : Sync() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

androidComponents.onVariants { variant ->
    val taskName = "prepareProotLibs" + variant.name.replaceFirstChar { it.uppercase() }
    val prepare = tasks.register<ProotLibsTask>(taskName) {
        dependsOn(downloadProot)
        into(outputDir.dir("arm64-v8a"))
        from(tarTree(resources.gzip(prootArchive))) {
            include("root/bin/proot")
            eachFile { relativePath = RelativePath(true, "libproot.so") }
        }
        from(tarTree(resources.gzip(prootArchive))) {
            include("root/libexec/proot/loader")
            eachFile { relativePath = RelativePath(true, "libproot_loader.so") }
        }
        includeEmptyDirs = false
    }
    variant.sources.jniLibs?.addGeneratedSourceDirectory(prepare, ProotLibsTask::outputDir)
}

android {
    namespace = "me.sheimi.sgit"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    defaultConfig {
        applicationId = "io.gitara"
        minSdk = 26
        targetSdk = 36

        vectorDrawables.useSupportLibrary = true

        versionCode = appVersionCode
        versionName = appVersionName

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        dataBinding = true
        buildConfig = true
        compose = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = file(signingStore!!)
                storePassword = signingStorePassword
                keyAlias = signingAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.jcraft" && requested.name == "jsch") {
            useTarget("com.github.mwiede:jsch:0.2.0")
        }
    }
    exclude(group = "org.apache.httpcomponents", module = "httpclient")
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.vectordrawable)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.google.material)

    implementation(libs.androidx.lifecycle.extensions)
    kapt(libs.androidx.lifecycle.compiler)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.snakeyaml.engine)

    implementation(libs.timber)
    implementation(libs.jsch)
    implementation(libs.commons.io)
    implementation(libs.jgit)
    implementation(libs.universal.image.loader)
    implementation(libs.secure.preferences)
    implementation(libs.conscrypt)
    implementation(libs.bouncycastle)

    implementation(libs.acra.mail)
    implementation(libs.acra.dialog)

    debugImplementation(libs.stetho.core)
    debugImplementation(libs.stetho.timber)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric.core)
    testImplementation(libs.robolectric.shadows.support)
}
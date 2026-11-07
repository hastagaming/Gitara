import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

val prootCommit = "01f83b8841358450c78333d1b33ab30d4943bec4"
val prootSha256 = "9629eb30cdf86e95c6ba681f8ab89c6fdaa9eca093d5577163513c99af5ca281"
val prootArchive = layout.buildDirectory.file("proot/proot-android-aarch64.tar.gz")
val prootLibsDir = layout.buildDirectory.dir("generated/prootLibs")

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

val prepareProotLibs by tasks.registering(Sync::class) {
    dependsOn(downloadProot)
    into(prootLibsDir.map { it.dir("arm64-v8a") })
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

        versionCode = 1
        versionName = "1.0.0"

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
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(prootLibsDir)
        }
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
        create("release") {
            if (project.hasProperty("special")) {
                keyAlias = project.property("alias") as String
                keyPassword = project.property("password") as String
                storeFile = file(project.property("keystore") as String)
                storePassword = project.property("password") as String
            } else {
                keyAlias = ""
                keyPassword = ""
                storeFile = file("/empty")
                storePassword = ""
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

tasks.named("preBuild") {
    dependsOn(prepareProotLibs)
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
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

android {
    namespace = "me.sheimi.sgit"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    defaultConfig {
        applicationId = "io.gitara"
        minSdk = 21
        targetSdk = 36

        vectorDrawables.useSupportLibrary = true

        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        dataBinding = true
        buildConfig = true
        compose = true
    }

    lint {
        abortOnError = false
    }

    packaging {
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
    implementation(libs.androidx.activity.compose)

    implementation(libs.timber)
    implementation(libs.jsch)
    implementation(libs.commons.io)
    implementation(libs.jgit)
    implementation(libs.universal.image.loader)
    implementation(libs.secure.preferences)
    implementation(libs.conscrypt)
    implementation(libs.bouncycastle)

    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.acra.mail)
    implementation(libs.acra.dialog)

    debugImplementation(libs.stetho.core)
    debugImplementation(libs.stetho.timber)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric.core)
    testImplementation(libs.robolectric.shadows.support)
}
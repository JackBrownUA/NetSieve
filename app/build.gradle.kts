import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ndkVer = "27.2.12479018"
val hevJniDir = layout.projectDirectory.dir("src/main/jni")
val hevBuildRoot = layout.buildDirectory.dir("hev")

android {
    namespace = "io.github.jackbrownua.netsieve"
    compileSdk = 35
    ndkVersion = ndkVer

    defaultConfig {
        applicationId = "io.github.jackbrownua.netsieve"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.4.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }

        externalNativeBuild {
            cmake {
                cFlags += listOf("-O2", "-fvisibility=hidden")
                arguments += listOf(
                    "-DANDROID_STL=none",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(layout.buildDirectory.dir("hev/libs"))
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

val buildHevSocks5Tunnel by tasks.registering(Exec::class) {
    val ndkHomeEnv = System.getenv("ANDROID_NDK_HOME")
    val ndkBuild = if (!ndkHomeEnv.isNullOrBlank()) {
        File(ndkHomeEnv, "ndk-build")
    } else {
        File(android.sdkDirectory, "ndk/$ndkVer/ndk-build")
    }
    val jniDir = hevJniDir.asFile
    val outRoot = hevBuildRoot.get().asFile

    inputs.dir(jniDir)
    outputs.dir(File(outRoot, "libs"))

    doFirst {
        if (!File(jniDir, "hev-socks5-tunnel/Android.mk").exists()) {
            throw GradleException(
                "Не найден src/main/jni/hev-socks5-tunnel/Android.mk. " +
                    "Добавьте сабмодуль или запустите сборку через CI."
            )
        }
        if (!ndkBuild.exists()) {
            throw GradleException("ndk-build не найден: $ndkBuild")
        }
        outRoot.mkdirs()
    }

    commandLine(
        ndkBuild,
        "-j4",
        "NDK_PROJECT_PATH=${outRoot.absolutePath}",
        "APP_BUILD_SCRIPT=${File(jniDir, "Android.mk").absolutePath}",
        "NDK_APPLICATION_MK=${File(jniDir, "Application.mk").absolutePath}",
        "NDK_LIBS_OUT=${File(outRoot, "libs").absolutePath}",
        "NDK_OUT=${File(outRoot, "obj").absolutePath}"
    )
}

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) {
        dependsOn(buildHevSocks5Tunnel)
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
//    id("kotlin-kapt")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.mindtype.ai.keyboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mindtype.ai.keyboard"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters.addAll(setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
        aidl = true // Needed for multi-process IPC
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

//dependencies {
//    // Android Core & Lifecycle
//    implementation("androidx.core:core-ktx:1.12.0")
//    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
//
//    // Jetpack Compose (UI)
//    implementation("androidx.activity:activity-compose:1.8.2")
//    implementation(platform("androidx.compose:compose-bom:2023.08.00"))
//    implementation("androidx.compose.ui:ui")
//    implementation("androidx.compose.ui:ui-graphics")
//    implementation("androidx.compose.ui:ui-tooling-preview")
//    implementation("androidx.compose.material3:material3")
//    implementation("androidx.compose.material:material-icons-extended")
//
//    // Coroutines
//    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
//
//    // Network Engine (Ktor Client for API calls)
//    implementation("io.ktor:ktor-client-android:2.3.7")
//    implementation("io.ktor:ktor-client-cio:2.3.7")
//    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
//    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
//
//    // Security & Encrypted Storage
//    implementation("androidx.security:security-crypto:1.1.0-alpha06")
//    implementation("androidx.datastore:datastore-preferences:1.0.0")
//
//    // Room Database (Clipboard History & Custom Stickers)
//    implementation("androidx.room:room-runtime:2.6.1")
//    implementation("androidx.room:room-ktx:2.6.1")
//
//    // Testing
//    testImplementation("junit:junit:4.13.2")
//    androidTestImplementation("androidx.test.ext:junit:1.1.5")
//    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
//    androidTestImplementation(platform("androidx.compose:compose-bom:2023.08.00"))
//    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
//    debugImplementation("androidx.compose.ui:ui-tooling")
//    debugImplementation("androidx.compose.ui:ui-test-manifest")
//
//    dependencies {
//        // 1. Material Components library (Fixes theme/resource linking errors)
//        implementation("com.google.android.material:material:1.11.0")
//
//        // 2. SavedState & ViewModel integration (Fixes red imports in CleverKeyboardService)
//        implementation("androidx.savedstate:savedstate-ktx:1.2.1")
//        implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
//        implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.7.0")
//
//        // Android Core & Lifecycle
//        implementation("androidx.core:core-ktx:1.12.0")
//        implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
//
//        // Jetpack Compose
//        implementation("androidx.activity:activity-compose:1.8.2")
//        implementation(platform("androidx.compose:compose-bom:2023.08.00"))
//        implementation("androidx.compose.ui:ui")
//        implementation("androidx.compose.ui:ui-graphics")
//        implementation("androidx.compose.ui:ui-tooling-preview")
//        implementation("androidx.compose.material3:material3")
//        implementation("androidx.compose.material:material-icons-extended")
//
//        // Coroutines
//        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
//
//        // Network Engine
//        implementation("io.ktor:ktor-client-android:2.3.7")
//        implementation("io.ktor:ktor-client-cio:2.3.7")
//        implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
//        implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
//
//        // Security & Encrypted Storage
//        implementation("androidx.security:security-crypto:1.1.0-alpha06")
//        implementation("androidx.datastore:datastore-preferences:1.0.0")
//
//        // Room Database
//        implementation("androidx.room:room-runtime:2.6.1")
//        implementation("androidx.room:room-ktx:2.6.1")
//    }
//}
dependencies {
    // 1. Material Components library (Fixes theme/resource linking errors)
    implementation("com.google.android.material:material:1.11.0")

    // 2. SavedState & ViewModel integration (Fixes red imports in CleverKeyboardService)
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.7.0")

    // Android Core & Lifecycle
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Jetpack Compose
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2023.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Network Engine
    implementation("io.ktor:ktor-client-android:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")

    // Security & Encrypted Storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
}
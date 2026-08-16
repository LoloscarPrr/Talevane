plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val stableKeystorePath = System.getenv("TALEVANE_KEYSTORE_PATH")
val hasStableKeystore = !stableKeystorePath.isNullOrBlank() && file(stableKeystorePath).exists()

android {
    namespace = "app.talevane.reader"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.talevane.reader"
        minSdk = 26
        targetSdk = 35
        versionCode = 37
        versionName = "0.7.8"
    }

    if (hasStableKeystore) {
        signingConfigs {
            create("stable") {
                storeFile = file(stableKeystorePath!!)
                storePassword = "TalevaneStable2026!"
                keyAlias = "talevane"
                keyPassword = "TalevaneStable2026!"
            }
        }
        buildTypes {
            getByName("debug") {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures { compose = true }
    packaging { resources.excludes += setOf("META-INF/DEPENDENCIES","META-INF/LICENSE","META-INF/LICENSE.txt","META-INF/NOTICE","META-INF/NOTICE.txt") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.media:media:1.7.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation("junit:junit:4.13.2")
}

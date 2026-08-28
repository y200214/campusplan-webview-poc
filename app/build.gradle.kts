plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "jp.naramed.campusplanpoc"
    compileSdk = 35

    defaultConfig {
        applicationId = "jp.naramed.campusplanpoc"
        // minSdk 26 (Android 8.0):
        //  - WebView の SafeBrowsing / SSL 制御など必要な API が揃う下限
        //  - 将来の Dedicated Device / Lock Task Mode も 26 以降が現実的
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-poc-phase1"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // debug ビルドだけ別 applicationId にして、本番想定ビルドと同居できるようにする
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
        // BuildConfig.DEBUG を WebView デバッグ制御に使うため有効化
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // AndroidX WebKit:
    //  - WebView 実装バージョンの取得（不具合切り分け用）
    //  - Phase 2 以降で addJavascriptInterface の代替として WebMessageListener を使う際に必要
    implementation(libs.androidx.webkit)

    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.ui.tooling)
}

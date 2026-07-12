plugins {
    id("com.android.library")
}

android {
    namespace = "org.opencv"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        disable += "MissingPermission"
    }

    buildFeatures {
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("java/src")
            res.srcDirs("java/res")
            jniLibs.srcDirs("native/libs")
            manifest.srcFile("java/AndroidManifest.xml")
        }
    }
}

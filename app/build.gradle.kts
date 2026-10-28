import java.util.Properties

plugins {
    id("com.android.application")
}

// Release key lives outside the repo (../keys/keystore.properties or $RELEASE_KEYS); without it release builds
// are signed with the debug key, which is fine for local builds.
val keyProps = Properties().apply {
    val f = file(System.getenv("RELEASE_KEYS") ?: "${rootDir}/../keys/keystore.properties")
    if (f.exists()) f.reader(Charsets.UTF_8).use { load(it) }   // UTF-8: the path may be non-ASCII
}

android {
    namespace = "dev.novacain.hive"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.novacain.hive"
        minSdk = 36          // made for CMF Phone 2 Pro (galaga), Nothing OS 4.1 / Android 16
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    signingConfigs {
        if (keyProps.isNotEmpty()) create("release") {
            storeFile = file(keyProps.getProperty("storeFile"))
            storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias")
            keyPassword = keyProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    lint { checkReleaseBuilds = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

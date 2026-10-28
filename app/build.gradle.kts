plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

import java.util.Properties

// Release builds are signed with the same key as the debug builds already on the phone, so they
// install as updates and keep all data. The key lives in signing/ (git-ignored); lose it and the
// app can never be updated in place again, so keep a copy somewhere safe.
val signingProps = Properties().apply {
    rootProject.file("signing/keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// Every commit is a new, higher version, so each build is a valid update of the last.
val commitCount: Int = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
    .standardOutput.asText.get().trim().toIntOrNull() ?: 1

android {
    namespace = "app.diurna"
    compileSdk = 36

    defaultConfig {
        // Kept from the app's first name, Daybook: changing it would make Android treat it as a
        // different app, with none of this one's data. The code package (namespace) is app.diurna.
        applicationId = "app.daybook"
        minSdk = 29
        targetSdk = 36
        versionCode = commitCount
        versionName = "0.$commitCount"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (signingProps.isNotEmpty()) {
                storeFile = rootProject.file("signing/" + signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Shrinking makes the app much smaller and faster; proguard-rules.pro keeps the parts
            // that are only reached by reflection.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
            // Only the Pixel's CPU type; debug builds keep all of them for the x86 emulator.
            ndk { abiFilters += listOf("arm64-v8a") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        // The Anthropic SDK's HTTP dependencies each ship the same license/metadata files.
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST")
    }
    sourceSets {
        // Exported Room schemas double as fixtures for migration tests.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
        // AI prompt eval harness (JVM): shares the unit-test classpath, not shipped in the APK.
        getByName("test") {
            java.srcDir("src/aiEval/java")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.zxing.core)
    implementation(libs.anthropic.java)
    // Raises the SDK's Jackson (it parses every AI reply) to a release with the security fixes.
    implementation(platform(libs.jackson.bom))
    implementation(libs.androidx.work.runtime.ktx)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    // Android's built-in org.json is a stub in JVM unit tests.
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}


// Live AI prompt evals. Reuses estimators/parsers from main; runs on the unit-test classpath.
//   ./gradlew aiEval -Pprovider=grok|claude|github-models|all -Pset=default|full|smoke
afterEvaluate {
    tasks.named<Test>("testDebugUnitTest") {
        // Case JSON lives at repo-root ai-evals/; the harness resolves from here.
        workingDir = rootProject.projectDir
    }

    tasks.register<JavaExec>("aiEval") {
        group = "verification"
        description = "Run AI prompt evals against Grok, Claude, and/or GitHub Models"
        // AGP wires these only after evaluate; compile + process puts AiEvalMain on the test classpath.
        dependsOn(
            "compileDebugUnitTestKotlin",
            "processDebugUnitTestJavaRes",
            "bundleDebugClassesToRuntimeJar",
        )
        classpath = tasks.named<Test>("testDebugUnitTest").get().classpath
        mainClass.set("app.diurna.eval.AiEvalMainKt")
        workingDir = rootProject.projectDir
        systemProperty("root", rootProject.projectDir.absolutePath)
        val providerProp = providers.gradleProperty("provider").orElse("all")
        val setProp = providers.gradleProperty("set").orElse("default")
        val tagProp = providers.gradleProperty("tag")
        val grokModelProp = providers.gradleProperty("grokModel")
        val claudeModelProp = providers.gradleProperty("claudeModel")
        val githubModelProp = providers.gradleProperty("githubModel")
        val outDirProp = providers.gradleProperty("outDir").orElse("ai-evals/out")
        doFirst {
            systemProperty("provider", providerProp.get())
            systemProperty("set", setProp.get())
            systemProperty("outDir", outDirProp.get())
            if (tagProp.isPresent) systemProperty("tag", tagProp.get())
            if (grokModelProp.isPresent) systemProperty("grokModel", grokModelProp.get())
            if (claudeModelProp.isPresent) systemProperty("claudeModel", claudeModelProp.get())
            if (githubModelProp.isPresent) systemProperty("githubModel", githubModelProp.get())
            listOf("XAI_API_KEY", "ANTHROPIC_API_KEY", "GITHUB_TOKEN", "GH_TOKEN").forEach { name ->
                val v = System.getenv(name)
                if (!v.isNullOrBlank()) environment(name, v)
            }
        }
    }
}

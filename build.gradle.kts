plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.maven.publish)
    // alias(libs.plugins.jreleaser)
}

android {
    namespace = "com.qytech.audioplayer"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters.addAll(arrayOf("arm64-v8a"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(project(":core"))
    compileOnly(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.timber)
    implementation(libs.kotlin.stdlib)
    implementation(libs.androidx.core.ktx)
    implementation(libs.gson)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.commons.codec)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // exoplayer
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.smoothstreaming)
    implementation(libs.androidx.media3.exoplayer.rtsp)
    implementation(libs.androidx.media3.exoplayer.ima)
    implementation(libs.juniversalchardet)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}


afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "io.github.qytech"
                artifactId = "audioplayer"
                version = "0.0.7"

                // 用于发布 Android 的 release 组件
                // from(components["release"])
                afterEvaluate {
                    artifact(tasks.getByName("bundleReleaseAar"))
                }
                pom {
                    name.set("audioplayer")
                    description.set("audioplayer")
                    url.set("https://github.com/qytech/audioplayer")
                    inceptionYear.set("2021")

                    licenses {
                        license {
                            name.set("Apache-2.0")
                            url.set("https://spdx.org/licenses/Apache-2.0.html")
                        }
                    }

                    developers {
                        developer {
                            id.set("qytech")
                            name.set("qytech")
                        }
                    }

                    scm {
                        connection.set("scm:git:https://github.com/qytech/audioplayer.git")
                        developerConnection.set("scm:git:ssh://github.com/qytech/audioplayer.git")
                        url.set("http://github.com/qytech/audioplayer")
                    }
                }

                pom.withXml {
                    asNode().appendNode("dependencies").apply {
                        configurations.implementation.get().dependencies.forEach {
                            val dep = appendNode("dependency")
                            dep.appendNode("groupId", it.group)
                            dep.appendNode("artifactId", it.name)
                            dep.appendNode("version", it.version)
                        }
                    }
                }
            }
        }

        repositories {
            maven {
                url = layout.buildDirectory.dir("staging-deploy").get().asFile.toURI()
            }
            mavenLocal()
        }
    }
}

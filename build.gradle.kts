plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.kotlin.parcelize)
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

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    compileOnly(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(libs.qytech.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.timber)
    implementation(libs.kotlin.stdlib)
    implementation(libs.androidx.core.ktx)
    implementation(libs.gson)
    implementation(libs.commons.codec)
    implementation(libs.hilt.android)
    implementation(libs.icu4j)
    implementation(libs.okhttp3)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.datasource.okhttp)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.extractor)
}


afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "io.github.qytech"
                artifactId = "audioplayer"
                version = "0.5.4"

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


                    withXml {
                        val dependenciesNode = asNode().appendNode("dependencies")
                        configurations.implementation.get().allDependencies.forEach {
                            if (it.group != null && it.version != null) {
                                val dependencyNode = dependenciesNode.appendNode("dependency")
                                dependencyNode.appendNode("groupId", it.group)
                                dependencyNode.appendNode("artifactId", it.name)
                                dependencyNode.appendNode("version", it.version)
                                dependencyNode.appendNode("scope", "runtime")
                            }
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

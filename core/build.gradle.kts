import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins { id("org.jetbrains.kotlin.jvm"); id("info.solidsoft.pitest") }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
dependencies { testImplementation("junit:junit:4.13.2") }
tasks.test { testLogging { events("passed", "failed", "skipped"); showStandardStreams = false } }
pitest {
    pitestVersion.set("1.17.0")
    targetClasses.set(listOf("com.withu.core.*"))
    targetTests.set(listOf("com.withu.core.*"))
    threads.set(4)
    outputFormats.set(listOf("XML", "HTML"))
    timestampedReports.set(false)
}

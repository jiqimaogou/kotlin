
plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    compile(kotlinStdlib())
    compileOnly(project(":kotlin-reflect-api"))
    compileOnly(project(":idea:kotlin-gradle-tooling"))
    compile(project(":compiler:util"))
    compile(project(":compiler:cli-common"))
    compile(project(":compiler:frontend.java"))
    compile(project(":js:js.frontend"))
    compile(project(":native:frontend.native"))
    compileOnly(intellijDep())
    compileOnly(jpsStandalone()) { includeJars("jps-model") }
}

sourceSets {
    "main" { projectDefault() }
    "test" {}
}

runtimeJar()

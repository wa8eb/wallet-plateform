rootProject.name = "wallet-stress"
gradle.beforeProject {
    extensions.extraProperties["reportsDir"] = layout.buildDirectory.dir("reports").get().asFile
}

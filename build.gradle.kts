// bug in IntelliJ in which libs shows up as not being accessible
// see https://youtrack.jetbrains.com/issue/KTIJ-19369
@Suppress("DSL_SCOPE_VIOLATION")

subprojects {
    val group = "webapps.electionguard"
    val version = "0.0.1"

    val kotlin_version: String by extra("1.7.20")
    val ktor_version: String by extra("2.1.3")
    val logback_version: String by extra("1.3.4")
}
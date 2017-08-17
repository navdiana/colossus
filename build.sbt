import sbt._
import Keys._
import com.lightbend.paradox.sbt.ParadoxPlugin
import com.lightbend.paradox.sbt.ParadoxPlugin.autoImport._

val AKKA_VERSION      = "2.5.4"
val SCALATEST_VERSION = "3.0.1"

lazy val testAll = TaskKey[Unit]("test-all")

val GeneralSettings = Seq[Setting[_]](
  compile := (compile in Compile).dependsOn(compile in Test).dependsOn(compile in IntegrationTest).value,
  testAll := (test in Test).dependsOn(test in IntegrationTest).value,
  organization := "com.tumblr",
  scalaVersion := "2.12.2",
  crossScalaVersions := Seq("2.11.8", "2.12.2"),
  parallelExecution in Test := false,
  scalacOptions := scalaVersion.map { v: String =>
    val default = List(
      "-feature",
      "-language:implicitConversions",
      "-language:postfixOps",
      "-unchecked",
      "-deprecation",
      "-target:jvm-1.8"
    )
    if (v.startsWith("2.10.")) {
      default
    } else {
      "-Ywarn-unused-import" :: default
    }
  }.value,
  javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint"),
  scalacOptions in (Compile, console) := Seq(),
  libraryDependencies ++= Seq(
    "com.typesafe.akka"      %% "akka-actor"                  % AKKA_VERSION,
    "com.typesafe.akka"      %% "akka-testkit"                % AKKA_VERSION,
    "org.scalatest"          %% "scalatest"                   % SCALATEST_VERSION % "test, it",
    "org.scalamock"          %% "scalamock-scalatest-support" % "3.6.0" % "test",
    "org.mockito"            % "mockito-all"                  % "1.9.5" % "test",
    "com.github.nscala-time" %% "nscala-time"                 % "2.16.0"
  ),
  coverageExcludedPackages := "colossus\\.examples\\..*;.*\\.testkit\\.*"
) ++ Defaults.itSettings

lazy val publishSettings: Seq[Setting[_]] = Seq(
  publishMavenStyle := true,
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value) {
      Some("snapshots" at nexus + "content/repositories/snapshots")
    } else {
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
    }
  },
  publishArtifact in Test := false,
  pomIncludeRepository := Function.const(false),
  //credentials could also be just embedded in ~/.sbt/0.13/sonatype.sbt
  (for {
    username <- Option(System.getenv("SONATYPE_USERNAME"))
    password <- Option(System.getenv("SONATYPE_PASSWORD"))
  } yield credentials += Credentials("Sonatype Nexus Repository Manager", "oss.sonatype.org", username, password))
    .getOrElse(credentials += Credentials(Path.userHome / ".sonatype_credentials")),
  Option(System.getenv("PGP_PASSPHRASE")).fold(
    pgpPassphrase := None
  )(passPhrase => pgpPassphrase := Some(passPhrase.toCharArray)),
  pgpSecretRing := file("secring.gpg"),
  pgpPublicRing := file("pubring.gpg"),
  licenses := Seq("Apache License, Version 2.0" -> new URL("http://www.apache.org/licenses/LICENSE-2.0.html")),
  homepage := Some(url("https://github.com/tumblr/colossus")),
  scmInfo := Some(ScmInfo(url("https://github.com/tumblr/colossus"), "scm:git:git@github.com/tumblr/colossus.git")),
  developers := List(
    Developer(id = "danSimon", name = "", email = "", url = url("http://macrodan.tumblr.com"))
  )
)

val ColossusSettings = GeneralSettings ++ publishSettings

val noPubSettings = GeneralSettings ++ Seq(
  publishArtifact := false,
  publishTo := Some(Resolver.file("Unused transient repository", file("target/unusedrepo")))
)

val testkitDependencies = libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % SCALATEST_VERSION
)

val MetricSettings = ColossusSettings

val ExamplesSettings = Seq(
  libraryDependencies ++= Seq(
    "org.json4s" %% "json4s-jackson" % "3.5.3"
  )
)

lazy val RootProject = Project(id = "root", base = file("."))
  .settings(noPubSettings: _*)
  .configs(IntegrationTest)
  .dependsOn(ColossusProject)
  .aggregate(ColossusProject, ColossusTestkitProject, ColossusMetricsProject, ColossusExamplesProject, ColossusDocs)

lazy val ColossusProject: Project = Project(id = "colossus", base = file("colossus"))
  .settings(ColossusSettings: _*)
  .configs(IntegrationTest)
  .aggregate(ColossusTestsProject)
  .dependsOn(ColossusMetricsProject)

lazy val ColossusExamplesProject = Project(id = "colossus-examples", base = file("colossus-examples"))
  .settings(noPubSettings: _*)
  .configs(IntegrationTest)
  .settings(ExamplesSettings: _*)
  .dependsOn(ColossusProject)

lazy val ColossusMetricsProject = Project(id = "colossus-metrics", base = file("colossus-metrics"))
  .settings(MetricSettings: _*)
  .configs(IntegrationTest)

lazy val ColossusTestkitProject = Project(id = "colossus-testkit", base = file("colossus-testkit"))
  .settings(ColossusSettings: _*)
  .settings(testkitDependencies)
  .configs(IntegrationTest)
  .dependsOn(ColossusProject)

lazy val ColossusDocs = Project(id = "colossus-docs", base = file("colossus-docs"))
  .settings(ColossusSettings: _*)
  .enablePlugins(ParadoxPlugin)
  .settings(
    paradoxTheme := Some(builtinParadoxTheme("generic"))
  )
  .configs(IntegrationTest)
  .dependsOn(ColossusProject, ColossusTestkitProject)

lazy val ColossusTestsProject = Project(
  id = "colossus-tests",
  base = file("colossus-tests"),
  dependencies = Seq(ColossusTestkitProject % "compile;test->test")
).settings(noPubSettings: _*).configs(IntegrationTest)

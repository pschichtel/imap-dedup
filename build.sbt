val scala3Version = "3.2.2"

lazy val root = project
  .in(file("."))
  .settings(
    name := "imap-dedup",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,
    libraryDependencies ++= Seq(
      "org.apache.commons" % "commons-email" % "1.5",
      "org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.4",
      "org.scalameta" %% "munit" % "1.0.3" % Test
    )
  )

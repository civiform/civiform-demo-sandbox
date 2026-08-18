lazy val jacksonVersion = "2.14.3"

lazy val root = (project in file("."))
  .enablePlugins(PlayJava)
  .settings(
    name := """cf-sandbox-builder""",
    version := "0.0.1",
    scalaVersion := "2.13.16",
    maintainer := "civiform-dev@google.com",
    javacOptions ++= Seq(
      "-encoding", "UTF-8",
      "-parameters",
      "-Xlint:unchecked",
      "-Xlint:deprecation"
    ),
    libraryDependencies ++= Seq(
      guice,
      javaJdbc,
      javaWs,

      // Collections & Utilities
      "com.google.guava" % "guava" % "33.4.0-jre",
      "org.apache.commons" % "commons-lang3" % "3.17.0",

      // JSON & Serialization (aligned with Play 3.0.x / Pekko Jackson)
      "com.fasterxml.jackson.datatype" % "jackson-datatype-guava" % jacksonVersion,
      "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % jacksonVersion,
      "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % jacksonVersion,

      // Templating (Thymeleaf)
      "org.thymeleaf" % "thymeleaf" % "3.1.3.RELEASE",
      "org.unbescape" % "unbescape" % "1.1.6.RELEASE",

      // Database
      "org.postgresql" % "postgresql" % "42.7.5",

      // Code Generation (Lombok)
      "org.projectlombok" % "lombok" % "1.18.36" % "provided",

      // Testing
      "org.assertj" % "assertj-core" % "3.27.3" % Test,
      "org.mockito" % "mockito-core" % "5.15.2" % Test,

      // Docker (Sprint 1 — Docker socket runtime)
      "com.github.docker-java" % "docker-java-core" % "3.4.0",
      "com.github.docker-java" % "docker-java-transport-httpclient5" % "3.4.0"
    )
  )

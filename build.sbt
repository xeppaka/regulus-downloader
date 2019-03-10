version := "1.0.0"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-http" % "10.1.7",
  "com.typesafe.akka" %% "akka-stream" % "2.5.21",
  "org.scala-lang.modules" %% "scala-xml" % "1.1.1"
)

mainClass in assembly := Some("eu.xeppaka.regulus.Downloader")
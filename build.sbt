name := "mt-scala-loci-examples"

version := "0.0.0"

scalaVersion := "2.13.2"

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-Xlint",
  "-Ymacro-annotations",
  "-Xmacro-settings:loci.macro.verbose",
  "-language:implicitConversions"
)

enablePlugins(PackPlugin)
packGenerateWindowsBatFile := false
packMain := PackMain.paths

commands += Command.single("compileOnly") { (state, path) =>
  s"""set sources in Compile := (sources in Compile).value.filter(_.getPath.startsWith("$path"))""" :: "compile" :: state
}

lazy val lociLang = ProjectRef(uri("https://github.com/LukasL97/scala-loci.git#master"), "lociLangJVM")
lazy val lociSerializerUpickle = ProjectRef(uri("https://github.com/LukasL97/scala-loci.git#master"), "lociSerializerUpickleJVM")
lazy val lociCommunicatorTcpJVM = ProjectRef(uri("https://github.com/LukasL97/scala-loci.git#master"), "lociCommunicatorTcpJVM")
lazy val lociLangTransmitterRescalaJVM = ProjectRef(uri("https://github.com/LukasL97/scala-loci.git#master"), "lociLangTransmitterRescalaJVM")

lazy val root = project
  .in(file("."))
  .dependsOn(lociLang, lociSerializerUpickle, lociCommunicatorTcpJVM, lociLangTransmitterRescalaJVM)


name := "mt-scala-loci-examples"

version := "0.0.0"

scalaVersion := "2.13.2"

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-unchecked",
  "-Xlint",
  "-Ymacro-annotations",
  "-Xmacro-settings:loci.macro.codepath_.code/,loci.macro.verbose",
  "-language:implicitConversions"
)

enablePlugins(PackPlugin)
packGenerateWindowsBatFile := false
packMain := PackMain.paths

commands += Command.single("compileOnly") { (state, path) =>
  s"""set sources in Compile := (sources in Compile).value.filter(_.getPath == "$path")""" :: "compile" :: state
}

resolvers += ("STG old bintray repo" at "http://www.st.informatik.tu-darmstadt.de/maven/").withAllowInsecureProtocol(true)

val localLociVersion = "0.4.0-196-g1102106-SNAPSHOT"

libraryDependencies ++= Seq(
  "de.tuda.stg" %% "scala-loci-lang" % localLociVersion,
  "de.tuda.stg" %% "scala-loci-serializer-upickle" % localLociVersion,
  "de.tuda.stg" %% "scala-loci-communicator-tcp" % localLociVersion,
  "de.tuda.stg" %% "scala-loci-lang-transmitter-rescala" % localLociVersion,
  "de.tuda.stg" %% "scala-loci-serializer-jsoniter-scala" % localLociVersion
)

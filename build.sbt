name := "mt-scala-loci-examples"

version := "0.0.0"

scalaVersion := "2.13.2"

scalacOptions ++= Seq("-feature", "-deprecation", "-unchecked", "-Xlint", "-Ymacro-annotations", "-Xmacro-settings:loci.macro.codepath_.code/")

enablePlugins(PackPlugin)
packGenerateWindowsBatFile := false
packMain := PackMain.paths

resolvers += ("STG old bintray repo" at "http://www.st.informatik.tu-darmstadt.de/maven/").withAllowInsecureProtocol(true)

val localLociVersion = "0.4.0-70-gd9f5c1e"

libraryDependencies ++= Seq(
  "de.tuda.stg" %% "scala-loci-lang" % localLociVersion,
  "de.tuda.stg" %% "scala-loci-serializer-upickle" % localLociVersion,
  "de.tuda.stg" %% "scala-loci-communicator-tcp" % localLociVersion,
  "de.tuda.stg" %% "scala-loci-lang-transmitter-rescala" % localLociVersion
)

import ScalaJSHelper._
import org.scalajs.sbtplugin.JSModuleID

lazy val orgSettings = Seq(
  organization := "org.scastie",
  version := "0.1.0-SNAPSHOT"
)

val crossPublishLocalRuntime = "crossPublishLocalRuntime"

commands in Global += Command.command(crossPublishLocalRuntime) { state =>
  List(
    "project runtimeScalaJVM",
    "+ publishLocal",
    "project runtimeScalaJS",
    "+ publishLocal",
    "reload",
    "project webApiJVM",
    "+ publishLocal",
    "project webApiJS",
    "+ publishLocal",
    "reload", // sbt crossVersion changes scalaVersion but does not set it back
    "project sbtScastie",
    "publishLocal"
  ) ::: state
}

addCommandAlias("docker", s";$crossPublishLocalRuntime; sbtRunner/docker")

lazy val baseSettings = Seq(
    libraryDependencies += "com.lihaoyi" %% "utest" % "0.4.5" % "test",
    testFrameworks += new TestFramework("utest.runner.Framework"),
    scalaVersion := "2.11.8",
    scalacOptions := {
    val extraOptions =
      if (scalaBinaryVersion.value != "2.10") {
        Seq("-Ywarn-unused-import")
      } else Seq()
    Seq(
      "-deprecation",
      "-encoding",
      "UTF-8",
      "-feature",
      "-unchecked",
      "-Xfatal-warnings",
      "-Xlint",
      "-Yno-adapted-args",
      "-Ywarn-dead-code",
      "-Ywarn-numeric-widen",
      "-Ywarn-value-discard"
    ) ++ extraOptions
  },
    console := (console in Test).value,
    scalacOptions in (Test, console) -= "-Ywarn-unused-import",
    scalacOptions in (Compile, consoleQuick) -= "-Ywarn-unused-import",
    allDependencies ~= logging
  ) ++ orgSettings

def logging(allDependencies: Seq[ModuleID]): Seq[ModuleID] = {
  Seq(
    "org.slf4j" % "slf4j-api"    % "1.7.6",
    "org.slf4j" % "jul-to-slf4j" % "1.7.6",
    "ch.qos.logback" % "logback-core"     % "1.1.1" % Runtime,
    "ch.qos.logback" % "logback-classic"  % "1.1.1" % Runtime,
    "org.slf4j"      % "jcl-over-slf4j"   % "1.7.6" % Runtime,
    "org.slf4j"      % "log4j-over-slf4j" % "1.7.6" % Runtime
  ) ++
    allDependencies.map(
      _.exclude("commons-logging", "commons-logging")
        .exclude("log4j", "log4j")
        .exclude("org.slf4j", "slf4j-log4j12")
        .exclude("org.slf4j", "slf4j-jcl")
        .exclude("org.slf4j", "slf4j-jdk14")
    )
}

def akka(module: String) = "com.typesafe.akka" %% ("akka-" + module) % "2.3.11"

val upickleVersion = "0.4.4"

lazy val remoteApi = project
  .in(file("remote-api"))
  .settings(baseSettings)
  .settings(libraryDependencies += akka("actor"))
  .disablePlugins(play.PlayScala)
  .dependsOn(webApiJVM)

lazy val sbtRunner = project
  .in(file("sbt-runner"))
  .settings(baseSettings)
  .settings(
    libraryDependencies ++= Seq(
      akka("actor"),
      akka("remote"),
      akka("slf4j")
    ),
    imageNames in docker := Seq(
      ImageName(
        namespace = Some("scalacenter"),
        repository = "scastie-sbt-runner",
        tag = Some(version.value)
      )
    ),
    assemblyMergeStrategy in assembly := {
      case PathList("META-INF", xs @ _ *) => MergeStrategy.discard
      case in @ PathList("reference.conf", xs @ _ *) => {
        val old = (assemblyMergeStrategy in assembly).value
        old(in)
      }
      case x => MergeStrategy.first
    },
    dockerfile in docker := {
      // run crossPublishLocalRuntime

      val ivy = ivyPaths.value.ivyHome.get

      val org                = organization.value
      val artifact           = assembly.value
      val artifactTargetPath = s"/app/${artifact.name}"

      new Dockerfile {
        // docker run --network=host -p 5150:5150 scalacenter/scastie-sbt-runner:0.1.0-SNAPSHOT

        from("scalacenter/scastie-docker-sbt:0.13.13")

        add(ivy / "local" / org, s"/root/.ivy2/local/$org")

        add(artifact, artifactTargetPath)

        expose(5150)
        entryPoint("java", "-Xmx2G", "-Xms512M", "-jar", artifactTargetPath)
      }
    }
  )
  .dependsOn(sbtApi, remoteApi, webApiJVM, instrumentation)
  .enablePlugins(DockerPlugin)
  .disablePlugins(play.PlayScala)

lazy val server = project
  .in(file("scastie"))
  .settings(baseSettings)
  .settings(packageScalaJS(client))
  .settings(
    scalacOptions --= Seq(
      "-Ywarn-unused-import",
      "-Xfatal-warnings"
    ),
    allDependencies ~= (_.map(
      _.exclude("com.typesafe.play", "play-doc_2.11")
        .exclude("com.typesafe.play", "play-docs_2.11")
        .exclude("com.lihaoyi", "upickle_sjs0.6_2.11")
    )),
    libraryDependencies += akka("remote"),
    mainClass in Compile := Option("ProdNettyServer"),
    products in Compile := (products in Compile)
      .dependsOn(WebKeys.assets in Assets)
      .value,
    reStart := reStart.dependsOn(WebKeys.assets in Assets).evaluated,
    WebKeys.public in Assets := (classDirectory in Compile).value / "public",
    mappings in (Compile, packageBin) += (fullOptJS in (client, Compile)).value.data -> "public/client-opt.js"
  )
  .enablePlugins(SbtWeb, play.PlayScala)
  .dependsOn(remoteApi, client, webApiJVM)

lazy val scastie = project
  .in(file("."))
  .aggregate(server)
  .settings(run := {
    val forcePublishLocal = (publishLocal in sbtApi).value

    (reStart in sbtRunner).toTask("").value
    (reStart in server).toTask("").value
  })

/* codemirror is a facade to the javascript rich editor codemirror*/
lazy val codemirror = project
  .settings(baseSettings)
  .settings(
    scalacOptions -= "-Ywarn-dead-code",
    jsDependencies ++= {
      def codemirrorD(path: String): JSModuleID =
        "org.webjars.bower" % "codemirror" % "5.18.2" % "compile" / s"$path.js" minified s"$path.js"

      List(
        "lib/codemirror",
        "addon/comment/comment",
        "addon/dialog/dialog",
        "addon/edit/closebrackets",
        "addon/edit/matchbrackets",
        "addon/fold/brace-fold",
        "addon/fold/foldcode",
        "addon/hint/show-hint",
        "addon/runmode/runmode",
        "addon/scroll/scrollpastend",
        "addon/scroll/simplescrollbars",
        "addon/search/match-highlighter",
        "addon/search/search",
        "addon/search/searchcursor",
        "keymap/sublime",
        "mode/clike/clike"
      ).map(codemirrorD)
    },
    libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "0.9.1"
  )
  .enablePlugins(ScalaJSPlugin)
  .disablePlugins(play.PlayScala)

/* frontend code */
def react(artifact: String, name: String): JSModuleID =
  "org.webjars.bower" % "react" % "15.3.2" % "compile" / s"$artifact.js" minified s"$artifact.min.js" commonJSName name

def react(artifact: String, name: String, depends: String): JSModuleID =
  react(artifact, name).dependsOn(s"$depends.js")

lazy val client = project
  .settings(baseSettings)
  .settings(
    JsEngineKeys.engineType := JsEngineKeys.EngineType.Node,
    skip in packageJSDependencies := false,
    jsDependencies ++= Seq(
      react("react-with-addons", "React"),
      react("react-dom", "ReactDOM", "react-with-addons"),
      react("react-dom-server", "ReactDOMServer", "react-dom")
    ),
    libraryDependencies ++= Seq(
      "com.github.japgolly.scalajs-react" %%% "extra"     % "0.11.2",
      "org.webjars.bower"                 % "open-iconic" % "1.1.1"
    )
  )
  .enablePlugins(ScalaJSPlugin, SbtWeb)
  .dependsOn(codemirror, webApiJS)
  .disablePlugins(play.PlayScala)

/*  instrument a program to add a Map[Position, (Value, Type)]

class A {
  val a = 1 + 1 // << 2: Int
  3 + 3         // << 6: Int
}

 */
lazy val instrumentation = project
  .settings(baseSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.scalameta" %% "scalameta" % "1.2.0",
      "com.googlecode.java-diff-utils" % "diffutils" % "1.3.0" % Test
    )
  )
  .disablePlugins(play.PlayScala)

/* runtime* pretty print values and type */
lazy val runtimeScala = crossProject
  .crossType(CrossType.Pure)
  .in(file("runtime-scala"))
  .settings(baseSettings)
  .settings(
    crossScalaVersions := Seq("2.10.6", "2.11.8", "2.12.1"),
    moduleName := "runtime-scala",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %%% "upickle" % upickleVersion,
      "com.lihaoyi" %%% "pprint"  % upickleVersion
    )
  )
  .dependsOn(webApi)
  .disablePlugins(play.PlayScala)

lazy val runtimeScalaJVM = runtimeScala.jvm
lazy val runtimeScalaJS  = runtimeScala.js

lazy val runtimeDotty = project
  .in(file("runtime-dotty"))
  .settings(orgSettings)
  .settings(
    moduleName := "runtime-dotty",
    scalaVersion := "0.1-20161203-9ceed92-NIGHTLY",
    scalaOrganization := "ch.epfl.lamp",
    scalaBinaryVersion := "2.11",
    scalaCompilerBridgeSource := ("ch.epfl.lamp" % "dotty-sbt-bridge" % "0.1.1-20161203-9ceed92-NIGHTLY" % "component")
      .sources(),
    autoScalaLibrary := false,
    libraryDependencies ++= Seq(
      "org.scala-lang" % "scala-library" % "2.11.5",
      "com.lihaoyi"    %% "upickle"      % upickleVersion
    )
  )
  .dependsOn(webApiJVM)
  .disablePlugins(play.PlayScala)

/* webApi is for the communication between the server and the frontend */
lazy val webApi = crossProject
  .in(file("web-api"))
  .settings(baseSettings)
  .settings(
    crossScalaVersions := Seq("2.10.6", "2.11.8", "2.12.1"),
    libraryDependencies ++= Seq(
      "com.lihaoyi" %%% "autowire" % "0.2.6",
      "com.lihaoyi" %%% "upickle"  % upickleVersion
    )
  )
  .jsSettings(
    libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "0.9.1"
  )
  .disablePlugins(play.PlayScala)

lazy val webApiJVM = webApi.jvm
lazy val webApiJS  = webApi.js

/* sbtApi is for the communication between sbt and the renderer */
lazy val sbtApi = project
  .in(file("sbt-api"))
  .settings(orgSettings)
  .settings(
    scalaVersion := "2.10.6",
    libraryDependencies += "com.lihaoyi" %%% "upickle" % upickleVersion
  )
  .disablePlugins(play.PlayScala)

lazy val sbtScastie = project
  .in(file("sbt-scastie"))
  .settings(orgSettings)
  .settings(
    moduleName := "sbt-scastie",
    scalaVersion := "2.10.6",
    sbtPlugin := true
  )
  .dependsOn(sbtApi)

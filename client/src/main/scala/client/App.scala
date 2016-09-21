package client

import japgolly.scalajs.react._, vdom.all._

import api._
import autowire._
import scalajs.concurrent.JSExecutionContext.Implicits.queue
import org.scalajs.dom

import org.scalajs.dom.{WebSocket, MessageEvent, Event, CloseEvent, ErrorEvent, window}
import scala.util.{Success, Failure}

import upickle.default.{read => uread}

object App {
  case class State(
    code: String,
    websocket: Option[WebSocket] = None,
    output: Vector[String] = Vector(),
    dark: Boolean = true,
    compilationInfos: Set[api.Problem] = Set(),
    sideBarClosed: Boolean = true) {

    def toogleTheme             = copy(dark = !dark)
    def toogleSidebar           = copy(sideBarClosed = !sideBarClosed)
    def log(line: String)       = copy(output = output :+ line)
    def log(lines: Seq[String]) = copy(output = output ++ lines)
  }

  class Backend(scope: BackendScope[_, State]) {
    def codeChange(newCode: String) = scope.modState(_.copy(code = newCode))

    private def connect(id: Long) = CallbackTo[WebSocket]{
      val direct = scope.accessDirect

      def onopen(e: Event): Unit           = direct.modState(_.log("Connected."))
      def onmessage(e: MessageEvent): Unit = {
        val progress = uread[PasteProgress](e.data.toString)
        dom.console.log(progress.toString)
        direct.modState( s =>
            s.log(progress.output).copy(
              compilationInfos = s.compilationInfos ++ progress.compilationInfos.toSet
            )
        )
      }
      def onerror(e: ErrorEvent): Unit     = direct.modState(_.log(s"Error: ${e.message}"))
      def onclose(e: CloseEvent): Unit     = direct.modState(_.copy(websocket = None).log(s"Closed: ${e.reason}"))

      val protocol = if(window.location.protocol == "https:") "wss" else "ws"
      val uri = s"$protocol://${window.location.host}/progress/$id"
      val socket = new WebSocket(uri)

      socket.onopen = onopen _
      socket.onclose = onclose _
      socket.onmessage = onmessage _
      socket.onerror = onerror _
      socket
    }

    def run() = {
      scope.state.map(s =>
        api.Client[Api].run(s.code).call().onSuccess{ case id =>
          val direct = scope.accessDirect
          connect(id).attemptTry.map {
            case Success(ws)    =>
              direct.modState(_.log("Connecting...").copy(
                websocket = Some(ws),
                output = Vector(),
                compilationInfos = Set())
              )
            case Failure(error) => direct.modState(_.log(error.toString).copy(compilationInfos = Set()))
          }.runNow()
        }
      )
    }
    def runE(e: ReactEventI) = run()

    def toogleTheme() = scope.modState(_.toogleTheme)
  }

  val SideBar = ReactComponentB[(State, Backend)]("SideBar")
    .render_P { case (state, backend) =>
      // val label = if(state.dark) "light" else "dark"

      div(
        button(onClick ==> backend.runE)("run"),
        pre(state.compilationInfos.mkString("\n"))
      )
    }
    .build


  val defaultCode =
    """|/***
       |scalaVersion := "0.1-SNAPSHOT"
       |scalaOrganization := "ch.epfl.lamp"
       |scalacOptions ++= Seq("-language:Scala2")
       |scalaBinaryVersion := "2.11"
       |autoScalaLibrary := false
       |libraryDependencies += "org.scala-lang" % "scala-library" % "2.11.5"
       |scalaCompilerBridgeSource := ("ch.epfl.lamp" % "dotty-bridge" % "0.1.1-SNAPSHOT" % "component").sources()
       |*/
       |
       |object Example {
       |  def main(args: Array[String]): Unit = {
       |    e1
       |  }
       |  trait A
       |  trait B
       |
       |  trait Wr {
       |    val z: A with B
       |  }
       |}""".stripMargin

  val component = ReactComponentB[Unit]("App")
    .initialState(State(code = defaultCode))
    .backend(new Backend(_))
    .renderPS((scope, _, state) => {
      val sideStyle =
        if(state.sideBarClosed) "sidebar-closed"
        else "sidebar-open"

      val hideOutput = if(state.output.isEmpty) display.none else display.block

      div(`class` := "app")(
        div(`class` := s"editor $sideStyle")(
          Editor(state, scope.backend),
          ul(`class` := "output", hideOutput)(
            state.output.map(o => li(o))
          )
        ),
        div(`class` := s"sidebar $sideStyle")(SideBar((state, scope.backend)))
      )
    })
    .build

  def apply() = component()
}

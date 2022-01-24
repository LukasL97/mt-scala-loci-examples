package casestudies.sessions

import loci._
import loci.valueref._
import loci.communicator.tcp._
import loci.serializer.upickle._
import loci.transmitter.transmittable._
import upickle.default._

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.io.StdIn
import scala.jdk.CollectionConverters.CollectionHasAsScala

sealed trait SessionState
case class AwaitLogin() extends SessionState
case class LoggedIn(user: String) extends SessionState

object SessionState {
  implicit val transmittable: IdenticallyTransmittable[SessionState] = IdenticallyTransmittable()
  implicit val serializable: ReadWriter[SessionState] = macroRW[SessionState]
}

object AwaitLogin {
  implicit val serializable: ReadWriter[AwaitLogin] = macroRW[AwaitLogin]
}

object LoggedIn {
  implicit val serializable: ReadWriter[LoggedIn] = macroRW[LoggedIn]
}

sealed trait Request
case class InitRequest() extends Request
case class LoginRequest(user: String, password: String) extends Request
case class AddRequest(entry: String) extends Request
case class GetRequest() extends Request

object Request {
  implicit val transmittable: IdenticallyTransmittable[Request] = IdenticallyTransmittable()
  implicit val serializable: ReadWriter[Request] = macroRW[Request]
}

object InitRequest {
  implicit val serializable: ReadWriter[InitRequest] = macroRW[InitRequest]
}

object LoginRequest {
  implicit val serializable: ReadWriter[LoginRequest] = macroRW[LoginRequest]
}

object AddRequest {
  implicit val serializable: ReadWriter[AddRequest] = macroRW[AddRequest]
}

object GetRequest {
  implicit val serializable: ReadWriter[GetRequest] = macroRW[GetRequest]
}

sealed trait Response
case class AwaitLoginResponse(message: String) extends Response
case class WelcomeResponse(message: String) extends Response
case class ErrorResponse(message: String) extends Response
case class AddResponse(added: Boolean) extends Response
case class GetResponse(entries: Seq[String]) extends Response

object Response {
  implicit val transmittable: IdenticallyTransmittable[Response] = IdenticallyTransmittable()
  implicit val serializable: ReadWriter[Response] = macroRW[Response]
}

object AwaitLoginResponse {
  implicit val serializable: ReadWriter[AwaitLoginResponse] = macroRW[AwaitLoginResponse]
}

object WelcomeResponse {
  implicit val serializable: ReadWriter[WelcomeResponse] = macroRW[WelcomeResponse]
}

object ErrorResponse {
  implicit val serializable: ReadWriter[ErrorResponse] = macroRW[ErrorResponse]
}

object AddResponse {
  implicit val serializable: ReadWriter[AddResponse] = macroRW[AddResponse]
}

object GetResponse {
  implicit val serializable: ReadWriter[GetResponse] = macroRW[GetResponse]
}

/**
 * Showcase for remote value references and dynamic remote selection based on network monitoring.
 *
 * The Client sends a request to the Gateway, which selects a Server based on the lowest number of bytes sent to a
 * remote of type Server. Then the Server returns a remote value reference to a Session object that the Client sends with
 * every further request. On every further request of a Client, the Gateway uses the remote Server the Session lives on
 * to route the request to, in order to make sure that a Client Session is handled by only one Server.
 */
@multitier object Sessions {

  @peer type DB <: { type Tie <: Multiple[Server] }
  @peer type Server <: { type Tie <: Single[Gateway] with Multiple[Server] with Single[DB] }
  @peer type Gateway <: { type Tie <: Multiple[Client] with Multiple[Server] }
  @peer type Client <: { type Tie <: Single[Gateway] }

  val db: Local[ConcurrentHashMap[String, ConcurrentLinkedQueue[String]]] on DB = on[DB] { implicit! =>
    new ConcurrentHashMap[String, ConcurrentLinkedQueue[String]]()
  }

  def addEntry(user: String, entry: String): Boolean on DB = on[DB] { implicit! =>
    if (db.containsKey(user)) {
      db.get(user).add(entry)
    } else {
      val entries = new ConcurrentLinkedQueue[String]()
      entries.add(entry)
      db.put(user, entries)
      true
    }
  }

  def getEntries(user: String): Seq[String] on DB = on[DB] { implicit! =>
    db.getOrDefault(user, new ConcurrentLinkedQueue[String]()).asScala.toSeq
  }

  def selectServer(session: Option[SessionState via Server])(servers: Seq[Remote[Server]]): Local[Remote[Server]] on Gateway = on[Gateway] { implicit! =>
    session.map(_.getRemote).getOrElse(
      servers.map { server =>
        server -> networkMonitor.sumSentBytes(server)
      }.minBy(_._2)._1
    )
  }

  def requestServer(
    request: Request,
    session: Option[SessionState via Server]
  ): Future[(Response, SessionState via Server)] on Gateway = on[Gateway] { implicit! =>
    remoteAny.apply[Server](selectServer(session)(_)).call(executeRequest(request, session)).asLocal
  }

  def executeRequest(
    request: Request,
    session: Option[SessionState via Server]
  ): Future[(Response, SessionState via Server)] on Server = on[Server] { implicit! =>
    (session, request) match {
      case (None, InitRequest()) =>
        Future.successful(AwaitLoginResponse("Enter user and password:") -> AwaitLogin().asValueRef)
      case (None, _) =>
        Future.successful(ErrorResponse("Missing session. Enter user and password:") -> AwaitLogin().asValueRef)
      case (Some(session), request) => session.getValue.flatMap { state =>
        (state, request) match {
          case (AwaitLogin(), LoginRequest(user, password)) =>
            Future.successful(WelcomeResponse(s"Welcome, $user!") -> LoggedIn(user).asValueRef)
          case (LoggedIn(user), AddRequest(entry)) =>
            remote[DB].call(addEntry(user, entry)).asLocal.map { added =>
              AddResponse(added) -> session
            }
          case (LoggedIn(user), GetRequest()) =>
            remote[DB].call(getEntries(user)).asLocal.map { entries =>
              GetResponse(entries) -> session
            }
          case (_, _) =>
            Future.successful(ErrorResponse("Invalid request. Enter user and password:") -> AwaitLogin().asValueRef)
        }
      }
    }
  }

  def main(): Unit on Client = on[Client] { implicit! =>
    StdIn.readLine()
    remote[Gateway].call(requestServer(InitRequest(), None)).asLocal.foreach {
      case (AwaitLoginResponse(message), session) =>
        println(message)
        val user = StdIn.readLine().trim
        val password = StdIn.readLine().trim
        remote[Gateway].call(requestServer(LoginRequest(user, password), Option(session))).asLocal.foreach {
          case (WelcomeResponse(message), session) =>
            println(message)
            var currentSession = session
            for (line <- scala.io.Source.stdin.getLines()) {
              val response = line.trim match {
                case "" => remote[Gateway].call(requestServer(GetRequest(), Option(currentSession))).asLocal
                case entry => remote[Gateway].call(requestServer(AddRequest(entry), Option(currentSession))).asLocal
              }
              response.collect {
                case (GetResponse(entries), newSession) =>
                  println(entries.mkString("\n"))
                  currentSession = newSession
                case (AddResponse(added), newSession) =>
                  currentSession = newSession
              }
            }
        }
    }
  }

}

object DB extends App {
  multitier start new Instance[Sessions.DB](
    listen[Sessions.Server](TCP(50003))
  )
}

object Gateway extends App {
  multitier start new Instance[Sessions.Gateway](
    listen[Sessions.Server](TCP(50001)) and
      listen[Sessions.Client](TCP(50002)),
    NetworkMonitorConfig(5.seconds, 10.minutes, 10.minutes)
  )
}

object Server extends App {
  multitier start new Instance[Sessions.Server](
    connect[Sessions.Gateway](TCP("localhost", 50001)) and
      connect[Sessions.DB](TCP("localhost", 50003))
  )
}

object Client extends App {
  multitier start new Instance[Sessions.Client](
    connect[Sessions.Gateway](TCP("localhost", 50002))
  )
}

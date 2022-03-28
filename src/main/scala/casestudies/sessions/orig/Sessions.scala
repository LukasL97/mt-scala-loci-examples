package casestudies.sessions.orig

import loci._
import loci.communicator.tcp._
import loci.language.Placed.lift
import loci.transmitter.transmittable._
import upickle.default._
import loci.serializer.upickle._

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration
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

sealed trait Request {
  def getBytes: Int = 1
}
case class InitRequest() extends Request
case class LoginRequest(user: String, password: String) extends Request {
  override def getBytes: Int = super.getBytes + user.getBytes.length + password.getBytes.length
}
case class AddRequest(entry: String) extends Request {
  override def getBytes: Int = super.getBytes + entry.getBytes.length
}
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

case class SessionReference(serverId: UUID, sessionId: UUID)

object SessionReference {
  implicit val transmittable: IdenticallyTransmittable[SessionReference] = IdenticallyTransmittable()
  implicit val serializable: ReadWriter[SessionReference] = macroRW[SessionReference]
}

case class BytesInfo(
  time: LocalDateTime,
  bytes: Int
)

@multitier object Sessions {

  @peer type DB <: { type Tie <: Multiple[Server] }
  @peer type Server <: { type Tie <: Single[Gateway] with Single[DB] }
  @peer type Gateway <: { type Tie <: Multiple[Client] with Multiple[Server] }
  @peer type Client <: { type Tie <: Single[Gateway] }

  val id: UUID on Server = on[Server] { implicit! => UUID.randomUUID() }

  val cachedServers: Local[mutable.Map[UUID, Remote[Server]]] on Gateway = on[Gateway] { implicit! =>
    mutable.Map.empty[UUID, Remote[Server]]
  }

  val sentBytes: Local[mutable.Map[Remote[Server], Seq[BytesInfo]]] on Gateway = on[Gateway] { implicit! =>
    mutable.Map.empty[Remote[Server], Seq[BytesInfo]]
  }

  def sumSentBytes(server: Remote[Server], last: Duration): Local[Int] on Gateway = on[Gateway] { implicit! =>
    val recentMessages = sentBytes
      .getOrElse(server, Seq.empty[BytesInfo])
      .filter(_.time.isAfter(LocalDateTime.now().minus(last.toMillis, ChronoUnit.MILLIS)))
    sentBytes.put(server, recentMessages)
    recentMessages.map(_.bytes).sum
  }

  def putSentBytes(server: Remote[Server], bytes: Int): Local[Unit] on Gateway = on[Gateway] { implicit! =>
    val serverMessages = sentBytes.getOrElse(server, Seq.empty[BytesInfo])
    sentBytes.put(server, serverMessages.appended(BytesInfo(LocalDateTime.now(), bytes)))
  }

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

  def selectServer(session: Option[SessionReference]): Local[Future[Remote[Server]]] on Gateway = on[Gateway] { implicit! =>
    session.map {
      case SessionReference(serverId, _) =>
        cachedServers.get(serverId).map(Future.successful).getOrElse(getUncachedServer(serverId))
    }.getOrElse {
      Future.successful(
        remote[Server].connected.map {
          server => server -> (sumSentBytes(server, last = 5.minutes): Int)
        }.minBy(_._2)._1
      )
    }
  }

  def getUncachedServer(serverId: UUID): Local[Future[Remote[Server]]] on Gateway = on[Gateway] { implicit! =>
    val uncachedServers = remote[Server].connected.toSet.diff(cachedServers.values.toSet)
    Future.sequence(
      uncachedServers.map { server => (id from server).asLocal.map(_ -> server) }
    ).map { servers =>
      servers.foreach { case (id, server) => cachedServers.put(id, server) }
      cachedServers.getOrElse(serverId, throw new RuntimeException(s"Unknown server id $serverId"))
    }
  }

  def requestServer(request: Request, session: Option[SessionReference]): Future[(Response, SessionReference)] on Gateway =
    on[Gateway] { implicit! =>
      selectServer(session).flatMap { server =>
        putSentBytes(server, request.getBytes)
        remote(server).call(executeRequest(request, session)).asLocal
      }
    }

  def executeRequest(
    request: Request,
    session: Option[SessionReference]
  ): Future[(Response, SessionReference)] on Server = on[Server] { implicit! =>
    println(s"EXECUTING REQUEST: $request")
    (session, request) match {
      case (None, InitRequest()) =>
        Future.successful(AwaitLoginResponse("Enter user and password:") -> newSessionReference(AwaitLogin()))
      case (None, _) =>
        Future.successful(ErrorResponse("Missing session. Enter user and password:") -> newSessionReference(AwaitLogin()))
      case (Some(ref), request) =>
        val state: SessionState = getSession(ref)
        (state, request) match {
          case (AwaitLogin(), LoginRequest(user, password)) =>
            Future.successful(WelcomeResponse(s"Welcome, $user!") -> newSessionReference(LoggedIn(user)))
          case (LoggedIn(user), AddRequest(entry)) =>
            remote[DB].call(addEntry(user, entry)).asLocal.map { added =>
              AddResponse(added) -> ref
            }
          case (LoggedIn(user), GetRequest()) =>
            remote[DB].call(getEntries(user)).asLocal.map { entries =>
              GetResponse(entries) -> ref
            }
          case (_, _) =>
            Future.successful(ErrorResponse("Invalid request. Enter user and password:") -> newSessionReference(AwaitLogin()))
        }
    }
  }

  val sessionReferences: Local[mutable.Map[UUID, SessionState]] on Server = on[Server] { implicit! =>
    mutable.Map.empty[UUID, SessionState]
  }

  def newSessionReference(session: SessionState): Local[SessionReference] on Server = on[Server] { implicit! =>
    val sessionId = UUID.randomUUID()
    sessionReferences.put(sessionId, session)
    SessionReference(id, sessionId)
  }

  def getSession(ref: SessionReference): Local[SessionState] on Server = on[Server] { implicit! =>
    sessionReferences.getOrElse(ref.sessionId, throw new RuntimeException(s"Unknown session id ${ref.sessionId}"))
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
      listen[Sessions.Client](TCP(50002))
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

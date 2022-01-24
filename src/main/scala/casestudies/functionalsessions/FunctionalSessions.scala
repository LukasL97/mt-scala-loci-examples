package casestudies.functionalsessions

import loci._
import loci.valueref._
import loci.communicator.tcp._
import loci.serializer.upickle._
import loci.transmitter.transmittable._
import upickle.default._

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.io.StdIn
import scala.jdk.CollectionConverters.CollectionHasAsScala

/**
 * Showcase for remote value reference to a closure that is only accessed locally and therefore does not require
 * serialization.
 *
 * The Client initializes communication with the Server and gets back a reference to a login closure, which it uses in
 * a remote block to login to the Server. Then it uses the session it gets from the server to add entries to a db or
 * get all entries in the DB.
 */
@multitier object FunctionalSessions {
  @peer type Server <: { type Tie <: Multiple[Client] }
  @peer type Client <: { type Tie <: Single[Server] }

  val db: Local[ConcurrentHashMap[String, ConcurrentLinkedQueue[String]]] on Server = on[Server] local { implicit! =>
    new ConcurrentHashMap[String, ConcurrentLinkedQueue[String]]()
  }

  def addEntry(userSession: String via Server, entry: String): Boolean on Server = on[Server] { implicit! =>
    val user = userSession.getValueLocally
    if (db.containsKey(user)) {
      db.get(user).add(entry)
    } else {
      val entries = new ConcurrentLinkedQueue[String]()
      entries.add(entry)
      db.put(user, entries)
      true
    }
  }

  def getEntries(userSession: String via Server): Seq[String] on Server = on[Server] { implicit! =>
    val user = userSession.getValueLocally
    db.getOrDefault(user, new ConcurrentLinkedQueue[String]()).asScala.toSeq
  }

  def main(): Unit on Client = on[Client] { implicit! =>
    StdIn.readLine()
    val loginClosure: Future[((String, String) => String via Server) via Server] = {
      on[Server].run {
        // create a remote value reference to a closure, mapping user and password to a session object (another remote value reference)
        implicit! => {
          (user: String, password: String) =>
            user.asValueRef
        }.asValueRef
      }.asLocal
    }
    println("Enter user and password:")
    val user = StdIn.readLine().trim
    val password = StdIn.readLine().trim
    val userSession: Future[String via Server] = loginClosure.flatMap { login =>
      on[Server].run.capture(login, user, password) { implicit! =>
        // use the login closure reference locally on the server to login with user and password
        login.getValueLocally(user, password)
      }.asLocal
    }
    userSession.foreach { session =>
      println(s"Welcome, $user!")
      for (line <- scala.io.Source.stdin.getLines()) {
        line match {
          case "" =>
            remote[Server].call(getEntries(session)).asLocal.foreach(entries => println(entries.mkString("\n")))
          case entry =>
            remote[Server].call(addEntry(session, entry))
        }
      }
    }
  }

}

object Server extends App {
  multitier start new Instance[FunctionalSessions.Server](
    listen[FunctionalSessions.Client](TCP(50001))
  )
}

object Client extends App {
  multitier start new Instance[FunctionalSessions.Client](
    connect[FunctionalSessions.Server](TCP("localhost", 50001))
  )
}

package casestudies.trust

import loci._
import loci.communicator.tcp._
import loci.transmitter.transmittable._
import loci.valueref._
import loci.serializer.upickle._
import upickle.default._
import upickle.default.macroRW

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Random

case class Key(id: Int)

object Key {
  implicit val transmittable: IdenticallyTransmittable[Key] = IdenticallyTransmittable()
  implicit val rw: ReadWriter[Key] = macroRW[Key]
}

@multitier object Trust {

  @peer type TrustedKeyDB <: { type Tie <: Multiple[ResourceManager] with Multiple[KeyManager] }
  @peer type PublicKeyDB <: { type Tie <: Multiple[SuperVisor] with Multiple[KeyManager] }

  @peer type KeyManager <: { type Tie <: Single[PublicKeyDB] with Single[TrustedKeyDB] with Multiple[Client] with Multiple[SuperVisor] }
  @peer type SuperVisor <: { type Tie <: Single[PublicKeyDB] with Multiple[ResourceManager] with Single[KeyManager] }
  @peer type ResourceManager <: { type Tie <: Single[TrustedKeyDB] with Multiple[Server] with Single[SuperVisor] }

  @peer type Server <: { type Tie <: Single[ResourceManager] with Multiple[Client] }
  @peer type Client <: { type Tie <: Single[Server] with Single[KeyManager] }

  type ID = Int
  type Resource = String

  /**
   * Maps [[Client]] IDs to their respective key. [[TrustedKeyDB]] and [[PublicKeyDB]] can contain different keys.
   */
  val db: Local[mutable.Map[ID, Key]] on (TrustedKeyDB | PublicKeyDB) = on[TrustedKeyDB | PublicKeyDB] local { implicit! =>
    mutable.Map.empty[ID, Key]
  }

  /**
   * A [[Client]] can register itself in the system by sending its ID to the [[KeyManager]], which then asks the
   * [[PublicKeyDB]] to generate a key for this ID.
   */
  def register(id: ID): Unit on KeyManager = on[KeyManager] { implicit! =>
    on[PublicKeyDB].run.capture(id) { implicit! => db.put(id, Key(id)) }
  }

  /**
   * A [[Client]] can ask its own key from the [[KeyManager]], which retrieves it either from the [[TrustedKeyDB]]
   * or the [[PublicKeyDB]]. It only returns a reference to the value that lives on the respective DB.
   */
  def getKey(id: ID): Future[Key via (TrustedKeyDB | PublicKeyDB)] on KeyManager = on[KeyManager] { implicit! =>
    on[TrustedKeyDB].run.capture(id) { implicit! => db.contains(id) }.asLocal.flatMap {
      case true => on[TrustedKeyDB].run.capture(id) { implicit! => db(id).asValueRef }.asLocal
      case false => on[PublicKeyDB].run.capture(id) { implicit! => db(id).asValueRef }.asLocal
    }
  }

  /**
   * The [[SuperVisor]] can ask the [[KeyManager]] to mark a public key as trusted. It sends the reference to the key
   * living on [[PublicKeyDB]] to the [[KeyManager]], which accesses the reference, then asks the [[TrustedKeyDB]]
   * to add the key to its map and returns a new reference to the key living on [[TrustedKeyDB]].
   */
  def trust(key: Key via PublicKeyDB): Future[Key via TrustedKeyDB] on KeyManager = on[KeyManager] { implicit! =>
    key.getValue.flatMap {
      case key @ Key(id) => on[TrustedKeyDB].run.capture(id, key) { implicit! =>
        db.put(id, key).getOrElse(key).asValueRef
      }.asLocal
    }
  }

  /**
   * If the [[SuperVisor]] confirms the reference to the public key, it asks the [[KeyManager]] to mark it as trusted
   * and returns the resulting reference to the trusted key.
   */
  def confirm(key: Key via PublicKeyDB): Option[Future[Key via TrustedKeyDB]] on SuperVisor = on[SuperVisor] { implicit! =>
    println(s"Do you trust the key?")
    scala.io.StdIn.readLine().toLowerCase match {
      case "y" | "yes" => Some(remote[KeyManager].call(trust(key)).asLocal)
      case "n" | "no" => None
    }
  }

  /**
   * When the key is trusted, the [[ResourceManager]] provides a resource.
   */
  def unlock(key: Key via TrustedKeyDB): Option[Resource] on ResourceManager = on[ResourceManager] { implicit! =>
    Some("resource")
  }

  /**
   * The [[Server]] asks the [[ResourceManager]] for a resource given some reference to a key. If the reference refers
   * to a trusted key, the [[ResourceManager]] just unlocks the resource. Otherwise, the [[ResourceManager]] asks the
   * [[SuperVisor]] to confirm that the public key can be trusted, and only if this is the case the [[ResourceManager]]
   * unlocks the resource.
   */
  def handle(key: Key via (TrustedKeyDB | PublicKeyDB)): Future[Option[Resource]] on ResourceManager = on[ResourceManager] { implicit! =>
    key.asVia[TrustedKeyDB] match {
      case Some(key) => Future.successful(unlock(key))
      case None => remote[SuperVisor].call(confirm(key.asVia[PublicKeyDB].get)).asLocal.flatMap {
        case Some(key) => key.map(unlock)
        case None => Future.successful(None)
      }
    }
  }

  val id: ID on Client = on[Client] { implicit! => Random.nextInt(1000000) }

  /**
   * The [[Client]] first registers itself at the [[KeyManager]]. For each user input, it fetches a reference to its
   * key from the [[KeyManager]] and uses it to request the server, which requires the reference to the client key to
   * get a resource from the [[ResourceManager]].
   */
  def main(): Unit on Client = on[Client] { implicit! =>
    remote[KeyManager].call(register(id))
    for (_ <- scala.io.Source.stdin.getLines()) {
      remote[KeyManager].call(getKey(id)).asLocal.foreach { keyRef =>
        val serverResponse = on[Server].run.capture(keyRef) { implicit! =>
          remote.call(handle(keyRef)).asLocal.map {
            case Some(resource) => s"Doing stuff with resource: $resource"
            case None => "Could not do stuff without a resource"
          }
        }.asLocal
        serverResponse.foreach(println)
      }
    }
  }

}

object TrustedKeyDB extends App {
  multitier start new Instance[Trust.TrustedKeyDB](
    listen[Trust.ResourceManager](TCP(50001)) and
      listen[Trust.KeyManager](TCP(50002))
  )
}

object PublicKeyDB extends App {
  multitier start new Instance[Trust.PublicKeyDB](
    listen[Trust.SuperVisor](TCP(50003)) and
      listen[Trust.KeyManager](TCP(50004))
  )
}

object KeyManager extends App {
  multitier start new Instance[Trust.KeyManager](
    connect[Trust.PublicKeyDB](TCP("localhost", 50004)) and
      connect[Trust.TrustedKeyDB](TCP("localhost", 50002)) and
      listen[Trust.Client](TCP(50007)) and
      listen[Trust.SuperVisor](TCP(50008))
  )
}

object SuperVisor extends App {
  multitier start new Instance[Trust.SuperVisor](
    connect[Trust.PublicKeyDB](TCP("localhost", 50003)) and
      listen[Trust.ResourceManager](TCP(50006)) and
      connect[Trust.KeyManager](TCP("localhost", 50008))
  )
}

object ResourceManager extends App {
  multitier start new Instance[Trust.ResourceManager](
    connect[Trust.TrustedKeyDB](TCP("localhost", 50001)) and
      listen[Trust.Server](TCP(50005)) and
      connect[Trust.SuperVisor](TCP("localhost", 50006))
  )
}

object Server extends App {
  multitier start new Instance[Trust.Server](
    connect[Trust.ResourceManager](TCP("localhost", 50005)) and
      listen[Trust.Client](TCP(50009))
  )
}

object Client extends App {
  multitier start new Instance[Trust.Client](
    connect[Trust.Server](TCP("localhost", 50009)) and
      connect[Trust.KeyManager](TCP("localhost", 50007))
  )
}

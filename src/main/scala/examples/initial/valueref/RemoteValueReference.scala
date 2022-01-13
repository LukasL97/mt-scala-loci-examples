package examples.initial.valueref

import loci._
import loci.communicator.tcp._
import loci.serializer.upickle._
import loci.transmitter.transmittable._

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.Random
import scala.concurrent.ExecutionContext.Implicits.global

@multitier object RemoteValueReference {
  @peer type Top <: { type Tie <: Multiple[Left] with Multiple[Right] }
  @peer type Bottom <: { type Tie <: Multiple[Left] with Multiple[Right] }
  @peer type Left <: { type Tie <: Multiple[Top] with Multiple[Bottom] }
  @peer type Right <: { type Tie <: Multiple[Top] with Multiple[Bottom] }

  val cache: Local[mutable.Map[Int, String]] on Top = on[Top] local { implicit! => mutable.Map.empty[Int, String] }

  def getFromCache(valueId: Int): String on Top = on[Top] { implicit! => cache.apply(valueId) }

  val peerId: Int on Top = on[Top] { implicit! =>
    val id = new Random().nextInt(1000)
    println(id)
    id
  }

  def getRef: ValueRef on Top = on[Top] { implicit! =>
    val value = "VALUE"
    val valueId = new Random().nextInt(1000)
    cache.put(valueId, value)
    ValueRef(peerId, valueId)
  }

  def createTopRef: Future[ValueRef] on Left = on[Left] { implicit! =>
    val connectedTop = remote[Top].connected.head
    remote(connectedTop).call(getRef).asLocal
  }

  def fetchValue(ref: ValueRef): Future[String] on Right = on[Right] { implicit! =>
    val connectedTop = remote[Top].connected.head
    val x = (peerId from connectedTop).asLocal
    (peerId from connectedTop).asLocal.flatMap {
      case peerId if peerId == ref.peerId => remote(connectedTop).call(getFromCache(ref.valueId)).asLocal
    }
  }

  def main(): Unit on Bottom = on[Bottom] { implicit! =>
    for (_ <- scala.io.Source.stdin.getLines) {
      val connectedLeft = remote[Left].connected.head
      val connectedRight = remote[Right].connected.head
      val topRef: Future[ValueRef] = remote(connectedLeft).call(createTopRef).asLocal
      topRef.flatMap(ref => remote(connectedRight).call(fetchValue(ref)).asLocal).foreach(println)
    }
  }
}

case class ValueRef(peerId: Int, valueId: Int)

object ValueRef {
  implicit val valueRefTransmittable: IdenticallyTransmittable[ValueRef] = IdenticallyTransmittable()
  implicit val valueRefSerializer: upickle.default.ReadWriter[ValueRef] = upickle.default.macroRW[ValueRef]
}

object Top extends App {
  multitier start new Instance[RemoteValueReference.Top](
    listen[RemoteValueReference.Left](TCP(40001)) and
      listen[RemoteValueReference.Right](TCP(40002))
  )
}

object Bottom extends App {
  multitier start new Instance[RemoteValueReference.Bottom](
    connect[RemoteValueReference.Left](TCP("localhost", 40003)) and
      connect[RemoteValueReference.Right](TCP("localhost", 40004))
  )
}

object Left extends App {
  multitier start new Instance[RemoteValueReference.Left](
    connect[RemoteValueReference.Top](TCP("localhost", 40001)) and
      listen[RemoteValueReference.Bottom](TCP(40003))
  )
}

object Right extends App {
  multitier start new Instance[RemoteValueReference.Right](
    connect[RemoteValueReference.Top](TCP("localhost", 40002)) and
      listen[RemoteValueReference.Bottom](TCP(40004))
  )
}

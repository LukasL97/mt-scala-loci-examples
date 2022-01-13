package casestudies.imageviewer

import loci._
import loci.valueref._
import loci.communicator.tcp._
import loci.serializer.upickle._
import loci.transmitter.transmittable._
import upickle.default._

import java.util.UUID
import scala.collection.mutable
import scala.util.Random
import scala.concurrent.ExecutionContext.Implicits.global

case class Image(
  name: String,
  pixels: Array[Byte]
)

object Image {
  implicit val transmittable: IdenticallyTransmittable[Image] = IdenticallyTransmittable()
  implicit val rw: ReadWriter[Image] = macroRW[Image]
}

sealed trait ClientAppState
case object BaseState extends ClientAppState
case object SelectionState extends ClientAppState

@multitier object ImageViewer {
  @peer type Client <: { type Tie <: Single[Server] }
  @peer type Server <: { type Tie <: Multiple[Client] }

  val images: Local[mutable.Set[Image]] on Server = on[Server] local { implicit! =>
    mutable.Set.empty[Image]
  }

  def store(image: Image): Unit on Server = on[Server] { implicit! =>
    images.add(image)
  }

  def getImageReferences(): Seq[(String, Int, Image via Server)] on Server = on[Server] { implicit! =>
    images.map { image =>
      (image.name, image.pixels.length, image.asValueRef)
    }.toSeq
  }

  def createImage: Local[Image] on Client = on[Client] local { implicit! =>
    val name = UUID.randomUUID().toString
    val size = new Random().nextInt(100000)
    val pixels = new Random().nextBytes(size)
    Image(name, pixels)
  }

  def main(): Unit on Client = on[Client] { implicit! =>
    var state: ClientAppState = BaseState
    var imageReferences: Seq[(String, Int, Image via Server)] = Seq()
    for (input <- scala.io.Source.stdin.getLines()) {
      (input, state) match {
        case ("get", BaseState) =>
          remote[Server].call(getImageReferences()).asLocal.foreach { references =>
            imageReferences = references.toSeq
            imageReferences.zipWithIndex.foreach {
              case ((name, size, _), index) => println(s"$index: $name ($size)")
            }
            state = SelectionState
          }
        case (number, SelectionState) =>
          imageReferences(number.toInt)._3.getValue.foreach { image =>
            println(image.name)
            println(image.pixels.slice(0, 10).mkString("Array(", ", ", ")"))
            state = BaseState
          }
        case ("create", BaseState) =>
          val image = createImage
          remote[Server].call(store(image))
      }
    }
  }

}

object Server extends App {
  multitier start new Instance[ImageViewer.Server](
    listen[ImageViewer.Client](TCP(50001))
  )
}

object Client extends App {
  multitier start new Instance[ImageViewer.Client](
    connect[ImageViewer.Server](TCP("localhost", 50001))
  )
}

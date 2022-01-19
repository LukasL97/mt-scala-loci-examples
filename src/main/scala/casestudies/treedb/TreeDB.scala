package casestudies.treedb

import loci._
import loci.communicator.tcp._
import loci.contexts.Immediate.Implicits.global
import loci.serializer.upickle._
import loci.transmitter.transmittable._
import upickle.default._

import scala.collection.mutable
import scala.concurrent.Future

@multitier object TreeDB {

  @peergroup type CLI

  @peergroup type BaseNode <: { type Tie <: Multiple[Child] }
  @peergroup type Parent <: BaseNode { type Tie <: Multiple[Child] }
  @peergroup type Child <: BaseNode { type Tie <: Optional[Parent] with Multiple[Child] }
  @peer type DataNode <: Parent with Child with CLI { type Tie <: Optional[Parent] with Multiple[Child] with Multiple[Client] }

  @peer type Client <: CLI { type Tie <: Single[DataNode] }

  val n = 1000

  val partition: Local[mutable.Map[Int, String]] on BaseNode = on[BaseNode] local { implicit ! =>
    mutable.Map.empty[Int, String]
  }

  var keyRange: Local[(Int, Int)] on BaseNode = on[BaseNode] local { implicit ! => (0, 0) }
  val childrenKeyRanges: Local[mutable.Map[Remote[Child], (Int, Int)]] on BaseNode = on[BaseNode] local { implicit ! =>
    mutable.Map.empty[Remote[Child], (Int, Int)]
  }

  /**
   * This method needs to be placed on the `Child` instead of `DataNode` as it needs to be able to be executed again
   * on the returned `Child` (recursive remote selection).
   */
  def selectNode(key: Int)(children: Seq[Remote[Child]]): Local[Remote[Child]] on Child = on[Child] { implicit! =>
    val keyHash = key % n
    println(keyHash)
    if (keyHash >= keyRange._1 && keyHash < keyRange._2) {
      self
    } else {
      children.find(child => keyHash >= childrenKeyRanges(child)._1 && keyHash < childrenKeyRanges(child)._2).get
    }
  }

  def insert(key: Int, value: String): Future[Option[String]] on Child = on[Child] { implicit! =>
    onAny.recursive[Child](selectNode(key) _).run.capture(key, value) { implicit! =>
      println(s"INSERT $key: $value")
      partition.put(key, value)
    }.asLocal
  }

  def get(key: Int): Future[Option[String]] on Child = on[Child] { implicit! =>
    onAny.recursive[Child](selectNode(key) _).run.capture(key) { implicit! =>
      println(s"GET $key")
      partition.get(key)
    }.asLocal
  }

  /**
   * Init the key ranges recursively, each node takes a key range for itself and distributes the rest of the key
   * ranges to its children.
   */
  def initKeyRanges(lo: Int, hi: Int): Unit on BaseNode = on[BaseNode] { implicit! =>
    val children = remote[Child].connected
    val keyRangeSizePerNode = (hi - lo) / (children.size + 1)
    children.zipWithIndex.foreach {
      case (child, index) =>
        val childLo = lo + index * keyRangeSizePerNode
        val childHi = lo + (index + 1) * keyRangeSizePerNode
        remote(child).call(initKeyRanges(childLo, childHi))
        childrenKeyRanges.put(child, childLo -> childHi)
    }
    keyRange = (lo + children.size * keyRangeSizePerNode, hi)
    println(keyRange)
    println(childrenKeyRanges)
  }

  def main(): Unit on CLI = on[DataNode] { implicit ! =>
    for (_ <- scala.io.Source.stdin.getLines()) {
      println("INITIALIZE KEY RANGES")
      initKeyRanges(0, n)
    }
  } and on[Client] { implicit! =>
    for (line <- scala.io.Source.stdin.getLines()) {
      line.split(' ').toSeq match {
        case Seq("insert", key, value) => remote[DataNode].call(insert(key.toInt, value))
        case Seq("get", key) => remote[DataNode].call(get(key.toInt)).asLocal.foreach(println)
      }
    }
  }

}

object Root extends App {
  multitier start new Instance[TreeDB.DataNode](
    listen[TreeDB.Child](TCP(50001)) and
      listen[TreeDB.Child](TCP(50002)) and
      listen[TreeDB.Client](TCP(50003))
  )
}

object Left extends App {
  multitier start new Instance[TreeDB.DataNode](
    connect[TreeDB.Parent](TCP("localhost", 50001))
  )
}

object Right extends App {
  multitier start new Instance[TreeDB.DataNode](
    connect[TreeDB.Parent](TCP("localhost", 50002))
  )
}

object Client extends App {
  multitier start new Instance[TreeDB.Client](
    connect[TreeDB.DataNode](TCP("localhost", 50003))
  )
}

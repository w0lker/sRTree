package edu.thu.cs.database.spark.rdd

//import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

//import scala.collection.JavaConverters._
import scala.collection.mutable

//import com.github.davidmoten.rtree.geometry.{Geometry, Rectangle, Geometries}
//import com.github.davidmoten.rtree.{InternalStructure, Entry, RTree, Entries}
//import org.apache.hadoop.io.{BytesWritable, NullWritable}
//import org.apache.hadoop.mapred.SequenceFileInputFormat
//import edu.thu.cs.database.spark.RTreeInputFormat
import edu.thu.cs.database.spark.rtree._
import edu.thu.cs.database.spark.spatial._
import edu.thu.cs.database.spark.partitioner.RTreePartitioner
import org.apache.spark.rdd.{ShuffledRDD, PartitionPruningRDD, RDD}
import org.apache.spark._
import rx.functions.Func1

//import scala.collection.JavaConversions.asScalaIterator
import scala.reflect.ClassTag

/**
  * Created by lihaoda on 16-3-23.
  */

object RTreeRDD {

  class RTreeRDDImpl[T: ClassTag](rdd: RDD[(Point, T)], max_entry_per_node:Int = 25) extends RDD[(RTree, Array[(Point, T)])](rdd) {
    override def getPartitions: Array[Partition] = firstParent[(Point, T)].partitions
    override def compute(split: Partition, context: TaskContext): Iterator[(RTree, Array[(Point, T)])] = {
      val it = firstParent[(Point, T)].iterator(split, context)
      val b = mutable.ListBuffer[(Point, T)]()
      while (it.hasNext) {
        b += it.next
      }
      if(b.nonEmpty) {
        //val geos = array.map(_._1).zipWithIndex
        val tree = RTree(b.map(_._1).zipWithIndex.toArray, max_entry_per_node)
        Iterator((tree, b.toArray))
      } else {
        Iterator()
      }
    }
  }

  def getActualSavePath(path:String) = {
    if(path.endsWith("/") || path.endsWith("\\")) {
      (path+"data", path+"global")
    } else {
      (path+"/data", path+"/global")
    }
  }

  def repartitionRDDorNot[T: ClassTag](rdd: RDD[T], numPartitions: Int): RDD[T] = {
    if (numPartitions > 0 && numPartitions != rdd.getNumPartitions) {
      rdd.repartition(numPartitions)
    } else {
      rdd
    }
  }

  implicit class RTreeFunctionsForTuple[T: ClassTag](rdd: RDD[(Point, T)]) {
    def buildRTree(numPartitions:Int = -1):RTreeRDD[T] = {
      new RTreeRDD[T](new RTreeRDDImpl(repartitionRDDorNot(rdd,numPartitions)))
    }

    def buildRTreeWithRepartition(numPartitions: Int, sampleNum:Int = 10000):RTreeRDD[T] = {
      require(numPartitions > 0)
      rdd.cache()
      val samplePos = rdd.takeSample(withReplacement = false, sampleNum).map(_._1)
      val rddPartitioner = RTreePartitioner.create(samplePos, numPartitions)
      val shuffledRDD = new ShuffledRDD[Point, T, T](rdd, rddPartitioner)
      val rtreeImpl = new RTreeRDDImpl(shuffledRDD)
      new RTreeRDD[T](rtreeImpl)
    }
  }

  implicit class RTreeFunctionsForSingle[T: ClassTag](rdd: RDD[T]) {
    def buildRTree(f: T => Point, numPartitions:Int = -1):RTreeRDD[T] = {
      rdd.map(a => (f(a), a)).buildRTree(numPartitions)
    }
    def buildRTreeWithRepartition(f: T => Point, numPartitions: Int, sampleNum:Int = 10000):RTreeRDD[T] = {
      rdd.map(a => (f(a), a)).buildRTreeWithRepartition(numPartitions, sampleNum)
    }
  }

  implicit def toFunc1[A, B](a: A => B):Func1[A, B] = new Func1[A, B] with java.io.Serializable {
    override def call(t: A): B = a(t)
  }

  implicit class RTreeFunctionsForSparkContext(sc: SparkContext) {
    def rtreeFile[T : ClassTag](path:String, partitionPruned:Boolean = true): RTreeRDD[T] = {
      val paths = getActualSavePath(path)
      val rdd = new RTreeRDD[T](sc.objectFile(paths._1), partitionPruned)  //rtreeDataFile[T](paths._1, partitionPruned)
      val global = sc.objectFile[(MBR, Int)](paths._2).collect().sortBy(_._2).map(_._1)
      rdd.setPartitionRecs(global)
      rdd
    }
  }

  implicit class RTreeFunctionsForRTreeRDD[T: ClassTag](rdd: RDD[(RTree, Array[(Point, T)])]) {

    def getPartitionRecs:Array[MBR] = {
      val getPartitionMbr = (tc:TaskContext, iter:Iterator[(RTree, Array[(Point, T)])]) => {
        if(iter.hasNext) {
          val tree = iter.next()._1
          val mbr = tree.root.m_mbr
          Some((tc.partitionId(), mbr))
        } else {
          None
        }
      }
      val recArray = new Array[MBR](rdd.partitions.length)
      val resultHandler = (index: Int, rst:Option[(Int, MBR)]) => {
        rst match {
          case Some((idx, rec)) =>
            require(idx == index)
            recArray(index) = rec
          case None =>
        }
      }
      SparkContext.getOrCreate().runJob(rdd, getPartitionMbr, rdd.partitions.indices, resultHandler)
      recArray
    }
  }
}




private[spark] class RTreeRDD[T: ClassTag] (var prev: RDD[(RTree, Array[(Point, T)])], @transient var partitionPruned:Boolean = true)
  extends RDD[(Point, T)](prev) {

  //prev.cache()

  @transient
  private var _partitionRecs:Array[MBR] = null

  def setPartitionRecs(recs:Array[MBR]) = {
    require(recs.length == getNumPartitions)
    _partitionRecs = recs
  }

  def partitionRecs:Array[MBR] = {
    import RTreeRDD._
    if(_partitionRecs == null && partitionPruned) {
      _partitionRecs = prev.getPartitionRecs
      require(_partitionRecs.length == getNumPartitions)
    }
    _partitionRecs
  }

  def saveAsRTreeFile(path:String):Unit = {
    val paths = RTreeRDD.getActualSavePath(path)
    prev.saveAsObjectFile(paths._1)
    sparkContext.parallelize(partitionRecs.zipWithIndex).saveAsObjectFile(paths._2)
  }

  def search(r:MBR):RDD[(Point, T)] = {
    (if(partitionPruned) {
      prev.cache()
      PartitionPruningRDD.create(prev, idx => {
        if(partitionRecs(idx) == null)
          false
        else
          partitionRecs(idx).intersects(r)
      })
    } else {
      firstParent[(RTree, Array[(Point, T)])]
    }).mapPartitions(iter => {
      if (iter.hasNext) {
        new Iterator[(Point, T)](){
          val data = iter.next()
          val rstIter = data._1.range(r).iterator
          override def hasNext: Boolean = rstIter.hasNext
          override def next(): (Point, T) = data._2(rstIter.next()._2)
        }
      } else {
        Iterator()
      }
      Iterator()
    })
  }

  override def getPartitions: Array[Partition] = firstParent[(RTree, Array[(Point, T)])].partitions

  override def compute(split: Partition, context: TaskContext): Iterator[(Point, T)] = {
    val iter = firstParent[(RTree, Array[(Point, T)])].iterator(split, context)
    if (iter.hasNext) {
      iter.next()._2.iterator
    } else {
      Iterator()
    }
  }

  override def clearDependencies() {
    super.clearDependencies()
    prev = null
  }
}


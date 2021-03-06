package pr

import org.apache.parquet.hadoop.api.ReadSupport
import org.apache.parquet.hadoop.{ParquetFileReader, ParquetRecordReader}
import org.apache.parquet.schema.MessageType
import org.apache.parquet.hadoop.metadata.{FileMetaData, ParquetMetadata}

import org.apache.hadoop.fs.Path
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.mapred.FileSplit
import org.apache.hadoop.mapred.Reporter

import org.apache.spark._
import org.apache.spark.rdd._

import scala.reflect.ClassTag

class ParquetRDDPartition[T](val index: Int,
                             val readSupport: ReadSupport[T] with Serializable,
                             s: FileSplit,
                             c: Configuration)
    extends Partition {

  val split = new SerializableWritable[FileSplit](s)
  val conf  = new SerializableWritable[Configuration](c)
}

class RecordReaderIterator[T](prr: ParquetRecordReader[T]) extends Iterator[T] {
  override def hasNext: Boolean = prr.nextKeyValue()
  override def next(): T        = prr.getCurrentValue()
}

class ParquetRDD[T: ClassTag](
    @transient private val _sc: SparkContext,
    pathStr: String,
    @transient private val readSupport: ReadSupport[T] with Serializable
) extends RDD[T](_sc, Nil) {

  override def compute(raw: Partition, context: TaskContext): Iterator[T] = {
    val partition = raw.asInstanceOf[ParquetRDDPartition[T]]
    val prr       = new ParquetRecordReader(partition.readSupport)
    prr.initialize(partition.split.value, partition.conf.value, Reporter.NULL)
    new RecordReaderIterator(prr)
  }

  override protected def getPartitions: Array[Partition] = {
    val path       = new Path(pathStr)
    val conf       = _sc.hadoopConfiguration
    val fs         = path.getFileSystem(conf)
    val fileStatus = fs.getFileStatus(path)
    val blocks     = fs.getFileBlockLocations(fileStatus, 0, fileStatus.getLen())
    blocks.zipWithIndex.map {
      case (b, i) =>
        val split =
          new FileSplit(path, b.getOffset(), b.getLength(), b.getHosts())
        new ParquetRDDPartition(i, readSupport, split, conf)
    }
  }
}

object ParquetRDD {
  implicit class SparkContextOps(sc: SparkContext) {
    def parquet[T: ClassTag](
        pathStr: String,
        readSupport: ReadSupport[T] with Serializable
    ): ParquetRDD[T] =
      new ParquetRDD[T](sc, pathStr, readSupport)

    def parquet[T: ClassTag](
        pathStr: String,
        readSupport: MessageType => ReadSupport[T] with Serializable
    ): ParquetRDD[T] = {
      val path                       = new Path(pathStr)
      val conf                       = sc.hadoopConfiguration
      val fs                         = path.getFileSystem(conf)
      val fileStatus                 = fs.getFileStatus(path)
      val metaData: ParquetMetadata  = ParquetFileReader.readFooter(conf, fileStatus)
      val fileMetaData: FileMetaData = metaData.getFileMetaData()
      val schema                     = fileMetaData.getSchema()
      new ParquetRDD[T](sc, pathStr, readSupport(schema))
    }
  }
}

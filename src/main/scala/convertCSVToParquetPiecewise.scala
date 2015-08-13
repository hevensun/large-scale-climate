package org.apache.spark.climate
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkContext
import org.apache.spark.SparkConf
import org.apache.spark.storage.StorageLevel
import org.apache.spark.mllib.linalg.{Vector, DenseVector, SparseVector}
import org.apache.spark.mllib.linalg.distributed.{IndexedRow, IndexedRowMatrix}
import org.apache.spark.sql.{SQLContext, Row => SQLRow}
import java.util.Arrays
import java.util.Calendar
import java.text.SimpleDateFormat

object CSVToParquetPiecewise {

  def report(message: String, verbose: Boolean = true) = {
    val now = Calendar.getInstance().getTime()
    val formatter = new SimpleDateFormat("H:m:s")

    if(verbose) {
      println("STATUS REPORT (" + formatter.format(now) + "): " + message)
    }
  }

  def main(args: Array[String]) = {
    val conf= new SparkConf().setAppName("CSV to Parquet convertor").
                set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
    val sc = new SparkContext(conf)

    for (i <- 0 to 4) {
      convertCSVToScalaPiecewise(sc, args, i)
    }

    // load each of the pieces and join them

    val sqlctx = new SQLContext(sc)
    import sqlctx.implicits._

    val outpath = args(1)
    val colarray = new Array[RDD[(Long, DenseVector)]](5)
    for (idx <- 0 to 4) {
      colarray(idx) = {
        sqlctx.parquetFile(outpath + "/mat.parquet" + idx.toString).rdd.map {
          case SQLRow(index: Long, vector: DenseVector) => (index, vector)
        }
      }
    }

    val cols0and1 = colarray(0).join(colarray(1)).map(x => (x._1, new DenseVector(x._2._1.toArray ++ x._2._2.toArray)))
    val cols2and3 = colarray(2).join(colarray(3)).map(x => (x._1, new DenseVector(x._2._1.toArray ++ x._2._2.toArray)))
    val cols0through3 = cols0and1.join(cols2and3).map(x => (x._1, new DenseVector(x._2._1.toArray ++ x._2._2.toArray)))
    val allcols = cols0through3.join(colarray(4)).map(x => (x._1, new DenseVector(x._2._1.toArray ++ x._2._2.toArray)))
    
    val finalcols = allcols.map(x => new IndexedRow(x._1, x._2)).toDF

    //report(s"The dataframe contains ${finalcols.rdd.count} cols before writing")

    finalcols.saveAsParquetFile(outpath + "/finalmat.parquet")
  }

  def convertCSVToScalaPiecewise(sc: SparkContext, args: Array[String], startdigit: Int) = {
    if(args.length != 2) {
      Console.err.println("Expected args: inpath outpath") 
      System.exit(1)
    }

    val sqlctx = new SQLContext(sc)
    import sqlctx.implicits._

    val valsinpath = args(0) + "/part-" + startdigit.toString + "*"
    val outpath = args(1)

    val valsrows = sc.textFile(valsinpath).repartition(2880).map(_.split(",")).map(x => (x(1).toInt, (x(0).toInt, x(2).toDouble))).
                      groupByKey.map(x => (x._1, x._2.toSeq.sortBy(_._1)))
//    report(s"The CSV dataset contains ${valsrows.count} rows")

    /* now the records look like (rownum, ( (colnum1, val1) , ... (colnumn, valn))), where the rownums correspond to the about 6 million observation locations and the colnums correspond to the about 46K observation times. Note that the colnums are in increasing order (corresponding to increasing date), but some dates were skipped due to corrupted data, so we should store precisely what these colnums are to facilitate mapping the EOFs back onto the correct times */
    val colordering = sc.textFile(valsinpath).repartition(2880).map(_.split(",")).map(_(0).toInt).distinct().sortBy(identity).collect()
    sc.parallelize(colordering).coalesce(1).saveAsTextFile(outpath + "/origcolindices" + startdigit.toString)

    val newValsRows = valsrows.map(x => {
      val values = x._2.map(y => y._2).toArray
      new IndexedRow(x._1, new DenseVector(values))
    }).toDF

 //   report(s"The dataframe contains ${newValsRows.rdd.count} rows before writing")

    newValsRows.saveAsParquetFile(outpath + "/mat.parquet" + startdigit.toString)
  }
}

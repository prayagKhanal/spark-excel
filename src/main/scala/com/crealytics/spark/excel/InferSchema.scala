/*
 * Copyright 2014 Databricks, 2016 crealytics
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.crealytics.spark.excel

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types._
import org.apache.poi.ss.usermodel.Cell
private[excel] object InferSchema {

  type CellType = Int
  /**
   * Similar to the JSON schema inference.
   * [[org.apache.spark.sql.execution.datasources.json.InferSchema]]
   *     1. Infer type of each row
   *     2. Merge row types to find common type
   *     3. Replace any null types with string type
   */
  def apply(
    rowsRDD: RDD[Seq[CellType]],
      header: Array[String]): StructType = {
    val startType: Array[DataType] = Array.fill[DataType](header.length)(NullType)
    val rootTypes: Array[DataType] = rowsRDD.aggregate(startType)(
      inferRowType _,
      mergeRowTypes)

    val structFields = header.zip(rootTypes).map { case (thisHeader, rootType) =>
      val dType = rootType match {
        case z: NullType => StringType
        case other => other
      }
      StructField(thisHeader, dType, nullable = true)
    }
    StructType(structFields)
  }

  private def inferRowType(rowSoFar: Array[DataType], next: Seq[CellType]): Array[DataType] = {
    var i = 0
    while (i < math.min(rowSoFar.length, next.size)) {  // May have columns on right missing.
      rowSoFar(i) = inferField(rowSoFar(i), next(i))
      i+=1
    }
    rowSoFar
  }

  private[excel] def mergeRowTypes(
      first: Array[DataType],
      second: Array[DataType]): Array[DataType] = {
    first.zipAll(second, NullType, NullType).map { case ((a, b)) =>
      findTightestCommonType(a, b).getOrElse(NullType)
    }
  }

  val SPARK_TYPE_FOR_EXCEL_CELL_TYPE = Map(
    Cell.CELL_TYPE_STRING -> StringType,
    Cell.CELL_TYPE_BOOLEAN -> BooleanType,
    Cell.CELL_TYPE_NUMERIC -> DoubleType
  )
  /**
   * Infer type of string field. Given known type Double, and a string "1", there is no
   * point checking if it is an Int, as the final type must be Double or higher.
   */
  private[excel] def inferField(
    typeSoFar: DataType,
    field: CellType): DataType = {
    // Defining a function to return the StringType constant is necessary in order to work around
    // a Scala compiler issue which leads to runtime incompatibilities with certain Spark versions;
    // see issue #128 for more details.
    def stringType(): DataType = {
      StringType
    }

    if (field == Cell.CELL_TYPE_BLANK) {
      typeSoFar
    } else {
      (typeSoFar, field) match {
        case (NullType, ct) => SPARK_TYPE_FOR_EXCEL_CELL_TYPE(ct)
        // case (IntegerType, _) => tryParseInteger(field)
        // case (LongType, _) => tryParseLong(field)
        case (DoubleType, Cell.CELL_TYPE_NUMERIC) => DoubleType
        case (BooleanType, Cell.CELL_TYPE_BOOLEAN) => BooleanType
        case (StringType, _) => stringType()
        case (_, _) => stringType()
      }
    }
  }

  /**
   * Copied from internal Spark api
   * [[org.apache.spark.sql.catalyst.analysis.HiveTypeCoercion]]
   */
  private val numericPrecedence: IndexedSeq[DataType] =
    IndexedSeq[DataType](
      ByteType,
      ShortType,
      IntegerType,
      LongType,
      FloatType,
      DoubleType,
      TimestampType)

  /**
   * Copied from internal Spark api
   * [[org.apache.spark.sql.catalyst.analysis.HiveTypeCoercion]]
   */
  val findTightestCommonType: (DataType, DataType) => Option[DataType] = {
    case (t1, t2) if t1 == t2 => Some(t1)
    case (NullType, t1) => Some(t1)
    case (t1, NullType) => Some(t1)
    case (StringType, t2) => Some(StringType)
    case (t1, StringType) => Some(StringType)

    // Promote numeric types to the highest of the two and all numeric types to unlimited decimal
    case (t1, t2) if Seq(t1, t2).forall(numericPrecedence.contains) =>
      val index = numericPrecedence.lastIndexWhere(t => t == t1 || t == t2)
      Some(numericPrecedence(index))

    case _ => None
  }
}

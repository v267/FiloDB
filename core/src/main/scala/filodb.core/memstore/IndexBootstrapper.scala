package filodb.core.memstore

import kamon.Kamon
import kamon.metric.MeasurementUnit
import monix.eval.Task
import monix.reactive.Observable

import filodb.core.DatasetRef
import filodb.core.binaryrecord2.RecordBuilder
import filodb.core.metadata.Schemas
import filodb.core.store.{ColumnStore, PartKeyRecord}

class IndexBootstrapper(colStore: ColumnStore) {

  /**
    * Bootstrap the lucene index for the shard
    * using PartKeyRecord objects read from some persistent source.
    *
    * The partId used in the lucene index is generated by invoking
    * the function provided on the threadpool requested.
    *
    * @param index the lucene index to populate
    * @param shardNum shard number
    * @param ref dataset ref
    * @param assignPartId the function to invoke to get the partitionId to be used to populate the index record
    * @return number of updated records
    */
  def bootstrapIndexRaw(index: PartKeyLuceneIndex,
                        shardNum: Int,
                        ref: DatasetRef)
                       (assignPartId: PartKeyRecord => Int): Task[Long] = {

    val recoverIndexLatency = Kamon.gauge("shard-recover-index-latency", MeasurementUnit.time.milliseconds)
      .withTag("dataset", ref.dataset)
      .withTag("shard", shardNum)
    val start = System.currentTimeMillis()
    colStore.scanPartKeys(ref, shardNum)
      .map { pk =>
        val partId = assignPartId(pk)
        index.addPartKey(pk.partKey, partId, pk.startTime, pk.endTime)()
      }
      .countL
      .map { count =>
        index.refreshReadersBlocking()
        recoverIndexLatency.update(System.currentTimeMillis() - start)
        count
      }
  }

  /**
   * Same as bootstrapIndexRaw, except that we parallelize lucene update for
   * faster bootstrap of large number of index entries in downsample cluster.
   * Not doing this in raw cluster since parallel TimeSeriesPartition
   * creation requires more careful contention analysis
   */
  def bootstrapIndexDownsample(index: PartKeyLuceneIndex,
                     shardNum: Int,
                     ref: DatasetRef,
                     ttlMs: Long)
                    (assignPartId: PartKeyRecord => Int): Task[Long] = {

    val recoverIndexLatency = Kamon.gauge("shard-recover-index-latency", MeasurementUnit.time.milliseconds)
      .withTag("dataset", ref.dataset)
      .withTag("shard", shardNum)
    val start = System.currentTimeMillis()
    colStore.scanPartKeys(ref, shardNum)
      .filter(_.endTime > start - ttlMs)
      .mapParallelUnordered(Runtime.getRuntime.availableProcessors()) { pk =>
        Task.evalAsync {
          val partId = assignPartId(pk)
          index.addPartKey(pk.partKey, partId, pk.startTime, pk.endTime)()
        }
      }
      .countL
      .map { count =>
        index.refreshReadersBlocking()
        recoverIndexLatency.update(System.currentTimeMillis() - start)
        count
      }
  }

  /**
    * Refresh index with real-time data rom colStore's raw dataset
    * @param fromHour fromHour inclusive
    * @param toHour toHour inclusive
    * @param parallelism number of threads to use to concurrently load the index
    * @param lookUpOrAssignPartId function to invoke to assign (or lookup) partId to the partKey
    *
    * @return number of records refreshed
    */
  def refreshWithDownsamplePartKeys(
                   index: PartKeyLuceneIndex,
                   shardNum: Int,
                   ref: DatasetRef,
                   fromHour: Long,
                   toHour: Long,
                   schemas: Schemas,
                   parallelism: Int = Runtime.getRuntime.availableProcessors())
                   (lookUpOrAssignPartId: Array[Byte] => Int): Task[Long] = {
    val recoverIndexLatency = Kamon.gauge("downsample-store-refresh-index-latency",
      MeasurementUnit.time.milliseconds)
      .withTag("dataset", ref.dataset)
      .withTag("shard", shardNum)
    val start = System.currentTimeMillis()
    Observable.fromIterable(fromHour to toHour).flatMap { hour =>
      colStore.getPartKeysByUpdateHour(ref, shardNum, hour)
    }.mapParallelUnordered(parallelism) { pk =>
      // Same PK can be updated multiple times, but they wont be close for order to matter.
      // Hence using mapParallelUnordered
      Task.evalAsync {
        val downsamplPartKey = RecordBuilder.buildDownsamplePartKey(pk.partKey, schemas)
        downsamplPartKey.foreach { dpk =>
          val partId = lookUpOrAssignPartId(dpk)
          index.upsertPartKey(dpk, partId, pk.startTime, pk.endTime)()
        }
      }
     }
     .countL
     .map { count =>
       index.refreshReadersBlocking()
       recoverIndexLatency.update(System.currentTimeMillis() - start)
       count
     }
  }

}


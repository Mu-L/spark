/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.streaming.state

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, File, IOException, ObjectInputStream, ObjectOutputStream}
import java.net.URI
import java.util
import java.util.UUID
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Random

import org.apache.commons.io.FileUtils
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs._
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.scalatest.{BeforeAndAfter, PrivateMethodTester}
import org.scalatest.concurrent.Eventually._
import org.scalatest.time.SpanSugar._

import org.apache.spark._
import org.apache.spark.LocalSparkContext._
import org.apache.spark.internal.Logging
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.{GenericInternalRow, UnsafeProjection, UnsafeRow}
import org.apache.spark.sql.catalyst.util.quietly
import org.apache.spark.sql.execution.streaming._
import org.apache.spark.sql.execution.streaming.state.StateStoreCoordinatorSuite.withCoordinatorRef
import org.apache.spark.sql.functions.count
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._
import org.apache.spark.tags.ExtendedSQLTest
import org.apache.spark.unsafe.types.UTF8String
import org.apache.spark.util.Utils

/**
 * A test StateStoreProvider implementation that controls maintenance execution
 * timing using a CountDownLatch to simulate concurrent maintenance scenarios.
 *
 * This provider is used to test the scenario where a task thread attempts to
 * unload a provider via maintenance while it's already being processed by a
 * maintenance thread. This tests the awaitProcessThisPartition functionality
 * that ensures proper synchronization in StateStore's maintenance thread pool.
 */
class SignalingStateStoreProvider extends StateStoreProvider with Logging {
  import SignalingStateStoreProvider._
  private var id: StateStoreId = null

  override def init(
      stateStoreId: StateStoreId,
      keySchema: StructType,
      valueSchema: StructType,
      keyStateEncoderSpec: KeyStateEncoderSpec,
      useColumnFamilies: Boolean,
      storeConfs: StateStoreConf,
      hadoopConf: Configuration,
      useMultipleValuesPerKey: Boolean = false,
      stateSchemaProvider: Option[StateSchemaProvider] = None): Unit = {
    id = stateStoreId
  }

  override def stateStoreId: StateStoreId = id

  /**
   * Records which thread called close() to verify that only maintenance threads close providers
   */
  override def close(): Unit = {
    closeThreadName = Thread.currentThread.getName
  }

  /**
   * This test implementation doesn't need to provide an actual store
   */
  override def getStore(
      version: Long,
      uniqueId: Option[String]): StateStore = null

  /**
   * Simulates a maintenance operation that blocks until a signal is received.
   * This allows testing the scenario where a provider is already under maintenance
   * when a task thread tries to trigger another maintenance operation on it.
   */
  override def doMaintenance(): Unit = {
    maintenanceStarted = true
    logInfo(s"Maintenance started on thread: ${Thread.currentThread().getName}")

    // Block until the test signals to continue
    continueSignal.await()

    logInfo(s"Maintenance continuing after signal on thread: ${Thread.currentThread().getName}")
  }
}

/**
 * Companion object that tracks state and provides synchronization primitives
 * for testing concurrent maintenance scenarios
 */
object SignalingStateStoreProvider extends Logging {
  // For tracking state across threads
  var maintenanceStarted: Boolean = false
  var taskSubmittedMaintenance: Boolean = false
  var closeThreadName: String = ""

  // Added for queue testing
  var providerWasQueued: Boolean = false

  // For coordination between threads
  var continueSignal = new CountDownLatch(1)
  val maintenanceStartedLatch = new CountDownLatch(1)
  val taskAttemptCompletedLatch = new CountDownLatch(1)

  /**
   * Resets all test state between test runs
   */
  def reset(): Unit = {
    maintenanceStarted = false
    taskSubmittedMaintenance = false
    closeThreadName = ""

    // Reset the latch to ensure maintenance will block again
    try {
      continueSignal = new CountDownLatch(1)
    } catch {
      case e: Exception =>
        logError(s"Error resetting latch: ${e.getMessage}")
    }
  }
}

class FakeStateStoreProviderTracksCloseThread extends StateStoreProvider {
  import FakeStateStoreProviderTracksCloseThread._
  private var id: StateStoreId = null

  override def init(
      stateStoreId: StateStoreId,
      keySchema: StructType,
      valueSchema: StructType,
      keyStateEncoderSpec: KeyStateEncoderSpec,
      useColumnFamilies: Boolean,
      storeConfs: StateStoreConf,
      hadoopConf: Configuration,
      useMultipleValuesPerKey: Boolean = false,
      stateSchemaProvider: Option[StateSchemaProvider] = None): Unit = {
    id = stateStoreId
  }

  override def stateStoreId: StateStoreId = id

  override def close(): Unit = {
    closeThreadNames = Thread.currentThread.getName :: closeThreadNames
  }

  override def getStore(
      version: Long,
      uniqueId: Option[String]): StateStore = null

  override def doMaintenance(): Unit = {}
}

private object FakeStateStoreProviderTracksCloseThread {
  var closeThreadNames: List[String] = Nil
}

// MaintenanceErrorOnCertainPartitionsProvider is a test-only provider that throws an
// exception during maintenance for partitions 0 and 1 (these are arbitrary choices). It is
// used to test that an exception in a single provider's maintenance does not affect other
// providers that do not experience exceptions.
class MaintenanceErrorOnCertainPartitionsProvider extends HDFSBackedStateStoreProvider {
  private var id: StateStoreId = null

  override def init(
      stateStoreId: StateStoreId,
      keySchema: StructType,
      valueSchema: StructType,
      keyStateEncoderSpec: KeyStateEncoderSpec,
      useColumnFamilies: Boolean,
      storeConfs: StateStoreConf,
      hadoopConf: Configuration,
      useMultipleValuesPerKey: Boolean = false,
      stateSchemaProvider: Option[StateSchemaProvider] = None): Unit = {
    id = stateStoreId

    super.init(
      stateStoreId,
      keySchema, valueSchema, keyStateEncoderSpec, useColumnFamilies,
      storeConfs, hadoopConf, useMultipleValuesPerKey)
  }

  override def doMaintenance(): Unit = {
    if (id.partitionId == 0 || id.partitionId == 1) {
      throw new RuntimeException("Intentional maintenance failure")
    }
    super.doMaintenance()
  }
}

class FakeStateStoreProviderWithMaintenanceError extends StateStoreProvider {
  import FakeStateStoreProviderWithMaintenanceError._
  private var id: StateStoreId = null

  private val exceptionHandler = new Thread.UncaughtExceptionHandler() {
    override def uncaughtException(t: Thread, e: Throwable): Unit = {
      errorOnMaintenance.set(true)
    }
  }

  override def init(
      stateStoreId: StateStoreId,
      keySchema: StructType,
      valueSchema: StructType,
      keyStateEncoderSpec: KeyStateEncoderSpec,
      useColumnFamilies: Boolean,
      storeConfs: StateStoreConf,
      hadoopConf: Configuration,
      useMultipleValuesPerKey: Boolean = false,
      stateSchemaProvider: Option[StateSchemaProvider] = None): Unit = {
    id = stateStoreId
  }

  override def stateStoreId: StateStoreId = id

  override def close(): Unit = {}

  override def getStore(version: Long, uniqueId: Option[String]): StateStore = null

  override def doMaintenance(): Unit = {
    Thread.currentThread.setUncaughtExceptionHandler(exceptionHandler)
    throw new RuntimeException("Intentional maintenance failure")
  }
}

private object FakeStateStoreProviderWithMaintenanceError {
  val errorOnMaintenance = new AtomicBoolean(false)
}

@ExtendedSQLTest
class StateStoreSuite extends StateStoreSuiteBase[HDFSBackedStateStoreProvider]
  with BeforeAndAfter {
  import StateStoreTestsHelper._
  import StateStoreCoordinatorSuite._

  before {
    StateStore.stop()
    require(!StateStore.isMaintenanceRunning)
  }

  after {
    StateStore.stop()
    require(!StateStore.isMaintenanceRunning)
  }

  test("SPARK-51596: submitMaintenanceWorkForProvider from task thread adds" +
    " to queue when timeout occurs") {
    // Reset tracking variables for a clean test
    SignalingStateStoreProvider.reset()

    val sqlConf = getDefaultSQLConf(
      SQLConf.STATE_STORE_MIN_DELTAS_FOR_SNAPSHOT.defaultValue.get,
      SQLConf.MAX_BATCHES_TO_RETAIN_IN_MEMORY.defaultValue.get
    )

    // Critical: Set a very short timeout to ensure awaitProcessThisPartition fails quickly
    sqlConf.setConf(SQLConf.STATE_STORE_MAINTENANCE_PROCESSING_TIMEOUT, 1L) // 1 second

    // Maintenance interval large enough that we control timing manually
    sqlConf.setConf(SQLConf.STREAMING_MAINTENANCE_INTERVAL, 30000L)
    sqlConf.setConf(SQLConf.NUM_STATE_STORE_MAINTENANCE_THREADS, 4)

    // Use our test provider
    sqlConf.setConf(
      SQLConf.STATE_STORE_PROVIDER_CLASS,
      classOf[SignalingStateStoreProvider].getName
    )

    val conf = new SparkConf().setMaster("local").setAppName("test")

    withSpark(SparkContext.getOrCreate(conf)) { sc =>
      withCoordinatorRef(sc) { _ =>
        val rootLocation = s"${Utils.createTempDir().getAbsolutePath}/spark-51596-timeout-queue"
        val providerId = StateStoreProviderId(StateStoreId(rootLocation, 0, 0), UUID.randomUUID)

        // Load the provider to start the maintenance system
        StateStore.get(
          providerId,
          keySchema, valueSchema, NoPrefixKeyStateEncoderSpec(keySchema),
          0, None, None, useColumnFamilies = false,
          new StateStoreConf(sqlConf), new Configuration()
        )

        // Access the queue via reflection for verification
        val queueField = PrivateMethod[ConcurrentLinkedQueue[
          (StateStoreProviderId, StateStoreProvider)]](
          Symbol("unloadedProvidersToClose"))
        val queue = StateStore invokePrivate queueField()
        assert(queue.isEmpty, "Queue should start empty")

        // Manually trigger maintenance which will block
        val maintenanceMethod = PrivateMethod[Unit](Symbol("doMaintenance"))
        StateStore invokePrivate maintenanceMethod()

        // Wait for maintenance to start
        eventually(timeout(5.seconds)) {
          assert(SignalingStateStoreProvider.maintenanceStarted)
          assert(StateStore.isLoaded(providerId))
        }

        // Now get access to the provider to simulate a task thread
        val loadedProvidersField = PrivateMethod[
          mutable.HashMap[StateStoreProviderId, StateStoreProvider]](
          Symbol("loadedProviders"))
        val loadedProviders = StateStore invokePrivate loadedProvidersField()
        val provider = loadedProviders.synchronized { loadedProviders.get(providerId).get }
        val maintenancePartitionsField = PrivateMethod[
          mutable.HashSet[StateStoreProviderId]](
          Symbol("maintenancePartitions"))
        val maintenancePartitions = StateStore invokePrivate maintenancePartitionsField()

        // Create a task thread that will attempt to submit maintenance
        val taskThread = new Thread(() => {
          try {
            // Call submitMaintenanceWorkForProvider directly since that's what we're testing
            val submitMaintenanceMethod = PrivateMethod[Unit](
              Symbol("submitMaintenanceWorkForProvider"))
            StateStore invokePrivate submitMaintenanceMethod(
              providerId, provider, new StateStoreConf(sqlConf),
              MaintenanceTaskType.FromTaskThread)

            SignalingStateStoreProvider.taskSubmittedMaintenance = true
            SignalingStateStoreProvider.taskAttemptCompletedLatch.countDown()
          } catch {
            case e: Exception =>
              logError(s"Error in task thread: ${e.getMessage}", e)
          }
        })

        // Start the task thread - it should timeout and add provider to queue
        taskThread.start()

        // Wait for task attempt to complete
        assert(SignalingStateStoreProvider
          .taskAttemptCompletedLatch.await(10, TimeUnit.SECONDS),
          "Task thread didn't complete")

        // Critical verification: After timeout, the provider should be in the queue
        eventually(timeout(5.seconds)) {
          assert(queue.size() == 1, "Provider should be queued after timeout")
        }
        val (queuedId, _) = queue.peek()
        assert(queuedId == providerId, "Queued provider has wrong ID")

        // Now allow the first maintenance to complete
        SignalingStateStoreProvider.continueSignal.countDown()

        eventually(timeout(5.seconds)) {
          assert(maintenancePartitions.isEmpty,
            "Maintenance partitions should be removed from")
        }
        // Manually trigger another maintenance to process the queue
        StateStore invokePrivate maintenanceMethod()

        // Verify the queue eventually gets processed
        eventually(timeout(5.seconds)) {
          assert(queue.isEmpty, "Queue should be emptied after maintenance")
        }
      }
    }
  }

  test("SPARK-51596: queued maintenance tasks get processed when lock is available") {
    // Reset tracking variables for a clean test
    SignalingStateStoreProvider.reset()

    val sqlConf = getDefaultSQLConf(
      SQLConf.STATE_STORE_MIN_DELTAS_FOR_SNAPSHOT.defaultValue.get,
      SQLConf.MAX_BATCHES_TO_RETAIN_IN_MEMORY.defaultValue.get
    )
    // Use a maintenance interval large enough that we control timing explicitly
    sqlConf.setConf(SQLConf.STREAMING_MAINTENANCE_INTERVAL, 30000L)
    // Set our special provider class that lets us control maintenance timing
    sqlConf.setConf(
      SQLConf.STATE_STORE_PROVIDER_CLASS,
      classOf[SignalingStateStoreProvider].getName
    )

    val conf = new SparkConf().setMaster("local").setAppName("test")

    withSpark(SparkContext.getOrCreate(conf)) { sc =>
      withCoordinatorRef(sc) { coordinatorRef =>
        val rootLocation = s"${Utils.createTempDir().getAbsolutePath}/spark-51596-queue"

        // Create two providers that we'll use for the test
        val provider1Id =
          StateStoreProviderId(StateStoreId(rootLocation, 0, 0), UUID.randomUUID)
        val provider2Id =
          StateStoreProviderId(StateStoreId(rootLocation, 0, 1), UUID.randomUUID)

        // Get the first provider to load it
        StateStore.get(
          provider1Id,
          keySchema, valueSchema, NoPrefixKeyStateEncoderSpec(keySchema),
          0, None, None, useColumnFamilies = false,
          new StateStoreConf(sqlConf), new Configuration()
        )

        // Manually trigger maintenance for provider1, which will block in doMaintenance()
        val maintenanceMethod = PrivateMethod[Unit](Symbol("doMaintenance"))
        StateStore invokePrivate maintenanceMethod()

        // Wait for maintenance to start before continuing
        eventually(timeout(5.seconds)) {
          assert(SignalingStateStoreProvider.maintenanceStarted)
          assert(StateStore.isLoaded(provider1Id))
        }

        // Now make the first provider "stale" by reporting it active on another executor
        coordinatorRef.reportActiveInstance(provider1Id, "otherhost", "otherexec", Seq.empty)

        // Get provider2 which will cause a maintenance task for provider1 to be queued
        // (since provider1 is already under maintenance and can't be processed immediately)
        StateStore.get(
          provider2Id,
          keySchema, valueSchema, NoPrefixKeyStateEncoderSpec(keySchema),
          0, None, None, useColumnFamilies = false,
          new StateStoreConf(sqlConf), new Configuration()
        )

        // Mark that task submitted maintenance
        SignalingStateStoreProvider.taskSubmittedMaintenance = true

        // Unblock the first maintenance operation
        SignalingStateStoreProvider.continueSignal.countDown()

        // Verify that provider1 is eventually unloaded by the maintenance thread
        // after the first maintenance completes and the queued maintenance runs
        eventually(timeout(5.seconds)) {
          // Provider1 should be unloaded
          assert(!StateStore.isLoaded(provider1Id))
          // Provider2 should still be loaded
          assert(StateStore.isLoaded(provider2Id))
          // Close should have been called on a maintenance thread
          assert(SignalingStateStoreProvider.closeThreadName.contains("maintenance"))
        }

        // Get the partitionsForMaintenance field to check the queue is empty
        val partitionsField = PrivateMethod[
          ConcurrentLinkedQueue[StateStoreProviderId]](Symbol("unloadedProvidersToClose"))
        val queue = StateStore invokePrivate partitionsField()
        assert(queue.isEmpty, "Maintenance queue should be empty after processing queued tasks")
      }
    }
  }

  test("SPARK-51596: unloading only occurs on maintenance thread but occurs promptly") {
    // Reset closeThreadNames
    FakeStateStoreProviderTracksCloseThread.closeThreadNames = Nil

    val sqlConf = getDefaultSQLConf(
      SQLConf.STATE_STORE_MIN_DELTAS_FOR_SNAPSHOT.defaultValue.get,
      SQLConf.MAX_BATCHES_TO_RETAIN_IN_MEMORY.defaultValue.get
    )
    // Make maintenance interval very large (30s) so that task thread runs before maintenance.
    sqlConf.setConf(SQLConf.STREAMING_MAINTENANCE_INTERVAL, 30000L)
    // Use the `FakeStateStoreProviderTracksCloseThread` to run the test
    sqlConf.setConf(
      SQLConf.STATE_STORE_PROVIDER_CLASS,
      classOf[FakeStateStoreProviderTracksCloseThread].getName
    )

    val conf = new SparkConf().setMaster("local").setAppName("test")

    withSpark(SparkContext.getOrCreate(conf)) { sc =>
      withCoordinatorRef(sc) { coordinatorRef =>
        val rootLocation = s"${Utils.createTempDir().getAbsolutePath}/spark-51596"
        val providerId =
          StateStoreProviderId(StateStoreId(rootLocation, 0, 0), UUID.randomUUID)
        val providerId2 =
          StateStoreProviderId(StateStoreId(rootLocation, 0, 1), UUID.randomUUID)

        // Create provider to start the maintenance task + pool
        StateStore.get(
          providerId,
          keySchema, valueSchema, NoPrefixKeyStateEncoderSpec(keySchema),
          0, None, None, useColumnFamilies = false, new StateStoreConf(sqlConf), new Configuration()
        )

        // Report instance active on another executor
        coordinatorRef.reportActiveInstance(providerId, "otherhost", "otherexec", Seq.empty)

        // Load another provider to trigger task unload
        StateStore.get(
          providerId2,
          keySchema, valueSchema, NoPrefixKeyStateEncoderSpec(keySchema),
          0, None, None, useColumnFamilies = false, new StateStoreConf(sqlConf), new Configuration()
        )

        // Wait for close to occur. Timeout is less than maintenance interval,
        // so should only close by task triggering.
        eventually(timeout(5.seconds)) {
          assert(FakeStateStoreProviderTracksCloseThread.closeThreadNames.size == 1)
          FakeStateStoreProviderTracksCloseThread.closeThreadNames.foreach { name =>
            assert(name.contains("state-store-maintenance-thread"))}
        }
      }
    }
  }


  test("retaining only two latest versions when MAX_BATCHES_TO_RETAIN_IN_MEMORY set to 2") {
    tryWithProviderResource(
      newStoreProvider(minDeltasForSnapshot = 10, numOfVersToRetainInMemory = 2)) { provider =>

      var currentVersion = 0

      // commit the ver 1 : cache will have one element
      currentVersion = incrementVersion(provider, currentVersion)
      assert(getLatestData(provider, useColumnFamilies = false) === Set(("a", 0) -> 1))
      var loadedMaps = provider.getLoadedMaps()
      checkLoadedVersions(loadedMaps, count = 1, earliestKey = 1, latestKey = 1)
      checkVersion(loadedMaps, 1, Map(("a", 0) -> 1))

      // commit the ver 2 : cache will have two elements
      currentVersion = incrementVersion(provider, currentVersion)
      assert(getLatestData(provider, useColumnFamilies = false) === Set(("a", 0) -> 2))
      loadedMaps = provider.getLoadedMaps()
      checkLoadedVersions(loadedMaps, count = 2, earliestKey = 2, latestKey = 1)
      checkVersion(loadedMaps, 2, Map(("a", 0) -> 2))
      checkVersion(loadedMaps, 1, Map(("a", 0) -> 1))

      // commit the ver 3 : cache has already two elements and adding ver 3 incurs exceeding cache,
      // and ver 3 will be added but ver 1 will be evicted
      currentVersion = incrementVersion(provider, currentVersion)
      assert(getLatestData(provider, useColumnFamilies = false) === Set(("a", 0) -> 3))
      loadedMaps = provider.getLoadedMaps()
      checkLoadedVersions(loadedMaps, count = 2, earliestKey = 3, latestKey = 2)
      checkVersion(loadedMaps, 3, Map(("a", 0) -> 3))
      checkVersion(loadedMaps, 2, Map(("a", 0) -> 2))
    }
  }

  private def verifyStoreOperationUnsupported(operationName: String)(testFn: => Unit): Unit = {
    if (operationName != "merge") {
      val ex = intercept[SparkUnsupportedOperationException] {
        testFn
      }
      checkError(
        ex,
        condition = "UNSUPPORTED_FEATURE.STATE_STORE_MULTIPLE_COLUMN_FAMILIES",
        parameters = Map(
          "stateStoreProvider" -> "HDFSBackedStateStoreProvider"
        ),
        matchPVals = true
      )
    } else {
      val ex = intercept[SparkUnsupportedOperationException] {
        testFn
      }
      checkError(
        ex,
        condition = "STATE_STORE_UNSUPPORTED_OPERATION",
        parameters = Map(
          "operationType" -> operationName,
          "entity" -> "HDFSBackedStateStoreProvider"
        ),
        matchPVals = true
      )

    }
  }

  test("get, put, remove etc operations on non-default col family should fail") {
    tryWithProviderResource(newStoreProvider(opId = Random.nextInt(), partition = 0,
      minDeltasForSnapshot = 5)) { provider =>
      val store = provider.getStore(0)
      try {
        val keyRow = dataToKeyRow("a", 0)
        val valueRow = dataToValueRow(1)
        val colFamilyName = "test"
        verifyStoreOperationUnsupported("put") {
          store.put(keyRow, valueRow, colFamilyName)
        }

        verifyStoreOperationUnsupported("remove") {
          store.remove(keyRow, colFamilyName)
        }

        verifyStoreOperationUnsupported("get") {
          store.get(keyRow, colFamilyName)
        }

        verifyStoreOperationUnsupported("merge") {
          store.merge(keyRow, valueRow, colFamilyName)
        }

        verifyStoreOperationUnsupported("iterator") {
          store.iterator(colFamilyName)
        }

        verifyStoreOperationUnsupported("prefixScan") {
          store.prefixScan(keyRow, colFamilyName)
        }
      } finally {
        if (!store.hasCommitted) store.abort()
      }
    }
  }

  test("running with range scan encoder should fail") {
    val ex = intercept[SparkUnsupportedOperationException] {
      tryWithProviderResource(newStoreProvider(keySchemaWithRangeScan,
        keyStateEncoderSpec = RangeKeyScanStateEncoderSpec(keySchemaWithRangeScan, Seq(0)),
        useColumnFamilies = false)) { provider =>
        provider.getStore(0)
      }
    }
    checkError(
      ex,
      condition = "STATE_STORE_UNSUPPORTED_OPERATION",
      parameters = Map(
        "operationType" -> "Range scan",
        "entity" -> "HDFSBackedStateStoreProvider"
      ),
      matchPVals = true
    )
  }

  test("failure after committing with MAX_BATCHES_TO_RETAIN_IN_MEMORY set to 1") {
    tryWithProviderResource(newStoreProvider(opId = Random.nextInt(), partition = 0,
      numOfVersToRetainInMemory = 1)) { provider =>

      var currentVersion = 0

      // commit the ver 1 : cache will have one element
      currentVersion = incrementVersion(provider, currentVersion)
      assert(getLatestData(provider, useColumnFamilies = false) === Set(("a", 0) -> 1))
      var loadedMaps = provider.getLoadedMaps()
      checkLoadedVersions(loadedMaps, count = 1, earliestKey = 1, latestKey = 1)
      checkVersion(loadedMaps, 1, Map(("a", 0) -> 1))

      // commit the ver 2 : cache has already one elements and adding ver 2 incurs exceeding cache,
      // and ver 2 will be added but ver 1 will be evicted
      // this fact ensures cache miss will occur when this partition succeeds commit
      // but there's a failure afterwards so have to reprocess previous batch
      currentVersion = incrementVersion(provider, currentVersion)
      assert(getLatestData(provider, useColumnFamilies = false) === Set(("a", 0) -> 2))
      loadedMaps = provider.getLoadedMaps()
      checkLoadedVersions(loadedMaps, count = 1, earliestKey = 2, latestKey = 2)
      checkVersion(loadedMaps, 2, Map(("a", 0) -> 2))

      // suppose there has been failure after committing, and it decided to reprocess previous batch
      currentVersion = 1

      // committing to existing version which is committed partially but abandoned globally
      val store = provider.getStore(currentVersion)
      // negative value to represent reprocessing
      put(store, "a", 0, -2)
      store.commit()
      currentVersion += 1

      // make sure newly committed version is reflected to the cache (overwritten)
      assert(getLatestData(provider, useColumnFamilies = false) === Set(("a", 0) -> -2))
      loadedMaps = provider.getLoadedMaps()
      checkLoadedVersions(loadedMaps, count = 1, earliestKey = 2, latestKey = 2)
      checkVersion(loadedMaps, 2, Map(("a", 0) -> -2))
    }
  }

  test("no cache data with MAX_BATCHES_TO_RETAIN_IN_MEMORY set to 0") {
    tryWithProviderResource(newStoreProvider(opId = Random.nextInt(), partition = 0,
      numOfVersToRetainInMemory = 0)) { provider =>

      var currentVersion = 0

      // commit the ver 1 : never cached
      currentVersion = incrementVersion(provider, currentVersion)
      assert(getLatestData(provider, useColumnFamilies = false) === Set(("a", 0) -> 1))
      var loadedMaps = provider.getLoadedMaps()
      assert(loadedMaps.size() === 0)

      // commit the ver 2 : never cached
      currentVersion = incrementVersion(provider, currentVersion)
      assert(getLatestData(provider, useColumnFamilies = false) === Set(("a", 0) -> 2))
      loadedMaps = provider.getLoadedMaps()
      assert(loadedMaps.size() === 0)
    }
  }

  test("cleaning") {
    tryWithProviderResource(newStoreProvider(opId = Random.nextInt(), partition = 0,
      minDeltasForSnapshot = 5)) { provider =>

      for (i <- 1 to 20) {
        val store = provider.getStore(i - 1)
        put(store, "a", 0, i)
        store.commit()
        provider.doMaintenance() // do cleanup
      }
      require(
        rowPairsToDataSet(provider.latestIterator()) === Set(("a", 0) -> 20),
        "store not updated correctly")

      assert(!fileExists(provider, version = 1, isSnapshot = false)) // first file should be deleted

      // last couple of versions should be retrievable
      assert(getData(provider, 20, useColumnFamilies = false) === Set(("a", 0) -> 20))
      assert(getData(provider, 19, useColumnFamilies = false) === Set(("a", 0) -> 19))
    }
  }

  testQuietly("SPARK-19677: Committing a delta file atop an existing one should not fail on HDFS") {
    val conf = new Configuration()
    conf.set("fs.fake.impl", classOf[RenameLikeHDFSFileSystem].getName)
    conf.set("fs.defaultFS", "fake:///")

    tryWithProviderResource(
      newStoreProvider(opId = Random.nextInt(), partition = 0, hadoopConf = conf)) { provider =>

      provider.getStore(0).commit()
      provider.getStore(0).commit()

      // Verify we don't leak temp files
      val tempFiles = FileUtils.listFiles(new File(provider.stateStoreId.checkpointRootLocation),
        null, true).asScala.filter(_.getName.startsWith("temp-"))
      assert(tempFiles.isEmpty)
    }
  }

  test("empty or missing file handling") {
    tryWithProviderResource(newStoreProvider(opId = Random.nextInt(), partition = 0,
      minDeltasForSnapshot = 5)) { provider =>

      for (i <- 1 to 6) {
        val store = provider.getStore(i - 1)
        put(store, "a", 0, i)
        store.commit()
        provider.doMaintenance() // do cleanup
      }
      val snapshotVersion = (0 to 10).find { version =>
        fileExists(provider, version, isSnapshot = true)
      }.getOrElse(fail("snapshot file not found"))

      // Corrupt snapshot file and verify that it throws error
      assert(getData(provider, snapshotVersion,
        useColumnFamilies = false) === Set(("a", 0) -> snapshotVersion))
      emptyFile(provider, snapshotVersion, isSnapshot = true)
      var e = intercept[SparkException] {
        getData(provider, snapshotVersion, useColumnFamilies = false)
      }
      checkError(
        e,
        condition = "CANNOT_LOAD_STATE_STORE.UNCATEGORIZED",
        parameters = Map.empty
      )

      // Corrupt delta file and verify that it throws error
      assert(getData(provider, snapshotVersion - 1) === Set(("a", 0) -> (snapshotVersion - 1)))
      emptyFile(provider, snapshotVersion - 1, isSnapshot = false)
      e = intercept[SparkException] {
        getData(provider, snapshotVersion - 1)
      }
      checkError(
        e,
        condition = "CANNOT_LOAD_STATE_STORE.UNCATEGORIZED",
        parameters = Map.empty
      )

      // Delete delta file and verify that it throws error
      deleteFilesEarlierThanVersion(provider, snapshotVersion)
      e = intercept[SparkException] {
        getData(provider, snapshotVersion - 1)
      }
      checkError(
        e,
        condition = "CANNOT_LOAD_STATE_STORE.CANNOT_READ_DELTA_FILE_NOT_EXISTS",
        parameters = Map(
          "fileToRead" -> s"${provider.stateStoreId.storeCheckpointLocation()}/1.delta",
          "clazz" -> "HDFSStateStoreProvider\\[.+\\]"
        ),
        matchPVals = true
      )
    }
  }

  test("SPARK-51291: corrupted file handling") {
    tryWithProviderResource(newStoreProvider(opId = Random.nextInt(), partition = 0,
      minDeltasForSnapshot = 5)) { provider =>

      for (i <- 1 to 6) {
        val store = provider.getStore(i - 1)
        put(store, "a", 0, i)
        store.commit()
        provider.doMaintenance() // do cleanup
      }
      val snapshotVersion = (0 to 10).find { version =>
        fileExists(provider, version, isSnapshot = true)
      }.getOrElse(fail("snapshot file not found"))

      assert(getData(provider, snapshotVersion - 1) === Set(("a", 0) -> (snapshotVersion - 1)))

      // Corrupt delta file and verify that it throws error
      val method = PrivateMethod[Path](Symbol("baseDir"))
      val basePath = provider invokePrivate method()
      // corrupt 1.delta since we only validate the first file
      val path = new Path(basePath.toString, "1.delta")
      val byteArray = new Array[Byte](60)
      provider.decompressStream(provider.fm.open(path)).readFully(byteArray)
      byteArray(4) = -1 // Flip byte to -1 to corrupt the file and
                        // trigger key row fmt validation error
      val outputStream = provider.compressStream(provider.fm.createAtomic(
        path, overwriteIfPossible = true))
      outputStream.write(byteArray)
      outputStream.close()

      val e = intercept[SparkException] {
        getData(provider, snapshotVersion - 1)
      }
      assert(e.getCondition == "CANNOT_LOAD_STATE_STORE.KEY_ROW_FORMAT_VALIDATION_FAILURE")
    }
  }

  test("reports memory usage on current version") {
    def getSizeOfStateForCurrentVersion(metrics: StateStoreMetrics): Long = {
      val metricPair = metrics.customMetrics.find(_._1.name == "stateOnCurrentVersionSizeBytes")
      assert(metricPair.isDefined)
      metricPair.get._2
    }

    tryWithProviderResource(newStoreProvider()) { provider =>
      val store = provider.getStore(0)
      val noDataMemoryUsed = getSizeOfStateForCurrentVersion(store.metrics)

      put(store, "a", 0, 1)
      store.commit()
      assert(getSizeOfStateForCurrentVersion(store.metrics) > noDataMemoryUsed)
    }
  }

  test("SPARK-48105: state store unload/close happens during the maintenance") {
    tryWithProviderResource(
      newStoreProvider(opId = Random.nextInt(), partition = 0, minDeltasForSnapshot = 1)) {
      provider =>
        val store = provider.getStore(0).asInstanceOf[provider.HDFSBackedStateStore]
        val values = (1 to 20)
        val keys = values.map(i => ("a" + i))
        keys.zip(values).map{case (k, v) => put(store, k, 0, v)}
        // commit state store with 20 keys.
        store.commit()
        // get the state store iterator: mimic the case which the iterator is hold in the
        // maintenance thread.
        val storeIterator = store.iterator()

        // the store iterator should still be valid as the maintenance thread may have already
        // hold it and is doing snapshotting even though the state store is unloaded.
        val outputKeys = new mutable.ArrayBuffer[String]
        val outputValues = new mutable.ArrayBuffer[Int]
        var cnt = 0
        while (storeIterator.hasNext) {
          if (cnt == 10) {
            // Mimic the case where the provider is loaded in another executor in the middle of
            // iteration. When this happens, the provider will be unloaded and closed in
            // current executor.
            provider.close()
          }
          val unsafeRowPair = storeIterator.next()
          val (key, _) = keyRowToData(unsafeRowPair.key)
          outputKeys.append(key)
          outputValues.append(valueRowToData(unsafeRowPair.value))

          cnt = cnt + 1
        }
        assert(keys.sorted === outputKeys.sorted)
        assert(values.sorted === outputValues.sorted)
    }
  }

  test("maintenance") {
    val conf = new SparkConf()
      .setMaster("local")
      .setAppName("test")
    val opId = 0
    val dir1 = newDir()
    val storeProviderId1 = StateStoreProviderId(StateStoreId(dir1, opId, 0), UUID.randomUUID)
    val dir2 = newDir()
    val storeProviderId2 = StateStoreProviderId(StateStoreId(dir2, opId, 1), UUID.randomUUID)
    val sqlConf = getDefaultSQLConf(SQLConf.STATE_STORE_MIN_DELTAS_FOR_SNAPSHOT.defaultValue.get,
      SQLConf.MAX_BATCHES_TO_RETAIN_IN_MEMORY.defaultValue.get)
    sqlConf.setConf(SQLConf.MIN_BATCHES_TO_RETAIN, 2)
    // Make maintenance thread do snapshots and cleanups very fast
    sqlConf.setConf(SQLConf.STREAMING_MAINTENANCE_INTERVAL, 10L)
    val storeConf = StateStoreConf(sqlConf)
    val hadoopConf = new Configuration()

    var latestStoreVersion = 0

    def generateStoreVersions(): Unit = {
      for (i <- 1 to 20) {
        val store = StateStore.get(storeProviderId1, keySchema, valueSchema,
          NoPrefixKeyStateEncoderSpec(keySchema),
          latestStoreVersion, None, None, useColumnFamilies = false, storeConf, hadoopConf)
        put(store, "a", 0, i)
        store.commit()
        latestStoreVersion += 1
      }
    }

    val timeoutDuration = 1.minute

    quietly {
      withSpark(SparkContext.getOrCreate(conf)) { sc =>
        withCoordinatorRef(sc) { coordinatorRef =>
          require(!StateStore.isMaintenanceRunning, "StateStore is unexpectedly running")

          // Generate sufficient versions of store for snapshots
          generateStoreVersions()

          eventually(timeout(timeoutDuration)) {
            // Store should have been reported to the coordinator
            assert(coordinatorRef.getLocation(storeProviderId1).nonEmpty,
              "active instance was not reported")

            // Background maintenance should clean up and generate snapshots
            assert(StateStore.isMaintenanceRunning, "Maintenance task is not running")

            // Some snapshots should have been generated
            tryWithProviderResource(newStoreProvider(storeProviderId1.storeId)) { provider =>
              val snapshotVersions = (1 to latestStoreVersion).filter { version =>
                fileExists(provider, version, isSnapshot = true)
              }
              assert(snapshotVersions.nonEmpty, "no snapshot file found")
            }
          }

          // Generate more versions such that there is another snapshot and
          // the earliest delta file will be cleaned up
          generateStoreVersions()

          // Earliest delta file should get cleaned up
          tryWithProviderResource(newStoreProvider(storeProviderId1.storeId)) { provider =>
            eventually(timeout(timeoutDuration)) {
              assert(!fileExists(provider, 1, isSnapshot = false), "earliest file not deleted")
            }
          }

          // If driver decides to deactivate all stores related to a query run,
          // then this instance should be unloaded
          coordinatorRef.deactivateInstances(storeProviderId1.queryRunId)
          eventually(timeout(timeoutDuration)) {
            assert(!StateStore.isLoaded(storeProviderId1))
          }

          // Reload the store and verify
          StateStore.get(storeProviderId1, keySchema, valueSchema,
            NoPrefixKeyStateEncoderSpec(keySchema),
            latestStoreVersion, None, None, useColumnFamilies = false, storeConf, hadoopConf)
          assert(StateStore.isLoaded(storeProviderId1))

          // If some other executor loads the store, then this instance should be unloaded
          coordinatorRef
            .reportActiveInstance(storeProviderId1, "other-host", "other-exec", Seq.empty)
          eventually(timeout(timeoutDuration)) {
            assert(!StateStore.isLoaded(storeProviderId1))
          }

          // Reload the store and verify
          StateStore.get(storeProviderId1, keySchema, valueSchema,
            NoPrefixKeyStateEncoderSpec(keySchema),
            latestStoreVersion, None, None, useColumnFamilies = false, storeConf, hadoopConf)
          assert(StateStore.isLoaded(storeProviderId1))

          // If some other executor loads the store, and when this executor loads other store,
          // then this executor should unload inactive instances immediately.
          coordinatorRef
            .reportActiveInstance(storeProviderId1, "other-host", "other-exec", Seq.empty)
          StateStore.get(storeProviderId2, keySchema, valueSchema,
            NoPrefixKeyStateEncoderSpec(keySchema),
            0, None, None, useColumnFamilies = false, storeConf, hadoopConf)
          assert(!StateStore.isLoaded(storeProviderId1))
          assert(StateStore.isLoaded(storeProviderId2))
        }
      }

      // Verify if instance is unloaded if SparkContext is stopped
      eventually(timeout(timeoutDuration)) {
        require(SparkEnv.get === null)
        assert(!StateStore.isLoaded(storeProviderId1))
        assert(!StateStore.isLoaded(storeProviderId2))
        assert(!StateStore.isMaintenanceRunning)
      }
    }
  }

  test("SPARK-40492: maintenance before unload") {
    val conf = new SparkConf()
      .setMaster("local")
      .setAppName("SPARK-40492")
    val opId = 0
    val dir1 = newDir()
    val storeProviderId1 = StateStoreProviderId(StateStoreId(dir1, opId, 0), UUID.randomUUID)
    val sqlConf = getDefaultSQLConf(SQLConf.STATE_STORE_MIN_DELTAS_FOR_SNAPSHOT.defaultValue.get,
      SQLConf.MAX_BATCHES_TO_RETAIN_IN_MEMORY.defaultValue.get)
    sqlConf.setConf(SQLConf.MIN_BATCHES_TO_RETAIN, 2)
    // Make maintenance interval large so that maintenance is called after deactivating instances.
    sqlConf.setConf(SQLConf.STREAMING_MAINTENANCE_INTERVAL, 1.minute.toMillis)
    val storeConf = StateStoreConf(sqlConf)
    val hadoopConf = new Configuration()

    var latestStoreVersion = 0

    def generateStoreVersions(): Unit = {
      for (i <- 1 to 20) {
        val store = StateStore.get(storeProviderId1, keySchema, valueSchema,
          NoPrefixKeyStateEncoderSpec(keySchema),
          latestStoreVersion, None, None, useColumnFamilies = false, storeConf, hadoopConf)
        put(store, "a", 0, i)
        store.commit()
        latestStoreVersion += 1
      }
    }

    val timeoutDuration = 1.minute

    quietly {
      withSpark(SparkContext.getOrCreate(conf)) { sc =>
        withCoordinatorRef(sc) { coordinatorRef =>
          require(!StateStore.isMaintenanceRunning, "StateStore is unexpectedly running")

          // Generate sufficient versions of store for snapshots
          generateStoreVersions()
          eventually(timeout(timeoutDuration)) {
            // Store should have been reported to the coordinator
            assert(coordinatorRef.getLocation(storeProviderId1).nonEmpty,
              "active instance was not reported")
            // Background maintenance should clean up and generate snapshots
            assert(StateStore.isMaintenanceRunning, "Maintenance task is not running")
            // Some snapshots should have been generated
            tryWithProviderResource(newStoreProvider(storeProviderId1.storeId)) { provider =>
              val snapshotVersions = (1 to latestStoreVersion).filter { version =>
                fileExists(provider, version, isSnapshot = true)
              }
              assert(snapshotVersions.nonEmpty, "no snapshot file found")
            }
          }
          // Generate more versions such that there is another snapshot.
          generateStoreVersions()

          // If driver decides to deactivate all stores related to a query run,
          // then this instance should be unloaded.
          coordinatorRef.deactivateInstances(storeProviderId1.queryRunId)
          eventually(timeout(timeoutDuration)) {
            assert(!StateStore.isLoaded(storeProviderId1))
          }

          // Earliest delta file should be scheduled a cleanup during unload.
          tryWithProviderResource(newStoreProvider(storeProviderId1.storeId)) { provider =>
            eventually(timeout(timeoutDuration)) {
              assert(!fileExists(provider, 1, isSnapshot = false), "earliest file not deleted")
            }
          }
        }
      }
    }
  }

  test("snapshotting") {
    tryWithProviderResource(
      newStoreProvider(minDeltasForSnapshot = 5, numOfVersToRetainInMemory = 2)) { provider =>

      var currentVersion = 0

      currentVersion = updateVersionTo(provider, currentVersion, 2)
      require(getLatestData(provider, useColumnFamilies = false) === Set(("a", 0) -> 2))
      provider.doMaintenance()               // should not generate snapshot files
      assert(getLatestData(provider, useColumnFamilies = false) === Set(("a", 0) -> 2))

      for (i <- 1 to currentVersion) {
        assert(fileExists(provider, i, isSnapshot = false))  // all delta files present
        assert(!fileExists(provider, i, isSnapshot = true))  // no snapshot files present
      }

      // After version 6, snapshotting should generate one snapshot file
      currentVersion = updateVersionTo(provider, currentVersion, 6)
      require(getLatestData(provider, useColumnFamilies = false)
        === Set(("a", 0) -> 6), "store not updated correctly")
      provider.doMaintenance()       // should generate snapshot files

      val snapshotVersion = (0 to 6).find { version =>
        fileExists(provider, version, isSnapshot = true)
      }
      assert(snapshotVersion.nonEmpty, "snapshot file not generated")
      deleteFilesEarlierThanVersion(provider, snapshotVersion.get)
      assert(
        getData(provider, snapshotVersion.get, useColumnFamilies = false)
          === Set(("a", 0) -> snapshotVersion.get),
        "snapshotting messed up the data of the snapshotted version")
      assert(
        getLatestData(provider, useColumnFamilies = false) === Set(("a", 0) -> 6),
        "snapshotting messed up the data of the final version")

      // After version 20, snapshotting should generate newer snapshot files
      currentVersion = updateVersionTo(provider, currentVersion, 20)
      require(getLatestData(provider, useColumnFamilies = false)
        === Set(("a", 0) -> 20), "store not updated correctly")
      provider.doMaintenance()       // do snapshot

      val latestSnapshotVersion = (0 to 20).filter(version =>
        fileExists(provider, version, isSnapshot = true)).lastOption
      assert(latestSnapshotVersion.nonEmpty, "no snapshot file found")
      assert(latestSnapshotVersion.get > snapshotVersion.get, "newer snapshot not generated")

      deleteFilesEarlierThanVersion(provider, latestSnapshotVersion.get)
      assert(getLatestData(provider, useColumnFamilies = false)
        === Set(("a", 0) -> 20), "snapshotting messed up the data")
    }
  }

  test("SPARK-18416: do not create temp delta file until the store is updated") {
    val dir = newDir()
    val storeId = StateStoreProviderId(StateStoreId(dir, 0, 0), UUID.randomUUID)
    val storeConf = StateStoreConf.empty
    val hadoopConf = new Configuration()
    val deltaFileDir = new File(s"$dir/0/0/")

    def numTempFiles: Int = {
      if (deltaFileDir.exists) {
        deltaFileDir.listFiles.map(_.getName).count(n => n.endsWith(".tmp"))
      } else 0
    }

    def numDeltaFiles: Int = {
      if (deltaFileDir.exists) {
        deltaFileDir.listFiles.map(_.getName).count(n => n.contains(".delta") && !n.startsWith("."))
      } else 0
    }

    def shouldNotCreateTempFile[T](body: => T): T = {
      val before = numTempFiles
      val result = body
      assert(numTempFiles === before)
      result
    }

    // Getting the store should not create temp file
    val store0 = shouldNotCreateTempFile {
      StateStore.get(
        storeId, keySchema, valueSchema,
        NoPrefixKeyStateEncoderSpec(keySchema),
        version = 0, None, None, useColumnFamilies = false, storeConf, hadoopConf)
    }

    // Put should create a temp file
    put(store0, "a", 0, 1)
    assert(numTempFiles === 1)
    assert(numDeltaFiles === 0)

    // Commit should remove temp file and create a delta file
    store0.commit()
    assert(numTempFiles === 0)
    assert(numDeltaFiles === 1)

    // Remove should create a temp file
    val store1 = shouldNotCreateTempFile {
      StateStore.get(
        storeId, keySchema, valueSchema,
        NoPrefixKeyStateEncoderSpec(keySchema),
        version = 1, None, None, useColumnFamilies = false, storeConf, hadoopConf)
    }
    remove(store1, _._1 == "a")
    assert(numTempFiles === 1)
    assert(numDeltaFiles === 1)

    // Commit should remove temp file and create a delta file
    store1.commit()
    assert(numTempFiles === 0)
    assert(numDeltaFiles === 2)

    // Commit without any updates should create a delta file
    val store2 = shouldNotCreateTempFile {
      StateStore.get(
        storeId, keySchema, valueSchema,
        NoPrefixKeyStateEncoderSpec(keySchema),
        version = 2, None, None, useColumnFamilies = false, storeConf, hadoopConf)
    }
    store2.commit()
    assert(numTempFiles === 0)
    assert(numDeltaFiles === 3)
  }

  test("SPARK-21145: Restarted queries create new provider instances") {
    try {
      val checkpointLocation = Utils.createTempDir().getAbsoluteFile
      val spark = SparkSession.builder().master("local[2]").getOrCreate()
      SparkSession.setActiveSession(spark)
      implicit val sqlContext = spark.sqlContext
      spark.conf.set(SQLConf.SHUFFLE_PARTITIONS.key, "1")
      import spark.implicits._
      val inputData = MemoryStream[Int]

      def runQueryAndGetLoadedProviders(): Seq[StateStoreProvider] = {
        val aggregated = inputData.toDF().groupBy("value").agg(count("*"))
        // stateful query
        val query = aggregated.writeStream
          .format("memory")
          .outputMode("complete")
          .queryName("query")
          .option("checkpointLocation", checkpointLocation.toString)
          .start()
        inputData.addData(1, 2, 3)
        query.processAllAvailable()
        require(query.lastProgress != null) // at least one batch processed after start
        val loadedProvidersMethod =
          PrivateMethod[mutable.HashMap[StateStoreProviderId, StateStoreProvider]](
            Symbol("loadedProviders"))
        val loadedProvidersMap = StateStore invokePrivate loadedProvidersMethod()
        val loadedProviders = loadedProvidersMap.synchronized { loadedProvidersMap.values.toSeq }
        query.stop()
        loadedProviders
      }

      val loadedProvidersAfterRun1 = runQueryAndGetLoadedProviders()
      require(loadedProvidersAfterRun1.length === 1)

      val loadedProvidersAfterRun2 = runQueryAndGetLoadedProviders()
      assert(loadedProvidersAfterRun2.length === 2)   // two providers loaded for 2 runs

      // Both providers should have the same StateStoreId, but the should be different objects
      assert(loadedProvidersAfterRun2(0).stateStoreId === loadedProvidersAfterRun2(1).stateStoreId)
      assert(loadedProvidersAfterRun2(0) ne loadedProvidersAfterRun2(1))

    } finally {
      SparkSession.getActiveSession.foreach { spark =>
        spark.streams.active.foreach(_.stop())
        spark.stop()
      }
    }
  }

  test("error writing [version].delta cancels the output stream") {

    val hadoopConf = new Configuration()
    hadoopConf.set(
      SQLConf.STREAMING_CHECKPOINT_FILE_MANAGER_CLASS.parent.key,
      classOf[CreateAtomicTestManager].getName)
    val remoteDir = Utils.createTempDir().getAbsolutePath

    tryWithProviderResource(newStoreProvider(opId = Random.nextInt(), partition = 0,
      dir = remoteDir, hadoopConf = hadoopConf)) { provider =>

      // Disable failure of output stream and generate versions
      CreateAtomicTestManager.shouldFailInCreateAtomic = false
      for (version <- 1 to 10) {
        val store = provider.getStore(version - 1)
        put(store, version.toString, 0, version) // update "1" -> 1, "2" -> 2, ...
        store.commit()
      }
      val version10Data = (1L to 10).map(_.toString).map(x => x -> x).toSet

      CreateAtomicTestManager.cancelCalledInCreateAtomic = false
      val store = provider.getStore(10)
      // Fail commit for next version and verify that reloading resets the files
      CreateAtomicTestManager.shouldFailInCreateAtomic = true
      put(store, "11", 0, 11)
      val e = intercept[SparkException] { quietly { store.commit() } }
      assert(e.getCause.isInstanceOf[IOException])
      assert(e.getMessage.contains("Cannot perform commit"))
      CreateAtomicTestManager.shouldFailInCreateAtomic = false

      // Abort commit for next version and verify that reloading resets the files
      CreateAtomicTestManager.cancelCalledInCreateAtomic = false
      val store2 = provider.getStore(10)
      put(store2, "11", 0, 11)
      store2.abort()
      assert(CreateAtomicTestManager.cancelCalledInCreateAtomic)
    }
  }

  test("expose metrics with custom metrics to StateStoreMetrics") {
    def getCustomMetric(metrics: StateStoreMetrics, name: String): Long = {
      val metricPair = metrics.customMetrics.find(_._1.name == name)
      assert(metricPair.isDefined)
      metricPair.get._2
    }

    def getLoadedMapSizeMetric(metrics: StateStoreMetrics): Long = {
      metrics.memoryUsedBytes
    }

    def assertCacheHitAndMiss(
        metrics: StateStoreMetrics,
        expectedCacheHitCount: Long,
        expectedCacheMissCount: Long): Unit = {
      val cacheHitCount = getCustomMetric(metrics, "loadedMapCacheHitCount")
      val cacheMissCount = getCustomMetric(metrics, "loadedMapCacheMissCount")
      assert(cacheHitCount === expectedCacheHitCount)
      assert(cacheMissCount === expectedCacheMissCount)
    }

    var store: StateStore = null
    var loadedMapSizeForVersion1: Long = -1L
    tryWithProviderResource(newStoreProvider()) { provider =>
      // Verify state before starting a new set of updates
      assert(getLatestData(provider, useColumnFamilies = false).isEmpty)

      store = provider.getStore(0)
      assert(!store.hasCommitted)

      assert(store.metrics.numKeys === 0)

      val initialLoadedMapSize = getLoadedMapSizeMetric(store.metrics)
      assert(initialLoadedMapSize >= 0)
      assertCacheHitAndMiss(store.metrics, expectedCacheHitCount = 0, expectedCacheMissCount = 0)

      put(store, "a", 0, 1)
      assert(store.metrics.numKeys === 1)

      put(store, "b", 0, 2)
      put(store, "aa", 0, 3)
      assert(store.metrics.numKeys === 3)
      remove(store, _._1.startsWith("a"))
      assert(store.metrics.numKeys === 1)
      assert(store.commit() === 1)

      assert(store.hasCommitted)

      loadedMapSizeForVersion1 = getLoadedMapSizeMetric(store.metrics)
      assert(loadedMapSizeForVersion1 > initialLoadedMapSize)
      assertCacheHitAndMiss(store.metrics, expectedCacheHitCount = 0, expectedCacheMissCount = 0)

      val storeV2 = provider.getStore(1)
      assert(!storeV2.hasCommitted)
      assert(storeV2.metrics.numKeys === 1)

      put(storeV2, "cc", 0, 4)
      assert(storeV2.metrics.numKeys === 2)
      assert(storeV2.commit() === 2)

      assert(storeV2.hasCommitted)

      val loadedMapSizeForVersion1And2 = getLoadedMapSizeMetric(storeV2.metrics)
      assert(loadedMapSizeForVersion1And2 > loadedMapSizeForVersion1)
      assertCacheHitAndMiss(storeV2.metrics, expectedCacheHitCount = 1, expectedCacheMissCount = 0)
    }

    tryWithProviderResource(newStoreProvider(store.id)) { reloadedProvider =>
      // intended to load version 2 instead of 1
      // version 2 will not be loaded to the cache in provider
      val reloadedStore = reloadedProvider.getStore(1)
      assert(reloadedStore.metrics.numKeys === 1)

      assertCacheHitAndMiss(reloadedStore.metrics, expectedCacheHitCount = 0,
        expectedCacheMissCount = 1)

      // now we are loading version 2
      val reloadedStoreV2 = reloadedProvider.getStore(2)
      assert(reloadedStoreV2.metrics.numKeys === 2)

      assert(getLoadedMapSizeMetric(reloadedStoreV2.metrics) > loadedMapSizeForVersion1)
      assertCacheHitAndMiss(reloadedStoreV2.metrics, expectedCacheHitCount = 0,
        expectedCacheMissCount = 2)
    }
  }

  test("SPARK-52740: HDFSBackedStateStoreProvider checkpoint format version 2 with " +
    "deserialized sqlConf throws error correctly") {
    val sqlConf = getDefaultSQLConf(SQLConf.STATE_STORE_MIN_DELTAS_FOR_SNAPSHOT.defaultValue.get,
      SQLConf.MAX_BATCHES_TO_RETAIN_IN_MEMORY.defaultValue.get)
    sqlConf.setConf(SQLConf.STATE_STORE_CHECKPOINT_FORMAT_VERSION, 2)
    val originalConf = new StateStoreConf(sqlConf)

    // Serialize
    val baos = new ByteArrayOutputStream()
    val oos = new ObjectOutputStream(baos)
    oos.writeObject(originalConf)
    oos.close()

    // Deserialize
    val bais = new ByteArrayInputStream(baos.toByteArray)
    val ois = new ObjectInputStream(bais)
    val deserializedConf = ois.readObject().asInstanceOf[StateStoreConf]
    ois.close()

    val provider = new HDFSBackedStateStoreProvider()
    val hadoopConf = new Configuration()
    hadoopConf.set(StreamExecution.RUN_ID_KEY, UUID.randomUUID().toString)

    val e = intercept[AssertionError] {
      provider.init(
        StateStoreId(newDir(), Random.nextInt(), 0),
        keySchema,
        valueSchema,
        NoPrefixKeyStateEncoderSpec(keySchema),
        useColumnFamilies = false,
        deserializedConf,
        hadoopConf)
    }
    assert(e.getMessage.contains(
      "HDFS State Store Provider doesn't support checkpointFormatVersion >= 2"))
  }

  override def newStoreProvider(): HDFSBackedStateStoreProvider = {
    newStoreProvider(opId = Random.nextInt(), partition = 0)
  }

  override def newStoreProvider(storeId: StateStoreId): HDFSBackedStateStoreProvider = {
    newStoreProvider(storeId.operatorId, storeId.partitionId, dir = storeId.checkpointRootLocation)
  }

  override def newStoreProvider(
      storeId: StateStoreId,
      useColumnFamilies: Boolean): HDFSBackedStateStoreProvider = {
    newStoreProvider(storeId.operatorId, storeId.partitionId, dir = storeId.checkpointRootLocation)
  }

  def newStoreProvider(
      storeId: StateStoreId,
      conf: Configuration): HDFSBackedStateStoreProvider = {
    newStoreProvider(
      storeId.operatorId,
      storeId.partitionId,
      dir = storeId.checkpointRootLocation,
      hadoopConf = conf)
  }

  override def newStoreProvider(
      minDeltasForSnapshot: Int,
      numOfVersToRetainInMemory: Int): HDFSBackedStateStoreProvider = {
    newStoreProvider(opId = Random.nextInt(), partition = 0,
      minDeltasForSnapshot = minDeltasForSnapshot,
      numOfVersToRetainInMemory = numOfVersToRetainInMemory)
  }

  override def getLatestData(
      storeProvider: HDFSBackedStateStoreProvider,
      useColumnFamilies: Boolean = false): Set[((String, Int), Int)] = {
    getData(storeProvider, -1, useColumnFamilies)
  }

  override def getData(
      provider: HDFSBackedStateStoreProvider,
      version: Int,
      useColumnFamilies: Boolean = false): Set[((String, Int), Int)] = {
    tryWithProviderResource(newStoreProvider(provider.stateStoreId,
      useColumnFamilies)) { reloadedProvider =>
      if (version < 0) {
        reloadedProvider.latestIterator().map(rowPairToDataPair).toSet
      } else {
        val store = reloadedProvider.getStore(version)
        try {
          store.iterator().map(rowPairToDataPair).toSet
        } finally {
          if (!store.hasCommitted) store.abort()
        }
      }
    }
  }

  override def getDefaultSQLConf(
      minDeltasForSnapshot: Int,
      numOfVersToRetainInMemory: Int): SQLConf = {
    val sqlConf = new SQLConf()
    sqlConf.setConf(SQLConf.STATE_STORE_MIN_DELTAS_FOR_SNAPSHOT, minDeltasForSnapshot)
    sqlConf.setConf(SQLConf.MAX_BATCHES_TO_RETAIN_IN_MEMORY, numOfVersToRetainInMemory)
    sqlConf.setConf(SQLConf.MIN_BATCHES_TO_RETAIN, 2)
    sqlConf.setConf(SQLConf.STATE_STORE_COMPRESSION_CODEC, SQLConf.get.stateStoreCompressionCodec)
    sqlConf
  }

  def newStoreProvider(
      opId: Long,
      partition: Int,
      keyStateEncoderSpec: KeyStateEncoderSpec = NoPrefixKeyStateEncoderSpec(keySchema),
      keySchema: StructType = keySchema,
      dir: String = newDir(),
      minDeltasForSnapshot: Int = SQLConf.STATE_STORE_MIN_DELTAS_FOR_SNAPSHOT.defaultValue.get,
      numOfVersToRetainInMemory: Int = SQLConf.MAX_BATCHES_TO_RETAIN_IN_MEMORY.defaultValue.get,
      hadoopConf: Configuration = new Configuration): HDFSBackedStateStoreProvider = {
    hadoopConf.set(StreamExecution.RUN_ID_KEY, UUID.randomUUID().toString)
    val sqlConf = getDefaultSQLConf(minDeltasForSnapshot, numOfVersToRetainInMemory)
    val provider = new HDFSBackedStateStoreProvider()
    provider.init(
      StateStoreId(dir, opId, partition),
      keySchema,
      valueSchema,
      keyStateEncoderSpec,
      useColumnFamilies = false,
      new StateStoreConf(sqlConf),
      hadoopConf)
    provider
  }

  override def newStoreProvider(
      keySchema: StructType,
      keyStateEncoderSpec: KeyStateEncoderSpec,
      useColumnFamilies: Boolean): HDFSBackedStateStoreProvider = {
    newStoreProvider(opId = Random.nextInt(), partition = 0,
      keyStateEncoderSpec = keyStateEncoderSpec)
  }

  override def newStoreProvider(useColumnFamilies: Boolean): HDFSBackedStateStoreProvider = {
    // TODO: remove multiple col families restriction when we add support for
    // HDFSBackedStateStoreProvider
    newStoreProvider(opId = Random.nextInt(), partition = 0)
  }

  def checkLoadedVersions(
      loadedMaps: util.SortedMap[Long, HDFSBackedStateStoreMap],
      count: Int,
      earliestKey: Long,
      latestKey: Long): Unit = {
    assert(loadedMaps.size() === count)
    assert(loadedMaps.firstKey() === earliestKey)
    assert(loadedMaps.lastKey() === latestKey)
  }

  def checkVersion(
      loadedMaps: util.SortedMap[Long, HDFSBackedStateStoreMap],
      version: Long,
      expectedData: Map[(String, Int), Int]): Unit = {
    val originValueMap = loadedMaps.get(version).iterator().map { entry =>
      keyRowToData(entry.key) -> valueRowToData(entry.value)
    }.toMap

    assert(originValueMap === expectedData)
  }

  def emptyFile(
      provider: HDFSBackedStateStoreProvider,
      version: Long,
      isSnapshot: Boolean): Unit = {
    val method = PrivateMethod[Path](Symbol("baseDir"))
    val basePath = provider invokePrivate method()
    val fileName = if (isSnapshot) s"$version.snapshot" else s"$version.delta"
    val filePath = new File(basePath.toString, fileName)
    filePath.delete()
    filePath.createNewFile()
  }
}

abstract class StateStoreSuiteBase[ProviderClass <: StateStoreProvider]
  extends StateStoreCodecsTest with PrivateMethodTester {
  import StateStoreTestsHelper._

  type MapType = mutable.HashMap[UnsafeRow, UnsafeRow]

  protected val keySchema: StructType = StateStoreTestsHelper.keySchema
  protected val valueSchema: StructType = StateStoreTestsHelper.valueSchema

  testWithAllCodec("get, put, remove, commit, and all data iterator") { colFamiliesEnabled =>
    tryWithProviderResource(newStoreProvider(colFamiliesEnabled)) { provider =>
      // Verify state before starting a new set of updates
      assert(getLatestData(provider, useColumnFamilies = colFamiliesEnabled).isEmpty)

      val store = provider.getStore(0)
      assert(!store.hasCommitted)
      assert(get(store, "a", 0) === None)
      assert(store.iterator().isEmpty)

      // Verify state after updating
      put(store, "a", 0, 1)
      assert(get(store, "a", 0) === Some(1))

      assert(store.iterator().nonEmpty)
      assert(getLatestData(provider, useColumnFamilies = colFamiliesEnabled).isEmpty)

      // Make updates, commit and then verify state
      put(store, "b", 0, 2)
      put(store, "aa", 0, 3)
      remove(store, _._1.startsWith("a"))
      assert(store.commit() === 1)

      assert(store.hasCommitted)
      val store1 = provider.getStore(1)
      assert(rowPairsToDataSet(store1.iterator()) === Set(("b", 0) -> 2))
      store1.abort()

      // Trying to get newer versions should fail
      var e = intercept[SparkException] {
        provider.getStore(2)
      }
      assert(e.getMessage.contains("does not exist"))

      e = intercept[SparkException] {
        getData(provider, 2, useColumnFamilies = colFamiliesEnabled)
      }
      assert(e.getMessage.contains("does not exist"))

      // New updates to the reloaded store with new version, and does not change old version
      tryWithProviderResource(newStoreProvider(store.id, colFamiliesEnabled)) { reloadedProvider =>
        val reloadedStore = reloadedProvider.getStore(1)
        put(reloadedStore, "c", 0, 4)
        assert(reloadedStore.commit() === 2)
        val reloadedStore1 = reloadedProvider.getStore(2)
        assert(rowPairsToDataSet(reloadedStore1.iterator()) === Set(("b", 0) -> 2, ("c", 0) -> 4))
        reloadedStore1.commit()
      }
    }
  }

  testWithAllCodec("prefix scan") { colFamiliesEnabled =>
    tryWithProviderResource(newStoreProvider(keySchema, PrefixKeyScanStateEncoderSpec(keySchema, 1),
      colFamiliesEnabled)) { provider =>
      // Verify state before starting a new set of updates
      assert(getLatestData(provider, useColumnFamilies = false).isEmpty)

      var store = provider.getStore(0)

      def putCompositeKeys(keys: Seq[(String, Int)]): Unit = {
        val randomizedKeys = scala.util.Random.shuffle(keys.toList)
        randomizedKeys.foreach { case (key1, key2) =>
          put(store, key1, key2, key2)
        }
      }

      def verifyScan(key1: Seq[String], key2: Seq[Int]): Unit = {
        key1.foreach { k1 =>
          val keyValueSet = store.prefixScan(dataToPrefixKeyRow(k1)).map { pair =>
            rowPairToDataPair(pair.withRows(pair.key.copy(), pair.value.copy()))
          }.toSet

          assert(keyValueSet === key2.map(k2 => ((k1, k2), k2)).toSet)
        }
      }

      val key1AtVersion0 = Seq("a", "b", "c")
      val key2AtVersion0 = Seq(1, 2, 3)
      val keysAtVersion0 = for (k1 <- key1AtVersion0; k2 <- key2AtVersion0) yield (k1, k2)

      putCompositeKeys(keysAtVersion0)
      verifyScan(key1AtVersion0, key2AtVersion0)

      assert(store.prefixScan(dataToPrefixKeyRow("non-exist")).isEmpty)

      // committing and loading the version 1 (the version being committed)
      store.commit()
      store = provider.getStore(1)

      // before putting the new key-value pairs, verify prefix scan works for existing keys
      verifyScan(key1AtVersion0, key2AtVersion0)

      val key1AtVersion1 = Seq("c", "d")
      val key2AtVersion1 = Seq(4, 5, 6)
      val keysAtVersion1 = for (k1 <- key1AtVersion1; k2 <- key2AtVersion1) yield (k1, k2)

      // put a new key-value pairs, and verify that prefix scan reflects the changes
      putCompositeKeys(keysAtVersion1)
      verifyScan(Seq("c"), Seq(1, 2, 3, 4, 5, 6))
      verifyScan(Seq("d"), Seq(4, 5, 6))

      // aborting and loading the version 1 again (keysAtVersion1 should be rolled back)
      store.abort()
      store = provider.getStore(1)

      // prefix scan should not reflect the uncommitted changes
      verifyScan(key1AtVersion0, key2AtVersion0)
      verifyScan(Seq("d"), Seq.empty)
      store.abort()
    }
  }

  testWithAllCodec(s"numKeys metrics") { colFamiliesEnabled =>
    tryWithProviderResource(newStoreProvider(colFamiliesEnabled)) { provider =>
      // Verify state before starting a new set of updates
      assert(getLatestData(provider, useColumnFamilies = colFamiliesEnabled).isEmpty)

      val store = provider.getStore(0)
      put(store, "a", 0, 1)
      put(store, "b", 0, 2)
      put(store, "c", 0, 3)
      put(store, "d", 0, 4)
      put(store, "e", 0, 5)
      assert(store.commit() === 1)
      assert(store.metrics.numKeys === 5)
      val store1 = provider.getStore(1)
      assert(rowPairsToDataSet(store1.iterator()) ===
        Set(("a", 0) -> 1, ("b", 0) -> 2, ("c", 0) -> 3, ("d", 0) -> 4, ("e", 0) -> 5))
      store1.abort()

      tryWithProviderResource(newStoreProvider(store.id, colFamiliesEnabled)) { reloadedProvider =>
        val reloadedStore = reloadedProvider.getStore(1)
        remove(reloadedStore, _._1 == "b")
        assert(rowPairsToDataSet(reloadedStore.iterator()) ===
          Set(("a", 0) -> 1, ("c", 0) -> 3, ("d", 0) -> 4, ("e", 0) -> 5))
        assert(reloadedStore.commit() === 2)
        assert(reloadedStore.metrics.numKeys === 4)
      }
    }
  }

  testWithAllCodec(s"removing while iterating") { colFamiliesEnabled =>
    tryWithProviderResource(newStoreProvider(colFamiliesEnabled)) { provider =>
      // Verify state before starting a new set of updates
      assert(getLatestData(provider, useColumnFamilies = colFamiliesEnabled).isEmpty)
      val store = provider.getStore(0)
      try {
        put(store, "a", 0, 1)
        put(store, "b", 0, 2)

        // Updates should work while iterating of filtered entries
        val filtered = store.iterator().filter { tuple => keyRowToData(tuple.key) == ("a", 0) }
        filtered.foreach { tuple =>
          store.put(tuple.key, dataToValueRow(valueRowToData(tuple.value) + 1))
        }
        assert(get(store, "a", 0) === Some(2))

        // Removes should work while iterating of filtered entries
        val filtered2 = store.iterator().filter { tuple => keyRowToData(tuple.key) == ("b", 0) }
        filtered2.foreach { tuple => store.remove(tuple.key) }
        assert(get(store, "b", 0) === None)
      } finally {
        if (!store.hasCommitted) store.abort()
      }
    }
  }

  testWithAllCodec(s"abort") { colFamiliesEnabled =>
    tryWithProviderResource(newStoreProvider(colFamiliesEnabled)) { provider =>
      val store = provider.getStore(0)
      put(store, "a", 0, 1)
      assert(rowPairsToDataSet(store.iterator()) === Set(("a", 0) -> 1))
      store.commit()

      // cancelUpdates should not change the data in the files
      val store1 = provider.getStore(1)
      put(store1, "b", 0, 1)
      store1.abort()
    }
  }

  testWithAllCodec(s"getStore with invalid versions") { colFamiliesEnabled =>
    tryWithProviderResource(newStoreProvider(colFamiliesEnabled)) { provider =>
      def checkInvalidVersion(version: Int, isHDFSBackedStoreProvider: Boolean): Unit = {
        val e = intercept[SparkException] {
          provider.getStore(version)
        }
        if (version < 0) {
          checkError(
            e,
            condition = "CANNOT_LOAD_STATE_STORE.UNEXPECTED_VERSION",
            parameters = Map("version" -> version.toString)
          )
        } else {
          if (isHDFSBackedStoreProvider) {
            checkError(
              e,
              condition = "CANNOT_LOAD_STATE_STORE.CANNOT_READ_DELTA_FILE_NOT_EXISTS",
              parameters = Map("fileToRead" -> ".*", "clazz" -> ".*"),
              matchPVals = true
            )
          } else {
            checkError(
              e,
              condition = "CANNOT_LOAD_STATE_STORE.CANNOT_READ_STREAMING_STATE_FILE",
              parameters = Map("fileToRead" -> ".*"),
              matchPVals = true
            )
          }
        }
      }

      checkInvalidVersion(-1, provider.isInstanceOf[HDFSBackedStateStoreProvider])
      checkInvalidVersion(1, provider.isInstanceOf[HDFSBackedStateStoreProvider])

      val store = provider.getStore(0)
      put(store, "a", 0, 1)
      assert(rowPairsToDataSet(store.iterator()) === Set(("a", 0) -> 1))
      assert(store.commit() === 1)

      val store1_ = provider.getStore(1)
      assert(rowPairsToDataSet(store1_.iterator()) === Set(("a", 0) -> 1))
      store1_.abort()

      checkInvalidVersion(-1, provider.isInstanceOf[HDFSBackedStateStoreProvider])
      checkInvalidVersion(2, provider.isInstanceOf[HDFSBackedStateStoreProvider])

      // Update store version with some data
      val store1 = provider.getStore(1)
      assert(rowPairsToDataSet(store1.iterator()) === Set(("a", 0) -> 1))
      put(store1, "b", 0, 1)
      assert(rowPairsToDataSet(store1.iterator()) === Set(("a", 0) -> 1, ("b", 0) -> 1))
      assert(store1.commit() === 2)

      checkInvalidVersion(-1, provider.isInstanceOf[HDFSBackedStateStoreProvider])
      checkInvalidVersion(3, provider.isInstanceOf[HDFSBackedStateStoreProvider])
    }
  }

  testWithAllCodec("two concurrent StateStores - one for read-only and one for read-write") {
    colFamiliesEnabled =>
    // During Streaming Aggregation, we have two StateStores per task, one used as read-only in
    // `StateStoreRestoreExec`, and one read-write used in `StateStoreSaveExec`. `StateStore.abort`
    // will be called for these StateStores if they haven't committed their results. We need to
    // make sure that `abort` in read-only store after a `commit` in the read-write store doesn't
    // accidentally lead to the deletion of state.
    val dir = newDir()
    val storeId = StateStoreId(dir, 0L, 1)
    val key1 = "a"
    val key2 = 0

    tryWithProviderResource(newStoreProvider(storeId, colFamiliesEnabled)) { provider0 =>
      // prime state
      val store = provider0.getStore(0)

      put(store, key1, key2, 1)
      store.commit()
      val store1 = provider0.getStore(1)
      assert(rowPairsToDataSet(store1.iterator()) === Set((key1, key2) -> 1))
      store1.abort()
    }

    // two state stores
    tryWithProviderResource(newStoreProvider(storeId, colFamiliesEnabled)) { provider1 =>
      val restoreStore = provider1.getReadStore(1)
      val saveStore = provider1.upgradeReadStoreToWriteStore(restoreStore, 1)

      put(saveStore, key1, key2, get(restoreStore, key1, key2).get + 1)
      saveStore.commit()
    }

    // check that state is correct for next batch
    tryWithProviderResource(newStoreProvider(storeId, colFamiliesEnabled)) { provider2 =>
      val finalStore = provider2.getStore(2)
      assert(rowPairsToDataSet(finalStore.iterator()) === Set((key1, key2) -> 2))
      finalStore.abort()
    }
  }

  testQuietly("SPARK-18342: commit fails when rename fails") {
    import RenameReturnsFalseFileSystem._

    val ROCKSDB_STATE_STORE = "RocksDBStateStore"
    val dir = scheme + "://" + newDir()
    val conf = new Configuration()
    conf.set(s"fs.$scheme.impl", classOf[RenameReturnsFalseFileSystem].getName)

    val storeId = StateStoreId(dir, operatorId = 0, partitionId = 0)
    tryWithProviderResource(newStoreProvider(storeId, conf)) { provider =>
      val store = provider.getStore(0)
      try {
        put(store, "a", 0, 0)
        val e = intercept[SparkException](quietly {
          store.commit()
        })

        assert(e.getCondition == "CANNOT_WRITE_STATE_STORE.CANNOT_COMMIT")
        if (store.getClass.getName contains ROCKSDB_STATE_STORE) {
          assert(e.getMessage contains "RocksDBStateStore")
        } else {
          assert(e.getMessage contains "HDFSStateStore")
        }
        assert(e.getMessage contains "operatorId=0")
        assert(e.getMessage contains "partitionId=0")
        assert(e.getMessage contains "Error writing state store files")
        assert(e.getCause.getMessage.contains("Failed to rename"))
      } finally {
        if (!store.hasCommitted) store.abort()
      }
    }
  }

  test("two concurrent StateStores - one for read-only and one for read-write with release()") {
    val dir = Utils.createTempDir().getAbsolutePath
    val storeId = StateStoreId(dir, 0L, 1)
    val storeProviderId = StateStoreProviderId(storeId, UUID.randomUUID)
    val key1 = "a"
    val key2 = 0
    val storeConf = StateStoreConf.empty
    val hadoopConf = new Configuration()

    quietly {
      withSpark(SparkContext.getOrCreate(
        new SparkConf().setMaster("local").setAppName("test"))) { sc =>
        withCoordinatorRef(sc) { _ =>
          // Prime state
          val store = StateStore.get(
            storeProviderId, keySchema, valueSchema,
            NoPrefixKeyStateEncoderSpec(keySchema),
            0, None, None, useColumnFamilies = false, storeConf, hadoopConf)

          put(store, key1, key2, 1)
          store.commit()

          // Get two state stores - one read-only and one read-write
          val restoreStore = StateStore.getReadOnly(
            storeProviderId, keySchema, valueSchema,
            NoPrefixKeyStateEncoderSpec(keySchema),
            1, None, None, useColumnFamilies = false, storeConf, hadoopConf)

          val saveStore = StateStore.get(
            storeProviderId, keySchema, valueSchema,
            NoPrefixKeyStateEncoderSpec(keySchema),
            1, None, None, useColumnFamilies = false, storeConf, hadoopConf)

          // Update the write store based on data from read store
          put(saveStore, key1, key2, get(restoreStore, key1, key2).get + 1)
          saveStore.commit()

          // Check that state is correct for next batch
          val finalStore = StateStore.get(
            storeProviderId, keySchema, valueSchema,
            NoPrefixKeyStateEncoderSpec(keySchema),
            2, None, None, useColumnFamilies = false, storeConf, hadoopConf)

          assert(get(finalStore, key1, key2) === Some(2))
        }
      }
    }
  }

  test("getWriteStore correctly uses existing read store") {
    val dir = Utils.createTempDir().getAbsolutePath
    val storeId = StateStoreId(dir, 0L, 1)
    val storeProviderId = StateStoreProviderId(storeId, UUID.randomUUID)
    val storeConf = StateStoreConf.empty
    val hadoopConf = new Configuration()

    quietly {
      withSpark(SparkContext.getOrCreate(
        new SparkConf().setMaster("local").setAppName("test"))) { sc =>
        withCoordinatorRef(sc) { _ =>
          // Prime state
          val store = StateStore.get(
            storeProviderId, keySchema, valueSchema,
            NoPrefixKeyStateEncoderSpec(keySchema),
            0, None, None, useColumnFamilies = false, storeConf, hadoopConf)

          put(store, "a", 0, 1)
          store.commit()

          // Get a read-only store
          val readStore = StateStore.getReadOnly(
            storeProviderId, keySchema, valueSchema,
            NoPrefixKeyStateEncoderSpec(keySchema),
            1, None, None, useColumnFamilies = false, storeConf, hadoopConf)

          // Convert it to a write store using the new getWriteStore method
          val writeStore = StateStore.getWriteStore(
            readStore,
            storeProviderId, keySchema, valueSchema,
            NoPrefixKeyStateEncoderSpec(keySchema),
            1, None, None, useColumnFamilies = false, storeConf, hadoopConf)

          // The write store should still have access to the data
          assert(get(writeStore, "a", 0) === Some(1))

          // Update and commit with the write store
          put(writeStore, "a", 0, 2)
          writeStore.commit()

          // Check that the state was updated correctly
          val finalStore = StateStore.get(
            storeProviderId, keySchema, valueSchema,
            NoPrefixKeyStateEncoderSpec(keySchema),
            2, None, None, useColumnFamilies = false, storeConf, hadoopConf)

          assert(get(finalStore, "a", 0) === Some(2))
        }
      }
    }
  }

  // This test illustrates state store iterator behavior differences leading to SPARK-38320.
  testWithAllCodec("SPARK-38320 - state store iterator behavior differences") {
    colFamiliesEnabled =>
    val ROCKSDB_STATE_STORE = "RocksDBStateStore"
    val dir = newDir()
    val storeId = StateStoreId(dir, 0L, 1)
    var version = 0L

    tryWithProviderResource(newStoreProvider(storeId, colFamiliesEnabled)) { provider =>
      val store = provider.getStore(version)
      logInfo(s"Running SPARK-38320 test with state store ${store.getClass.getName}")

      val itr1 = store.iterator()  // itr1 is created before any writes to the store.
      put(store, "1", 11, 100)
      put(store, "2", 22, 200)
      val itr2 = store.iterator()  // itr2 is created in the middle of the writes.
      put(store, "1", 11, 101)  // Overwrite row (1, 11)
      put(store, "3", 33, 300)
      val itr3 = store.iterator()  // itr3 is created after all writes.

      val intermediateState = Set(("1", 11) -> 100, ("2", 22) -> 200) // The intermediate state.
      val finalState = Set(("1", 11) -> 101, ("2", 22) -> 200, ("3", 33) -> 300) // The final state.
      // Itr1 does not see any updates - original state of the store (SPARK-38320)
      assert(rowPairsToDataSet(itr1) === Set.empty[Set[((String, Int), Int)]])
      if (store.getClass.getName contains ROCKSDB_STATE_STORE) {
        assert(rowPairsToDataSet(itr2) === intermediateState)
      } else {
        assert(rowPairsToDataSet(itr2) === finalState)
      }
      assert(rowPairsToDataSet(itr3) === finalState)

      version = store.commit()
    }

    // Reload the store from the commited version and repeat the above test.
    tryWithProviderResource(newStoreProvider(storeId, colFamiliesEnabled)) { provider =>
      assert(version > 0)
      val store = provider.getStore(version)

      val itr1 = store.iterator()  // itr1 is created before any writes to the store.
      put(store, "3", 33, 301)  // Overwrite row (3, 33)
      put(store, "4", 44, 400)
      val itr2 = store.iterator()  // itr2 is created in the middle of the writes.
      put(store, "4", 44, 401)  // Overwrite row (4, 44)
      put(store, "5", 55, 500)
      val itr3 = store.iterator()  // itr3 is created after all writes.

      // The intermediate state
      val intermediate = Set(
        ("1", 11) -> 101, ("2", 22) -> 200, ("3", 33) -> 301, ("4", 44) -> 400)
      // The final state.
      val expected = Set(
        ("1", 11) -> 101, ("2", 22) -> 200, ("3", 33) -> 301, ("4", 44) -> 401, ("5", 55) -> 500)
      if (store.getClass.getName contains ROCKSDB_STATE_STORE) {
        // RocksDB itr1 does not see any updates - original state of the store (SPARK-38320)
        assert(rowPairsToDataSet(itr1) === Set(
          ("1", 11) -> 101, ("2", 22) -> 200, ("3", 33) -> 300))
      } else {
        assert(rowPairsToDataSet(itr1) === expected)
      }

      if (store.getClass.getName contains ROCKSDB_STATE_STORE) {
        assert(rowPairsToDataSet(itr2) === intermediate)
      } else {
        assert(rowPairsToDataSet(itr2) === expected)
      }

      assert(rowPairsToDataSet(itr3) === expected)

      version = store.commit()
    }
  }

  test("StateStore.get") {
    val conf = new SparkConf()
      .setMaster("local")
      .setAppName("test")
    quietly {
      withSpark(SparkContext.getOrCreate(conf)) { sc =>
        withCoordinatorRef(sc) { coordinatorRef =>
          val dir = newDir()
          val storeId = StateStoreProviderId(StateStoreId(dir, 0, 0), UUID.randomUUID)
          val storeConf = getDefaultStoreConf()
          val hadoopConf = new Configuration()

          // Verify that trying to get incorrect versions throw errors
          var e = intercept[SparkException] {
            StateStore.get(
              storeId, keySchema, valueSchema,
                NoPrefixKeyStateEncoderSpec(keySchema), -1, None, None, useColumnFamilies = false,
                storeConf, hadoopConf)
          }
          checkError(
            e,
            condition = "CANNOT_LOAD_STATE_STORE.UNEXPECTED_VERSION",
            parameters = Map(
              "version" -> "-1"
            )
          )

          e = intercept[SparkException] {
            StateStore.get(
              storeId, keySchema, valueSchema,
              NoPrefixKeyStateEncoderSpec(keySchema),
              1, None, None, useColumnFamilies = false,
              storeConf, hadoopConf)
          }
          checkError(
            e,
            condition = "CANNOT_LOAD_STATE_STORE.CANNOT_READ_DELTA_FILE_NOT_EXISTS",
            parameters = Map(
              "fileToRead" -> s"$dir/0/0/1.delta",
              "clazz" -> "HDFSStateStoreProvider\\[.+\\]"
            ),
            matchPVals = true
          )

          // Increase version of the store and try to get again
          val store0 = StateStore.get(
            storeId, keySchema, valueSchema,
            NoPrefixKeyStateEncoderSpec(keySchema),
            0, None, None, useColumnFamilies = false,
            storeConf, hadoopConf)
          assert(store0.version === 0)
          put(store0, "a", 0, 1)
          store0.commit()

          val store1 = StateStore.get(
            storeId, keySchema, valueSchema,
            NoPrefixKeyStateEncoderSpec(keySchema),
            1, None, None, useColumnFamilies = false,
            storeConf, hadoopConf)
          assert(StateStore.isLoaded(storeId))
          assert(store1.version === 1)
          assert(rowPairsToDataSet(store1.iterator()) === Set(("a", 0) -> 1))

          // Verify that you can also load older version
          val store0reloaded = StateStore.get(
            storeId, keySchema, valueSchema,
            NoPrefixKeyStateEncoderSpec(keySchema),
            0, None, None, useColumnFamilies = false,
            storeConf, hadoopConf)
          assert(store0reloaded.version === 0)
          assert(rowPairsToDataSet(store0reloaded.iterator()) === Set.empty)

          // Verify that you can remove the store and still reload and use it
          StateStore.removeFromLoadedProvidersAndClose(storeId)
          assert(!StateStore.isLoaded(storeId))

          val store1reloaded = StateStore.get(
            storeId, keySchema, valueSchema,
            NoPrefixKeyStateEncoderSpec(keySchema),
            1, None, None, useColumnFamilies = false,
            storeConf, hadoopConf)
          assert(StateStore.isLoaded(storeId))
          assert(store1reloaded.version === 1)
          put(store1reloaded, "a", 0, 2)
          assert(store1reloaded.commit() === 2)
          assert(rowPairsToDataSet(store1reloaded.iterator()) === Set(("a", 0) -> 2))
        }
      }
    }
  }

  test("reports memory usage") {
    // RocksDB metrics is only guaranteed to update when snapshot is created, so we set
    // minDeltasForSnapshot = 1 to enable snapshot generation here.
    tryWithProviderResource(newStoreProvider(minDeltasForSnapshot = 1,
      numOfVersToRetainInMemory = 1)) { provider =>
      val store = provider.getStore(0)
      put(store, "a", 0, 1)
      store.commit()
      assert(store.metrics.memoryUsedBytes > 0)
    }
  }

  test("SPARK-34270: StateStoreMetrics.combine should not override individual metrics") {
    val customSumMetric = StateStoreCustomSumMetric("metric1", "custom metric 1")
    val customSizeMetric = StateStoreCustomSizeMetric("metric2", "custom metric 2")
    val customTimingMetric = StateStoreCustomTimingMetric("metric3", "custom metric 3")

    val leftCustomMetrics: Map[StateStoreCustomMetric, Long] =
      Map(customSumMetric -> 10L, customSizeMetric -> 5L, customTimingMetric -> 100L)
    val leftMetrics = StateStoreMetrics(1, 10, leftCustomMetrics)

    val rightCustomMetrics: Map[StateStoreCustomMetric, Long] =
      Map(customSumMetric -> 20L, customSizeMetric -> 15L, customTimingMetric -> 300L)
    val rightMetrics = StateStoreMetrics(3, 20, rightCustomMetrics)

    val combinedMetrics = StateStoreMetrics.combine(Seq(leftMetrics, rightMetrics))
    assert(combinedMetrics.numKeys == 4)
    assert(combinedMetrics.memoryUsedBytes == 30)
    assert(combinedMetrics.customMetrics.size == 3)
    assert(combinedMetrics.customMetrics(customSumMetric) == 30L)
    assert(combinedMetrics.customMetrics(customSizeMetric) == 20L)
    assert(combinedMetrics.customMetrics(customTimingMetric) == 400L)
  }

  test("SPARK-35659: StateStore.put cannot put null value") {
    tryWithProviderResource(newStoreProvider()) { provider =>
      // Verify state before starting a new set of updates
      assert(getLatestData(provider, useColumnFamilies = false).isEmpty)

      val store = provider.getStore(0)
      try {
        val err = intercept[IllegalArgumentException] {
          store.put(dataToKeyRow("key", 0), null)
        }
        assert(err.getMessage.contains("Cannot put a null value"))
      } finally {
        if (!store.hasCommitted) store.abort()
      }
    }
  }

  test("SPARK-35763: StateStoreCustomMetric withNewDesc and createSQLMetric") {
    val metric = StateStoreCustomSizeMetric(name = "m1", desc = "desc1")
    val metricNew = metric.withNewDesc("new desc")
    assert(metricNew.desc === "new desc", "incorrect description in copied instance")
    assert(metricNew.name === "m1", "incorrect name in copied instance")

    val conf = new SparkConf().setMaster("local").setAppName("SPARK-35763")
    withSpark(SparkContext.getOrCreate(conf)) { sc =>
      val sqlMetric = metric.createSQLMetric(sc)
      assert(sqlMetric != null)
      assert(sqlMetric.name === Some("desc1"))
    }
  }

  test("SPARK-48997: maintenance threads with exceptions unload only themselves") {
    val sqlConf = getDefaultSQLConf(
      SQLConf.STATE_STORE_MIN_DELTAS_FOR_SNAPSHOT.defaultValue.get,
      SQLConf.MAX_BATCHES_TO_RETAIN_IN_MEMORY.defaultValue.get
    )
    // Make maintenance interval small so that maintenance task is called right after scheduling.
    sqlConf.setConf(SQLConf.STREAMING_MAINTENANCE_INTERVAL, 100L)
    // Use the `MaintenanceErrorOnCertainPartitionsProvider` to run the test
    sqlConf.setConf(
      SQLConf.STATE_STORE_PROVIDER_CLASS,
      classOf[MaintenanceErrorOnCertainPartitionsProvider].getName
    )

    val conf = new SparkConf().setMaster("local").setAppName("test")

    withSpark(new SparkContext(conf)) { sc =>
      withCoordinatorRef(sc) { _ =>
        val rootLocation = s"${Utils.createTempDir().getAbsolutePath}/spark-48997"
        // 0 and 1's maintenance will fail
        val provider0Id =
          StateStoreProviderId(StateStoreId(rootLocation, 0, 0), UUID.randomUUID)
        val provider1Id =
          StateStoreProviderId(StateStoreId(rootLocation, 0, 1), UUID.randomUUID)
        val provider2Id =
          StateStoreProviderId(StateStoreId(rootLocation, 0, 2), UUID.randomUUID)

        // Create provider 2 first to start the maintenance task + pool
        StateStore.get(
          provider2Id,
          keySchema, valueSchema, NoPrefixKeyStateEncoderSpec(keySchema),
          0, None, None, useColumnFamilies = false, new StateStoreConf(sqlConf), new Configuration()
        )

        // The following 2 calls to `get` will cause the associated maintenance to fail
        StateStore.get(
          provider0Id,
          keySchema, valueSchema, NoPrefixKeyStateEncoderSpec(keySchema),
          0, None, None, useColumnFamilies = false, new StateStoreConf(sqlConf), new Configuration()
        )

        StateStore.get(
          provider1Id,
          keySchema, valueSchema, NoPrefixKeyStateEncoderSpec(keySchema),
          0, None, None, useColumnFamilies = false, new StateStoreConf(sqlConf), new Configuration()
        )

        // Wait for the maintenance task for all the providers to run: it should happen relatively
        // quickly since the maintenance interval is small.
        eventually(timeout(5.seconds)) {
          assert(!StateStore.isLoaded(provider0Id))
          assert(!StateStore.isLoaded(provider1Id))
          assert(StateStore.isLoaded(provider2Id))
        }
      }
    }
  }

  test("SPARK-42572: StateStoreProvider.validateStateRowFormat shouldn't check" +
    " value row format when SQLConf.STATE_STORE_FORMAT_VALIDATION_ENABLED is false") {
    // By default, when there is an invalid pair of value row and value schema, it should throw
    val keyRow = dataToKeyRow("key", 1)
    val valueRow = dataToValueRow(2)
    val e = intercept[StateStoreValueRowFormatValidationFailure] {
      // Here valueRow doesn't match with prefixKeySchema
      StateStoreProvider.validateStateRowFormat(
        keyRow, keySchema, valueRow, keySchema, StateStoreId("", 0L, 0), getDefaultStoreConf())
    }
    assert(e.getMessage.contains("The streaming query failed to validate written state"))

    // When sqlConf.stateStoreFormatValidationEnabled is set to false and
    // StateStoreConf.FORMAT_VALIDATION_CHECK_VALUE_CONFIG is set to true,
    // don't check value row
    val sqlConf = getDefaultSQLConf(SQLConf.STATE_STORE_MIN_DELTAS_FOR_SNAPSHOT.defaultValue.get,
      SQLConf.MAX_BATCHES_TO_RETAIN_IN_MEMORY.defaultValue.get)
    sqlConf.setConf(SQLConf.STATE_STORE_FORMAT_VALIDATION_ENABLED, false)
    val storeConf = new StateStoreConf(sqlConf,
      Map(StateStoreConf.FORMAT_VALIDATION_CHECK_VALUE_CONFIG -> "true"))
    // Shouldn't throw
    StateStoreProvider.validateStateRowFormat(
      keyRow, keySchema, valueRow, keySchema, StateStoreId("", 0L, 0), storeConf)
  }

  test("test serialization and deserialization of NoPrefixKeyStateEncoderSpec") {
    implicit val formats: DefaultFormats.type = DefaultFormats
    val encoderSpec = NoPrefixKeyStateEncoderSpec(keySchema)
    val jsonMap = JsonMethods.parse(encoderSpec.json).extract[Map[String, Any]]
    val deserializedEncoderSpec = KeyStateEncoderSpec.fromJson(keySchema, jsonMap)
    assert(encoderSpec == deserializedEncoderSpec)
  }

  test("test serialization and deserialization of PrefixKeyScanStateEncoderSpec") {
    implicit val formats: DefaultFormats.type = DefaultFormats
    val encoderSpec = PrefixKeyScanStateEncoderSpec(keySchema, 1)
    val jsonMap = JsonMethods.parse(encoderSpec.json).extract[Map[String, Any]]
    val deserializedEncoderSpec = KeyStateEncoderSpec.fromJson(keySchema, jsonMap)
    assert(encoderSpec == deserializedEncoderSpec)
  }

  test("test serialization and deserialization of RangeKeyScanStateEncoderSpec") {
    implicit val formats: DefaultFormats.type = DefaultFormats
    val encoderSpec = RangeKeyScanStateEncoderSpec(keySchema, Seq(1))
    val jsonMap = JsonMethods.parse(encoderSpec.json).extract[Map[String, Any]]
    val deserializedEncoderSpec = KeyStateEncoderSpec.fromJson(keySchema, jsonMap)
    assert(encoderSpec == deserializedEncoderSpec)
  }

  /** Return a new provider with a random id */
  def newStoreProvider(): ProviderClass

  /** Return a new provider with the given id */
  def newStoreProvider(storeId: StateStoreId): ProviderClass

  /** Return a new provider with the given id and multiple column families */
  def newStoreProvider(storeId: StateStoreId, useColumnFamilies: Boolean): ProviderClass

  /** Return a new provider with the given id and configuration */
  def newStoreProvider(storeId: StateStoreId, conf: Configuration): ProviderClass

  /** Return a new provider with minimum delta and version to retain in memory */
  def newStoreProvider(minDeltasForSnapshot: Int, numOfVersToRetainInMemory: Int): ProviderClass

  /** Return a new provider with setting prefix key */
  def newStoreProvider(
      keySchema: StructType,
      keyStateEncoderSpec: KeyStateEncoderSpec,
      useColumnFamilies: Boolean): ProviderClass

  /** Return a new provider with useColumnFamilies set to true */
  def newStoreProvider(useColumnFamilies: Boolean): ProviderClass

  /** Get the latest data referred to by the given provider but not using this provider */
  def getLatestData(storeProvider: ProviderClass,
    useColumnFamilies: Boolean): Set[((String, Int), Int)]

  /**
   * Get a specific version of data referred to by the given provider but not using
   * this provider
   */
  def getData(storeProvider: ProviderClass, version: Int,
    useColumnFamilies: Boolean): Set[((String, Int), Int)]

  protected def testQuietly(name: String)(f: => Unit): Unit = {
    test(name) {
      quietly {
        f
      }
    }
  }

  protected def tryWithProviderResource[T](provider: ProviderClass)(f: ProviderClass => T): T = {
    try {
      f(provider)
    } finally {
      provider.close()
    }
  }

  /** Get the `SQLConf` by the given minimum delta and version to retain in memory */
  def getDefaultSQLConf(minDeltasForSnapshot: Int, numOfVersToRetainInMemory: Int): SQLConf

  /** Get the `StateStoreConf` used by the tests with default setting */
  def getDefaultStoreConf(): StateStoreConf = StateStoreConf.empty

  protected def fileExists(
      provider: ProviderClass,
      version: Long,
      isSnapshot: Boolean): Boolean = {
    val method = PrivateMethod[Path](Symbol("baseDir"))
    val basePath = provider invokePrivate method()
    val fileName = if (isSnapshot) s"$version.snapshot" else s"$version.delta"
    val filePath = new File(basePath.toString, fileName)
    filePath.exists
  }

  def updateVersionTo(
      provider: StateStoreProvider,
      currentVersion: Int,
      targetVersion: Int): Int = {
    var newCurrentVersion = currentVersion
    for (i <- newCurrentVersion until targetVersion) {
      newCurrentVersion = incrementVersion(provider, i)
    }
    require(newCurrentVersion === targetVersion)
    newCurrentVersion
  }

  def incrementVersion(provider: StateStoreProvider, currentVersion: Int): Int = {
    val store = provider.getStore(currentVersion)
    put(store, "a", 0, currentVersion + 1)
    store.commit()
    currentVersion + 1
  }

  def deleteFilesEarlierThanVersion(provider: ProviderClass, version: Long): Unit = {
    val method = PrivateMethod[Path](Symbol("baseDir"))
    val basePath = provider invokePrivate method()
    for (version <- 0 until version.toInt) {
      for (isSnapshot <- Seq(false, true)) {
        val fileName = if (isSnapshot) s"$version.snapshot" else s"$version.delta"
        val filePath = new File(basePath.toString, fileName)
        if (filePath.exists) filePath.delete()
      }
    }
  }
}

object StateStoreTestsHelper {

  val keySchema = StructType(
    Seq(StructField("key1", StringType, true), StructField("key2", IntegerType, true)))
  val valueSchema = StructType(Seq(StructField("value", IntegerType, true)))

  val keySchemaWithRangeScan: StructType = StructType(
    Seq(StructField("key1", LongType, false), StructField("key2", StringType, false)))

  val keyProj = UnsafeProjection.create(Array[DataType](StringType, IntegerType))
  val rangeScanProj = UnsafeProjection.create(Array[DataType](LongType, StringType))
  val prefixKeyProj = UnsafeProjection.create(Array[DataType](StringType))
  val prefixKeyProjWithRangeScan = UnsafeProjection.create(Array[DataType](LongType))
  val valueProj = UnsafeProjection.create(Array[DataType](IntegerType))

  def dataToPrefixKeyRow(s: String): UnsafeRow = {
    prefixKeyProj.apply(new GenericInternalRow(Array[Any](UTF8String.fromString(s)))).copy()
  }

  def dataToPrefixKeyRowWithRangeScan(ts: Long): UnsafeRow = {
    prefixKeyProjWithRangeScan.apply(new GenericInternalRow(Array[Any](ts))).copy()
  }

  def dataToKeyRow(s: String, i: Int): UnsafeRow = {
    keyProj.apply(new GenericInternalRow(Array[Any](UTF8String.fromString(s), i))).copy()
  }

  def dataToKeyRowWithRangeScan(ts: Long, s: String): UnsafeRow = {
    rangeScanProj.apply(new GenericInternalRow(Array[Any](ts, UTF8String.fromString(s)))).copy()
  }

  def dataToValueRow(i: Int): UnsafeRow = {
    valueProj.apply(new GenericInternalRow(Array[Any](i))).copy()
  }

  def keyRowToData(row: UnsafeRow): (String, Int) = {
    (row.getUTF8String(0).toString, row.getInt(1))
  }

  def keyRowWithRangeScanToData(row: UnsafeRow): (Long, String) = {
    (row.getLong(0), row.getUTF8String(1).toString)
  }

  def valueRowToData(row: UnsafeRow): Int = {
    row.getInt(0)
  }

  def rowPairToDataPair(row: UnsafeRowPair): ((String, Int), Int) = {
    (keyRowToData(row.key), valueRowToData(row.value))
  }

  def rowPairsToDataSet(iterator: Iterator[UnsafeRowPair]): Set[((String, Int), Int)] = {
    iterator.map(rowPairToDataPair).toSet
  }

  def remove(store: StateStore, condition: ((String, Int)) => Boolean,
    colFamilyName: String = StateStore.DEFAULT_COL_FAMILY_NAME): Unit = {
    store.iterator(colFamilyName).foreach { rowPair =>
      if (condition(keyRowToData(rowPair.key))) store.remove(rowPair.key, colFamilyName)
    }
  }

  def put(store: StateStore, key1: String, key2: Int, value: Int,
    colFamilyName: String = StateStore.DEFAULT_COL_FAMILY_NAME): Unit = {
    store.put(dataToKeyRow(key1, key2), dataToValueRow(value), colFamilyName)
  }

  def merge(store: StateStore, key1: String, key2: Int, value: Int,
    colFamilyName: String = StateStore.DEFAULT_COL_FAMILY_NAME): Unit = {
    store.merge(dataToKeyRow(key1, key2), dataToValueRow(value), colFamilyName)
  }

  def get(store: ReadStateStore, key1: String, key2: Int,
    colFamilyName: String = StateStore.DEFAULT_COL_FAMILY_NAME): Option[Int] = {
    Option(store.get(dataToKeyRow(key1, key2), colFamilyName)).map(valueRowToData)
  }

  def newDir(): String = Utils.createTempDir().toString
}

/**
 * Fake FileSystem that simulates HDFS rename semantic, i.e. renaming a file atop an existing
 * one should return false.
 * See hadoop.apache.org/docs/stable/hadoop-project-dist/hadoop-common/filesystem/filesystem.html
 */
class RenameLikeHDFSFileSystem extends RawLocalFileSystem {
  override def rename(src: Path, dst: Path): Boolean = {
    if (exists(dst)) {
      false
    } else {
      super.rename(src, dst)
    }
  }
}

/**
 * Fake FileSystem to test that the StateStore throws an exception while committing the
 * delta file, when `fs.rename` returns `false`.
 */
class RenameReturnsFalseFileSystem extends RawLocalFileSystem {
  import RenameReturnsFalseFileSystem._
  override def getUri: URI = {
    URI.create(s"$scheme:///")
  }

  override def rename(src: Path, dst: Path): Boolean = false
}

object RenameReturnsFalseFileSystem {
  val scheme = s"StateStoreSuite${math.abs(Random.nextInt())}fs"
}

/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.native.internal.gc

import kotlin.native.internal.*
import kotlin.native.internal.NativePtr
import kotlin.native.concurrent.*
import kotlin.time.*
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.cinterop.*
import kotlin.system.*

/**
 * This class represents statistics of memory usage in one memory pool.
 *
 * @property objectsCount The number of allocated objects.
 * @property totalObjectsSizeBytes The total size of allocated objects. System allocator overhead is not included,
 *                                 so it can not perfectly match the value received by os tools.
 *                                 All alignment and auxiliary object headers are included.
 */
@ExperimentalStdlibApi
class MemoryUsage(
        val objectsCount: Long,
        val totalObjectsSizeBytes: Long,
)

/**
 * This class represents statistics of the root set for garbage collector run, separated by root set pools.
 * These nodes are assumed to be used, even if there are no references for them.
 *
 * @property threadLocalReferences The number of objects in global variables with @ThreadLocal annotation.
 *                                 Object is counted for each thread it was initialized.
 * @property stackReferences The number of objects referenced from the stack of any thread.
 *                           These are function local variables and different temporary values, as function call arguments and
 *                           return values. They would be automatically removed from the root set when a corresponding function
 *                           call is finished.
 * @property globalReferences The number of objects in global variables. The object is counted only if the variable is initialized.
 * @property stableReferences The number of objects referenced by [kotlinx.cinterop.StableRef]. It includes both explicit usage
 *                            of this API, and internal usages, e.g. inside interop and Worker API.
 */
@ExperimentalStdlibApi
class RootSetStatistics(
        val threadLocalReferences: Long,
        val stackReferences: Long,
        val globalReferences: Long,
        val stableReferences: Long
)

/**
 * This class represents statistics about the single run of the garbage collector.
 * It is supposed to be used for testing and debugging purposes only.
 * Not all values can be available for all garbage collector implementations.
 *
 * @property epoch ID of garbage collector run.
 * @property startTimeNs Time, when garbage collector run is started, meausered by [kotlin.system.getTimeNanos].
 * @property endTimeNs Time, when garbage collector run is ended, measured by [kotlin.system.getTimeNanos].
 *                     After this point, most of the memory is reclaimed, and a new garbage collector run can start.
 * @property duration Difference between [endTimeNs] and [startTimeNs]. This is the best estimation of how long was this garbage collector run was.
 * @property pauseStartTimeNs Time, when mutator threads are suspended, mesured by [kotlin.system.getTimeNanos].
 * @property pauseEndTimeNs Time, when mutator threads are unsuspended, mesured by [kotlin.system.getTimeNanos].
 * @property pauseDuration Difference between [pauseEndTimeNs] and [pauseStartTimeNs]. This is the best estimation of how long no application
 *           operations can happen because of the garbage collector run.
 * @property finilisersDoneTimeNs Time, when all memory is reclaimed, measured by [kotlin.system.getTimeNanos].
 *                                If null, memory reclaiming is still in progress.
 * @property durationWithFinalizers Difference between [finilisersDoneTimeNs] and [startTimeNs].
 *                                  This is the best estimation of how long memory can still be not reclaimed after
 *                                  the garbage collector starts.
 * @property rootSet The number of objects in each root set pool. Check [RootSetStatistics] doc for details.
 * @property memoryUsageAfter Memory usage at the start of garbage collector run, separated by memory pools.
 *                            The set of memory pools depends on the collector implementation.
 *                            Can be empty, of colelction is in progress.
 * @property memoryUsageBefore Memory usage at the end of garbage collector run, separated by memory pools.
 *                            The set of memory pools depends on the collector implementation.
 *                            Can be empty, of colelction is in progress.
 */
@ExperimentalStdlibApi
class GCInfo(
        val epoch: Long,
        val startTimeNs: Long,
        val endTimeNs: Long?,
        val pauseStartTimeNs: Long?,
        val pauseEndTimeNs: Long?,
        val finilisersDoneTimeNs: Long?,
        val rootSet: RootSetStatistics?,
        val memoryUsageBefore: Map<String, MemoryUsage>,
        val memoryUsageAfter: Map<String, MemoryUsage>,
) {
    val duration: Duration?
        get() = endTimeNs?.let { (it - startTimeNs).nanoseconds }
    val pauseDuration: Duration?
        get() = if (pauseEndTimeNs != null && pauseStartTimeNs != null) (pauseEndTimeNs - pauseStartTimeNs).nanoseconds else null
    val durationWithFinalizers: Duration?
        get() = finilisersDoneTimeNs?.let { (it - startTimeNs).nanoseconds }

    internal companion object {
        val lastGCInfo: GCInfo?
            get() = getGcInfo(0)
        val runningGCInfo: GCInfo?
            get() = getGcInfo(1)

        private fun getGcInfo(id: Int) = GCInfoBuilder().apply { fill(id) }.build();
    }
}


@ExperimentalStdlibApi
private class GCInfoBuilder() {
    var epoch: Long? = null
    var startTimeNs: Long? = null
    var endTimeNs: Long? = null
    var pauseStartTimeNs: Long? = null
    var pauseEndTimeNs: Long? = null
    var finalizersDoneTimeNs: Long? = null
    var rootSet: RootSetStatistics? = null
    var memoryUsageBefore: MutableMap<String, MemoryUsage>? = null
    var memoryUsageAfter: MutableMap<String, MemoryUsage>? = null

    @ExportForCppRuntime("Kotlin_Internal_GC_GCInfoBuilder_setEpoch")
    private fun setEpoch(value: Long) {
        epoch = value
    }

    @ExportForCppRuntime("Kotlin_Internal_GC_GCInfoBuilder_setStartTime")
    private fun setStartTime(value: Long) {
        startTimeNs = value
    }

    @ExportForCppRuntime("Kotlin_Internal_GC_GCInfoBuilder_setEndTime")
    private fun setEndTime(value: Long) {
        endTimeNs = value
    }

    @ExportForCppRuntime("Kotlin_Internal_GC_GCInfoBuilder_setPauseStartTime")
    private fun setPauseStartTime(value: Long) {
        pauseStartTimeNs = value
    }

    @ExportForCppRuntime("Kotlin_Internal_GC_GCInfoBuilder_setPauseEndTime")
    private fun setPauseEndTime(value: Long) {
        pauseEndTimeNs = value
    }

    @ExportForCppRuntime("Kotlin_Internal_GC_GCInfoBuilder_setFinalizersDoneTime")
    private fun setFinalizersDoneTime(value: Long) {
        finalizersDoneTimeNs = value
    }

    @ExportForCppRuntime("Kotlin_Internal_GC_GCInfoBuilder_setRootSet")
    private fun setRootSet(threadLocalReferences: Long, stackReferences: Long, globalReferences: Long, stableReferences: Long) {
        rootSet = RootSetStatistics(threadLocalReferences, stackReferences, globalReferences, stableReferences)
    }

    @ExportForCppRuntime("Kotlin_Internal_GC_GCInfoBuilder_setMemoryUsageBefore")
    private fun setMemoryUsageBefore(name: NativePtr, objectsCount: Long, totalObjectsSize: Long) {
        val nameString = interpretCPointer<ByteVar>(name)!!.toKString()
        val memoryUsage = MemoryUsage(objectsCount, totalObjectsSize)
        if (memoryUsageBefore == null) {
            memoryUsageBefore = mutableMapOf(nameString to memoryUsage)
        } else {
            memoryUsageBefore!!.put(nameString, memoryUsage)
        }
    }

    @ExportForCppRuntime("Kotlin_Internal_GC_GCInfoBuilder_setMemoryUsageAfter")
    private fun setMemoryUsageAfter(name: NativePtr, objectsCount: Long, totalObjectsSize: Long) {
        val nameString = interpretCPointer<ByteVar>(name)!!.toKString()
        val memoryUsage = MemoryUsage(objectsCount, totalObjectsSize)
        if (memoryUsageAfter == null) {
            memoryUsageAfter = mutableMapOf(nameString to memoryUsage)
        } else {
            memoryUsageAfter!!.put(nameString, memoryUsage)
        }
    }

    fun build(): GCInfo? {
        return if (epoch == null || startTimeNs == null)
            null
        else GCInfo(
                epoch!!,
                startTimeNs!!,
                endTimeNs,
                pauseStartTimeNs,
                pauseEndTimeNs,
                finalizersDoneTimeNs,
                rootSet,
                memoryUsageBefore?.toMap() ?: emptyMap(),
                memoryUsageAfter?.toMap() ?: emptyMap()
        )
    }

    @GCUnsafeCall("Kotlin_Internal_GC_GCInfoBuilder_Fill")
    external fun fill(id: Int)
}
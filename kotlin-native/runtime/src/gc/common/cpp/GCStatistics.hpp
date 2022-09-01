/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

#pragma once

#include <cstdint>
#include <pthread.h>
#include "Common.h"
#include "ThreadData.hpp"

#define GCLogInfo(epoch, format, ...) RuntimeLogInfo({kTagGC}, "Epoch #%" PRIu64 ": " format, epoch, ##__VA_ARGS__)
#define GCLogDebug(epoch, format, ...) RuntimeLogDebug({kTagGC}, "Epoch #%" PRIu64 ": " format, epoch, ##__VA_ARGS__)

namespace kotlin::gc {

class GCHandle;

struct MemoryUsage {
    uint64_t objectsCount;
    uint64_t totalObjectsSize;
};

class GCHandle {
    class GCStageScopeUsTimer {
    protected:
        uint64_t startTime_ = konan::getTimeMicros();
        uint64_t getStageTime() const { return (konan::getTimeMicros() - startTime_); }
    };

    class GCSweepScope : GCStageScopeUsTimer, Pinned {
        GCHandle& handle_;
        MemoryUsage getUsage();

    public:
        explicit GCSweepScope(GCHandle& handle);
        ~GCSweepScope();
    };

    class GCSweepExtraObjectsScope : GCStageScopeUsTimer, Pinned {
        GCHandle& handle_;
        MemoryUsage getUsage();

    public:
        explicit GCSweepExtraObjectsScope(GCHandle& handle);
        ~GCSweepExtraObjectsScope();
    };

    class GCGlobalRootSetScope : GCStageScopeUsTimer, Pinned {
        GCHandle& handle_;
        uint64_t globalRoots_ = 0;
        uint64_t stableRoots_ = 0;

    public:
        explicit GCGlobalRootSetScope(GCHandle& handle);
        ~GCGlobalRootSetScope();
        void addGlobalRoot() { globalRoots_++; }
        void addStableRoot() { stableRoots_++; }
    };

    class GCThreadRootSetScope : GCStageScopeUsTimer, Pinned {
        GCHandle& handle_;
        mm::ThreadData& threadData_;
        uint64_t stackRoots_ = 0;
        uint64_t threadLocalRoots_ = 0;

    public:
        explicit GCThreadRootSetScope(GCHandle& handle, mm::ThreadData& threadData);
        ~GCThreadRootSetScope();
        void addStackRoot() { stackRoots_++; }
        void addThreadLocalRoot() { threadLocalRoots_++; }
    };

    class GCMarkScope : GCStageScopeUsTimer, Pinned {
        GCHandle& handle_;
        uint64_t objectsCount = 0;
        uint64_t totalObjectSizeBytes = 0;

    public:
        explicit GCMarkScope(GCHandle& handle);
        ~GCMarkScope();
        void addObject(uint64_t objectSize) {
            totalObjectSizeBytes += objectSize;
            objectsCount++;
        }
    };

    uint64_t epoch_;
    explicit GCHandle(uint64_t epoch) : epoch_(epoch) {}

    NO_EXTERNAL_CALLS_CHECK void threadRootSetCollected(mm::ThreadData& threadData, uint64_t threadLocalReferences, uint64_t stackReferences);
    NO_EXTERNAL_CALLS_CHECK void globalRootSetCollected(uint64_t globalReferences, uint64_t stableReferences);
    NO_EXTERNAL_CALLS_CHECK void heapUsageBefore(MemoryUsage usage);
    NO_EXTERNAL_CALLS_CHECK void heapUsageAfter(MemoryUsage usage);
    NO_EXTERNAL_CALLS_CHECK void extraObjectsUsageBefore(MemoryUsage usage);
    NO_EXTERNAL_CALLS_CHECK void extraObjectsUsageAfter(MemoryUsage usage);
    NO_EXTERNAL_CALLS_CHECK void marked(MemoryUsage usage);

public:
    static GCHandle create(uint64_t epoch);
    static GCHandle getByEpoch(uint64_t epoch);

    uint64_t getEpoch() { return epoch_; }
    NO_EXTERNAL_CALLS_CHECK void finished();
    NO_EXTERNAL_CALLS_CHECK void finalizersDone();
    NO_EXTERNAL_CALLS_CHECK void finalizersScheduled(uint64_t finalizersCount);
    NO_EXTERNAL_CALLS_CHECK void suspensionRequested();
    NO_EXTERNAL_CALLS_CHECK void threadsAreSuspended();
    NO_EXTERNAL_CALLS_CHECK void threadsAreResumed();
    GCSweepScope sweep() { return GCSweepScope(*this); }
    GCSweepExtraObjectsScope sweepExtraObjects() { return GCSweepExtraObjectsScope(*this); }
    GCGlobalRootSetScope collectGlobalRoots() { return GCGlobalRootSetScope(*this); }
    GCThreadRootSetScope collectThreadRoots(mm::ThreadData& threadData) { return GCThreadRootSetScope(*this, threadData); }
    GCMarkScope mark() { return GCMarkScope(*this); }

    MemoryUsage getMarked();
};
}
/*
 * Copyright 2010-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

#ifndef RUNTIME_GC_COMMON_MARK_AND_SWEEP_UTILS_H
#define RUNTIME_GC_COMMON_MARK_AND_SWEEP_UTILS_H

#include "ExtraObjectData.hpp"
#include "FinalizerHooks.hpp"
#include "GlobalData.hpp"
#include "GCStatistics.hpp"
#include "Logging.hpp"
#include "Memory.h"
#include "ObjectOps.hpp"
#include "ObjectTraversal.hpp"
#include "RootSet.hpp"
#include "Runtime.h"
#include "StableRefRegistry.hpp"
#include "ThreadData.hpp"
#include "Types.h"

namespace kotlin {
namespace gc {

template <typename Traits>
void Mark(GCHandle handle, typename Traits::MarkQueue& markQueue) noexcept {
    auto markHandle = handle.mark();
    while (!Traits::isEmpty(markQueue)) {
        ObjHeader* top = Traits::dequeue(markQueue);

        RuntimeAssert(!isNullOrMarker(top), "Got invalid reference %p in mark queue", top);
        RuntimeAssert(top->heap(), "Got non-heap reference %p in mark queue, permanent=%d stack=%d", top, top->permanent(), top->local());

        markHandle.addObject(mm::GetAllocatedHeapSize(top));

        traverseReferredObjects(top, [&](ObjHeader* field) noexcept {
            if (field->heap()) {
                Traits::enqueue(markQueue, field);
            }
        });

        if (auto* extraObjectData = mm::ExtraObjectData::Get(top)) {
            if (auto weakCounter = extraObjectData->GetWeakReferenceCounter()) {
                RuntimeAssert(
                        weakCounter->heap(), "Weak counter must be a heap object. object=%p counter=%p permanent=%d local=%d", top,
                        weakCounter, weakCounter->permanent(), weakCounter->local());
                Traits::enqueue(markQueue, weakCounter);
            }
        }
    }
}

template <typename Traits>
void SweepExtraObjects(GCHandle handle, typename Traits::ExtraObjectsFactory& objectFactory) noexcept {
    objectFactory.ProcessDeletions();
    auto sweepHandle = handle.sweepExtraObjects();
    auto iter = objectFactory.LockForIter();
    for (auto it = iter.begin(); it != iter.end();) {
        auto &extraObject = *it;
        if (!extraObject.getFlag(mm::ExtraObjectData::FLAGS_IN_FINALIZER_QUEUE) && !Traits::IsMarkedByExtraObject(extraObject)) {
            extraObject.ClearWeakReferenceCounter();
            if (extraObject.HasAssociatedObject()) {
                extraObject.DetachAssociatedObject();
                extraObject.setFlag(mm::ExtraObjectData::FLAGS_IN_FINALIZER_QUEUE);
                ++it;
            } else {
                extraObject.Uninstall();
                objectFactory.EraseAndAdvance(it);
            }
        } else {
            ++it;
        }
    }
}

template <typename Traits>
typename Traits::ObjectFactory::FinalizerQueue Sweep(GCHandle handle, typename Traits::ObjectFactory::Iterable& objectFactoryIter) noexcept {
    typename Traits::ObjectFactory::FinalizerQueue finalizerQueue;
    auto sweepHandle = handle.sweep();

    for (auto it = objectFactoryIter.begin(); it != objectFactoryIter.end();) {
        if (Traits::TryResetMark(*it)) {
            ++it;
            continue;
        }
        auto* objHeader = it->GetObjHeader();
        if (HasFinalizers(objHeader)) {
            objectFactoryIter.MoveAndAdvance(finalizerQueue, it);
        } else {
            objectFactoryIter.EraseAndAdvance(it);
        }
    }

    return finalizerQueue;
}

template <typename Traits>
typename Traits::ObjectFactory::FinalizerQueue Sweep(GCHandle handle, typename Traits::ObjectFactory& objectFactory) noexcept {
    auto iter = objectFactory.LockForIter();
    return Sweep<Traits>(handle, iter);
}

template <typename Traits>
void collectRootSetForThread(GCHandle gcHandle, typename Traits::MarkQueue& markQueue, mm::ThreadData& thread) {
    auto handle = gcHandle.collectThreadRoots(thread);
    thread.gc().OnStoppedForGC();
    for (auto value : mm::ThreadRootSet(thread)) {
        auto* object = value.object;
        if (!isNullOrMarker(object)) {
            if (object->heap()) {
                Traits::enqueue(markQueue, object);
            } else {
                traverseReferredObjects(object, [&](ObjHeader* field) noexcept {
                    // Each permanent and stack object has own entry in the root set.
                    if (field->heap()) {
                        Traits::enqueue(markQueue, field);
                    }
                });
                RuntimeAssert(!object->has_meta_object(), "Non-heap object %p may not have an extra object data", object);
            }
            switch (value.source) {
                case mm::ThreadRootSet::Source::kStack:
                    handle.addStackRoot();
                    break;
                case mm::ThreadRootSet::Source::kTLS:
                    handle.addThreadLocalRoot();
                    break;
            }
        }
    }
}

template <typename Traits>
void collectRootSetGlobals(GCHandle gcHandle, typename Traits::MarkQueue& markQueue) noexcept {
    auto handle = gcHandle.collectGlobalRoots();
    mm::StableRefRegistry::Instance().ProcessDeletions();
    for (auto value : mm::GlobalRootSet()) {
        auto* object = value.object;
        if (!isNullOrMarker(object)) {
            if (object->heap()) {
                Traits::enqueue(markQueue, object);
            } else {
                traverseReferredObjects(object, [&](ObjHeader* field) noexcept {
                    // Each permanent and stack object has own entry in the root set.
                    if (field->heap()) {
                        Traits::enqueue(markQueue, field);
                    }
                });
                RuntimeAssert(!object->has_meta_object(), "Non-heap object %p may not have an extra object data", object);
            }
            switch (value.source) {
                case mm::GlobalRootSet::Source::kGlobal:
                    handle.addGlobalRoot();
                    break;
                case mm::GlobalRootSet::Source::kStableRef:
                    handle.addStableRoot();
                    break;
            }
        }
    }
}

// TODO: This needs some tests now.
template <typename Traits, typename F>
void collectRootSet(GCHandle handle, typename Traits::MarkQueue& markQueue, F&& filter) noexcept {
    Traits::clear(markQueue);
    for (auto& thread : mm::GlobalData::Instance().threadRegistry().LockForIter()) {
        if (!filter(thread))
            continue;
        thread.Publish();
        collectRootSetForThread<Traits>(handle, markQueue, thread);
    }
    collectRootSetGlobals<Traits>(handle, markQueue);
}

} // namespace gc
} // namespace kotlin

#endif // RUNTIME_GC_COMMON_MARK_AND_SWEEP_UTILS_H

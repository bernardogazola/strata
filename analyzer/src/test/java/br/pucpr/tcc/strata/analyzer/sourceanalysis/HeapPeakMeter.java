package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import com.sun.management.GarbageCollectionNotificationInfo;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

final class HeapPeakMeter implements AutoCloseable {

  private static final String EXPLICIT_GC = "System.gc()";

  private final Set<String> heapPools;
  private final List<NotificationEmitter> emitters = new ArrayList<>();
  private final AtomicLong peakLive = new AtomicLong();
  private final AtomicLong peakUsed = new AtomicLong();
  private final NotificationListener listener = this::onNotification;

  HeapPeakMeter() {
    heapPools =
        ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(pool -> pool.getType() == MemoryType.HEAP)
            .map(MemoryPoolMXBean::getName)
            .collect(Collectors.toUnmodifiableSet());
    for (final GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
      if (collector instanceof NotificationEmitter emitter) {
        emitter.addNotificationListener(listener, null, null);
        emitters.add(emitter);
      }
    }
  }

  long peakLiveBytes() {
    return peakLive.get();
  }

  long peakUsedBytes() {
    return peakUsed.get();
  }

  @Override
  public void close() {
    for (final NotificationEmitter emitter : emitters) {
      try {
        emitter.removeNotificationListener(listener);
      } catch (ListenerNotFoundException alreadyGone) {
      }
    }
  }

  private void onNotification(Notification notification, Object handback) {
    if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(
        notification.getType())) {
      return;
    }
    final GarbageCollectionNotificationInfo info =
        GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
    if (EXPLICIT_GC.equals(info.getGcCause())) {
      return;
    }
    peakLive.accumulateAndGet(heapBytes(info.getGcInfo().getMemoryUsageAfterGc()), Math::max);
    peakUsed.accumulateAndGet(heapBytes(info.getGcInfo().getMemoryUsageBeforeGc()), Math::max);
  }

  private long heapBytes(Map<String, MemoryUsage> usageByPool) {
    return usageByPool.entrySet().stream()
        .filter(entry -> heapPools.contains(entry.getKey()))
        .mapToLong(entry -> entry.getValue().getUsed())
        .sum();
  }
}

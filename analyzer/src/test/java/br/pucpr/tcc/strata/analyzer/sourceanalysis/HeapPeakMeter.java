package br.pucpr.tcc.strata.analyzer.sourceanalysis;

import com.sun.management.GarbageCollectionNotificationInfo;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

public final class HeapPeakMeter implements AutoCloseable {

  private static final String EXPLICIT_GC = "System.gc()";

  private static final Set<String> COLLECTING_ACTIONS =
      Set.of("end of minor GC", "end of major GC");

  private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(5);

  private final Set<String> heapPools;
  private final List<NotificationEmitter> emitters = new ArrayList<>();
  private final AtomicLong peakLive = new AtomicLong();
  private final AtomicLong peakUsed = new AtomicLong();
  private final NotificationListener listener = this::onNotification;
  private volatile Drain pendingDrain = new Drain(Map.of(), new CountDownLatch(1));

  public HeapPeakMeter() {
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

  public void drain() throws InterruptedException {
    final Drain drain = new Drain(collectionCounts(), new CountDownLatch(1));
    pendingDrain = drain;
    System.gc();
    if (!drain.delivered().await(DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
      throw new IllegalStateException(
          "a notificação da coleta de drenagem não chegou em " + DRAIN_TIMEOUT);
    }
  }

  public long peakLiveBytes() {
    return peakLive.get();
  }

  public long peakUsedBytes() {
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
      pendingDrain.acknowledge(info);
      return;
    }
    if (!COLLECTING_ACTIONS.contains(info.getGcAction())) {
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

  private static Map<String, Long> collectionCounts() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .collect(
            Collectors.toUnmodifiableMap(
                GarbageCollectorMXBean::getName, GarbageCollectorMXBean::getCollectionCount));
  }

  private record Drain(Map<String, Long> collectionsBefore, CountDownLatch delivered) {

    void acknowledge(GarbageCollectionNotificationInfo explicit) {
      final long before = collectionsBefore.getOrDefault(explicit.getGcName(), Long.MAX_VALUE);
      if (explicit.getGcInfo().getId() > before) {
        delivered.countDown();
      }
    }
  }
}

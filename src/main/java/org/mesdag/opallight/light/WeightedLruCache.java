package org.mesdag.opallight.light;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 带条目数和字节权重双重预算的访问顺序缓存。
 *
 * <p>预算只约束可以重新计算的加速项，不得把光源注册表、已发布光场等真实状态放进本缓存。
 * 预算不足时只淘汰最久未访问的加速项，正确结果必须能够由真实状态重新构建。</p>
 *
 * <p>缓存取得每个映射值的所有权。映射因替换、显式删除、条件删除、清空或预算淘汰而离开
 * 缓存时，都会恰好调用一次 disposer。同一 key 再次放入同一个对象引用视为更新该映射的
 * 权重，所有权没有离开缓存，因此更新时不调用 disposer；若更新后的映射又被预算淘汰，
 * 则仍会调用一次。不要把同一个无租约资源同时放在多个 key 下，因为每个映射都独立拥有值。</p>
 */
final class WeightedLruCache<K, V> {
    private final Map<K, Entry<V>> entries = new LinkedHashMap<>(16, 0.75F, true);
    private final long maxEntries;
    private final long maxWeight;
    private final Consumer<? super V> disposer;
    private long totalWeight;

    WeightedLruCache(long maxEntries, long maxWeight) {
        this(maxEntries, maxWeight, ignored -> {
        });
    }

    WeightedLruCache(long maxEntries, long maxWeight, Consumer<? super V> disposer) {
        if (maxEntries <= 0L || maxWeight <= 0L) {
            throw new IllegalArgumentException("缓存预算必须为正数");
        }
        this.maxEntries = maxEntries;
        this.maxWeight = maxWeight;
        this.disposer = Objects.requireNonNull(disposer, "disposer");
    }

    V get(K key) {
        Entry<V> entry = entries.get(key);
        return entry == null ? null : entry.value;
    }

    void put(K key, V value, long weight) {
        if (weight < 0L) {
            throw new IllegalArgumentException("缓存权重不能为负数");
        }

        Entry<V> previous = entries.remove(key);
        if (previous != null) {
            totalWeight -= previous.weight;
        }

        Entry<V> added = new Entry<>(value, weight);
        entries.put(key, added);

        RuntimeException failure = null;
        if (previous != null && previous.value != value) {
            failure = dispose(previous.value, failure);
        }

        boolean addedStillOwned = true;
        while (entries.size() > maxEntries || weight > maxWeight - totalWeight) {
            var iterator = entries.entrySet().iterator();
            Entry<V> eldest = iterator.next().getValue();
            iterator.remove();

            if (eldest == added) {
                addedStillOwned = false;
            } else {
                totalWeight -= eldest.weight;
            }
            failure = dispose(eldest.value, failure);

            // 新条目是访问顺序中的最后一项；一旦连它也被淘汰，旧缓存已经恢复到合法预算。
            if (!addedStillOwned) {
                break;
            }
        }

        if (addedStillOwned) {
            // 上面的差值比较已证明加法不会溢出，而且结果不超过 maxWeight。
            totalWeight += weight;
        }
        rethrow(failure);
    }

    V remove(K key) {
        Entry<V> removed = entries.remove(key);
        if (removed == null) {
            return null;
        }
        totalWeight -= removed.weight;
        RuntimeException failure = dispose(removed.value, null);
        rethrow(failure);
        return removed.value;
    }

    void removeValuesIf(Predicate<V> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        RuntimeException failure = null;
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry<V> entry = iterator.next().getValue();
            final boolean remove;
            try {
                remove = predicate.test(entry.value);
            } catch (RuntimeException predicateFailure) {
                if (failure != null) {
                    predicateFailure.addSuppressed(failure);
                }
                throw predicateFailure;
            }
            if (remove) {
                totalWeight -= entry.weight;
                iterator.remove();
                failure = dispose(entry.value, failure);
            }
        }
        rethrow(failure);
    }

    void clear() {
        // 先恢复内部不变量，再逐项释放；即使 disposer 抛错，缓存也不会保留半清理状态。
        var removedEntries = new ArrayList<>(entries.values());
        entries.clear();
        totalWeight = 0L;

        RuntimeException failure = null;
        for (Entry<V> entry : removedEntries) {
            failure = dispose(entry.value, failure);
        }
        rethrow(failure);
    }

    int size() {
        return entries.size();
    }

    long totalWeight() {
        return totalWeight;
    }

    /**
     * LinkedHashMap 使用访问顺序，因此迭代器的第一个元素就是最久未使用的加速项。
     * 新条目的权重暂不计入 totalWeight，先通过差值比较淘汰旧项，从而避免 long 加法溢出。
     */
    private RuntimeException dispose(V value, RuntimeException previousFailure) {
        try {
            disposer.accept(value);
        } catch (RuntimeException currentFailure) {
            if (previousFailure == null) {
                return currentFailure;
            }
            previousFailure.addSuppressed(currentFailure);
        }
        return previousFailure;
    }

    private static void rethrow(RuntimeException failure) {
        if (failure != null) {
            throw failure;
        }
    }

    private record Entry<V>(V value, long weight) {
    }
}

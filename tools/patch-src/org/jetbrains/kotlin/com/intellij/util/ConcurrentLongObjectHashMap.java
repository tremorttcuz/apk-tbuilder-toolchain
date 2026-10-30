package org.jetbrains.kotlin.com.intellij.util;

import org.jetbrains.kotlin.com.intellij.util.containers.ConcurrentLongObjectMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Замена для Android/ART: оригинал использует sun.misc.Unsafe.copyMemory, которого в ART нет. */
final class ConcurrentLongObjectHashMap<V> implements ConcurrentLongObjectMap<V> {
    private final ConcurrentHashMap<Long, V> map = new ConcurrentHashMap<Long, V>();

    public int size() { return map.size(); }
    public boolean isEmpty() { return map.isEmpty(); }
    public V get(long key) { return map.get(key); }
    public V put(long key, V value) { return map.put(key, value); }
    public V remove(long key) { return map.remove(key); }
    public void clear() { map.clear(); }
    public V putIfAbsent(long key, V value) { return map.putIfAbsent(key, value); }
    public boolean remove(long key, Object value) { return map.remove(key, value); }
    public long mappingCount() { return map.mappingCount(); }
    public int hashCode() { return map.hashCode(); }
    public String toString() { return map.toString(); }
    public boolean equals(Object o) { return o instanceof ConcurrentLongObjectHashMap && map.equals(((ConcurrentLongObjectHashMap<?>) o).map); }

    public Iterable<ConcurrentLongObjectMap.LongEntry<V>> entries() {
        List<ConcurrentLongObjectMap.LongEntry<V>> out = new ArrayList<ConcurrentLongObjectMap.LongEntry<V>>();
        for (Map.Entry<Long, V> e : map.entrySet()) {
            final long k = e.getKey(); final V v = e.getValue();
            out.add(new ConcurrentLongObjectMap.LongEntry<V>() {
                public long getKey() { return k; }
                public V getValue() { return v; }
            });
        }
        return out;
    }
}

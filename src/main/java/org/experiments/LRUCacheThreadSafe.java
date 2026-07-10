package org.experiments;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class LRUCacheThreadSafe<U, V> extends LRUCache<U, V> {

    private final Lock lock;

    public LRUCacheThreadSafe(int capacity) {
        super(capacity);

        this.lock = new ReentrantLock();
    }

    @Override
    public V get(U key) {
        lock.lock();

        try {
            return super.get(key);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void put(U key, V value) {
        lock.lock();

        try {
            super.put(key, value);
        } finally {
            lock.unlock();
        }
    }


}

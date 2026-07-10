package org.experiments;

public interface Cache<U, V> {
    V get(U key);
    void put(U key, V value);
}

package org.experiments.cache;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class LRUCache<U, V> implements Cache<U, V> {

    private final int capacity;
    private final Map<U, Node<U,V>> registry;

    private final Node<U, V> head;
    private final Node<U, V> tail;

    protected static class Node<U,V> {
        U key;
        V value;
        Node<U,V> prev, next;

        public Node() {}

        public Node(U key, V value) {
            this.key = key;
            this.value = value;
        }

        public String toString() {
            return String.format("[%s:%s]", this.key, this.value);
        }
    }

    public LRUCache(int capacity) {
        this.capacity = capacity;
        this.registry = new HashMap<>();

        this.head = new Node<>();
        this.tail = new Node<>();

        this.head.next = this.tail;
        this.tail.prev = this.head;
    }


    @Override
    public V get(U key) {
        var node = registry.get(key);

        if (node == null) return null;

        moveToFront(node);

        return node.value;
    }

    @Override
    public void put(U key, V value) {
        Objects.requireNonNull(value);
        var node = registry.get(key);

        if (node != null) {
            node.value = value;
            moveToFront(node);
        } else {
            node = new Node<>(key, value);
            registry.put(key, node);
            addNode(node);
        }

        if (isOverCapacity()) evict();
    }

    public int size() {
        return registry.size();
    }

    private void moveToFront(Node<U,V> node) {
        remove(node);
        addNode(node);
    }

    protected boolean isOverCapacity() {
        return registry.size() > capacity;
    }

    protected void evict() {
        var evicted = getLRU();
        remove(evicted);
        registry.remove(evicted.key);
    }

    private Node<U,V> getLRU() {
        return head.next;
    }

    protected void addNode(Node<U,V> node) {
        tail.prev.next = node;
        node.prev = tail.prev;
        node.next = tail;
        tail.prev = node;

    }

    protected void remove(Node<U, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }
}

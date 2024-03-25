package com.google.devtools.build.lib.collect;

public interface Trie<ValueT, KeyT> {

    Node insert(ValueT data);

    boolean exists(ValueT data);

    interface Node<ValueT, KeyT> {

        Node get(KeyT key);

        ValueT getValue();

    }
}

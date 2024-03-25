package com.google.devtools.build.lib.vfs;

import com.google.devtools.build.lib.collect.Trie;
import com.google.errorprone.annotations.Immutable;

import java.util.*;

import static com.google.devtools.build.lib.vfs.PathFragment.SEPARATOR_CHAR;

public final class PathFragmentTrie implements Trie<String, String> {

    private PathFragmentNode root;

    public PathFragmentTrie() {
        this.root = new PathFragmentNode(null, null);
    }

    @Override
    public PathFragmentNode insert(String data) {
        PathFragmentNode current = root;
        Iterator<String> segments = Arrays.stream(data.split(String.valueOf(SEPARATOR_CHAR))).iterator();
        while (segments.hasNext()) {
            String segment = segments.next();
            PathFragmentNode next = current.get(segment);
            if (next == null) {
                PathFragmentNode newNode = new PathFragmentNode(segment, current);
                current.put(segment, newNode);
                next = newNode;
            }
            current = next;
        }
        return current;
    }

    @Override
    public boolean exists(String data) {
        return false;
    }

    public final class PathFragmentNode implements Trie.Node<String, String> {

        private final String segment;
        private final PathFragmentNode parent;
        private final HashMap<String, PathFragmentNode> map = new HashMap();

        public PathFragmentNode(String segment, PathFragmentNode parent) {
            this.segment = segment;
            this.parent = parent;
        }

        @Override
        public PathFragmentNode get(String k) {
            return map.get(k);
        }

        public PathFragmentNode put(String k, PathFragmentNode p) {
            if (map.get(k) != null) {
                return map.get(k);
            }
            return map.put(k, p);
        }

        @Override
        public String getValue() {
            LinkedList<String> path = new LinkedList<>();
            PathFragmentNode current = this;
            while (current != null && current.segment != null) {
                path.add(current.segment);
                current = current.parent;
            }
            return String.join(String.valueOf(SEPARATOR_CHAR), path.reversed());
        }
    }
}

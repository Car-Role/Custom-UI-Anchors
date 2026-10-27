/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Small in-memory log of recent plugin events (profile switches, captures, releases,
 * canvas changes, errors) for the "Copy diagnostics" report. Always on and bounded, so
 * users don't have to enable debug logging or dig through client.log to share what
 * happened before a problem. Thread-safe: written from the client thread and the AWT
 * thread, read from the EDT.
 */
class DiagnosticEvents {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final int capacity;
    private final Deque<String[]> entries = new ArrayDeque<>();

    DiagnosticEvents(int capacity) {
        this.capacity = capacity;
    }

    /** Record an event. */
    void add(String message) {
        add(null, message);
    }

    /**
     * Record an event, replacing the newest entry instead when it has the same non-null
     * {@code kind}. Keeps bursts (e.g. a window being drag-resized) to a single line.
     */
    synchronized void add(String kind, String message) {
        String line = LocalTime.now().format(TIME) + "  " + message;
        String[] last = entries.peekLast();
        if (kind != null && last != null && kind.equals(last[0])) {
            last[1] = line;
            return;
        }
        entries.addLast(new String[]{kind, line});
        while (entries.size() > capacity) {
            entries.removeFirst();
        }
    }

    /** Oldest-first copy of the recorded lines. */
    synchronized List<String> snapshot() {
        List<String> out = new ArrayList<>(entries.size());
        for (String[] e : entries) {
            out.add(e[1]);
        }
        return out;
    }

    synchronized void clear() {
        entries.clear();
    }
}

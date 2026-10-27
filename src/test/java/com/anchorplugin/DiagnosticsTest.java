/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Tests for the diagnostics report: event buffer, layout, redaction, size cap and issue URL. */
public class DiagnosticsTest {

    @Test
    public void eventsAreBoundedAndOldestFirst() {
        DiagnosticEvents events = new DiagnosticEvents(3);
        for (int i = 1; i <= 5; i++) {
            events.add("event " + i);
        }
        List<String> lines = events.snapshot();
        assertEquals(3, lines.size());
        assertTrue(lines.get(0).endsWith("event 3"));
        assertTrue(lines.get(2).endsWith("event 5"));
    }

    @Test
    public void sameKindReplacesNewestEntry() {
        DiagnosticEvents events = new DiagnosticEvents(10);
        events.add("start");
        events.add("canvas", "Canvas resized to 800x600");
        events.add("canvas", "Canvas resized to 900x700");
        events.add("next");
        events.add("canvas", "Canvas resized to 1000x800");
        List<String> lines = events.snapshot();
        assertEquals(4, lines.size());
        assertTrue(lines.get(1).endsWith("900x700"));
        assertTrue(lines.get(3).endsWith("1000x800"));
    }

    private static Map<String, String> summary() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("RuneLite", "1.13.4");
        s.put("Plugins to note", "117 HD | GPU");
        return s;
    }

    @Test
    public void rendersSummaryTableAndCollapsibleSections() {
        Map<String, String> sections = new LinkedHashMap<>();
        sections.put("Anchors", "[{\"id\":1}]");
        String report = DiagnosticsReport.render(summary(), sections,
                Collections.singletonList("12:00:00.000  Plugin started"), Collections.emptyMap());

        assertTrue(report.startsWith("### Custom UI Anchors diagnostics"));
        assertTrue(report.contains("| RuneLite | 1.13.4 |"));
        // A pipe inside a value must not break the table row.
        assertTrue(report.contains("| Plugins to note | 117 HD \\| GPU |"));
        assertTrue(report.contains("<details><summary>Anchors</summary>\n\n```json\n[{\"id\":1}]\n```"));
        assertTrue(report.contains("<details><summary>Recent events (1)</summary>"));
        assertTrue(report.contains("Plugin started"));
    }

    @Test
    public void redactsHomePathAndPlayerName() {
        Map<String, String> sections = new LinkedHashMap<>();
        sections.put("Paths", "C:\\Users\\Alice\\.runelite and C:/Users/Alice/x, player Zezima");
        Map<String, String> redactions = new LinkedHashMap<>();
        redactions.put("C:\\Users\\Alice", "~");
        redactions.put("C:/Users/Alice", "~");
        redactions.put("Zezima", "<player>");
        redactions.put("ab", "never"); // too short to redact safely
        String report = DiagnosticsReport.render(summary(), sections, Collections.emptyList(), redactions);

        assertFalse(report.contains("Alice"));
        assertFalse(report.contains("Zezima"));
        assertTrue(report.contains("~\\.runelite and ~/x, player <player>"));
        assertFalse(report.contains("never"));
    }

    @Test
    public void dropsOldestEventsToStayUnderTheCap() {
        List<String> events = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            events.add(String.format("%05d  %s", i, new String(new char[60]).replace('\0', 'x')));
        }
        String report = DiagnosticsReport.render(summary(), new LinkedHashMap<>(), events, Collections.emptyMap());

        assertTrue(report.length() <= DiagnosticsReport.MAX_REPORT_CHARS);
        assertTrue("newest event kept", report.contains("01999  "));
        assertFalse("oldest event dropped", report.contains("00000  "));
        assertTrue(report.contains("Recent events (last "));
    }

    @Test
    public void codeFencesInDataCannotCloseTheBlock() {
        Map<String, String> sections = new LinkedHashMap<>();
        sections.put("Anchors", "name ``` injected");
        String report = DiagnosticsReport.render(summary(), sections, Collections.emptyList(), Collections.emptyMap());
        assertFalse(report.contains("name ```"));
    }

    @Test
    public void issueUrlPrefillsOnlyWhenItFits() {
        String small = DiagnosticsReport.issueUrl("### short report");
        assertTrue(small.startsWith(DiagnosticsReport.NEW_ISSUE_URL + "&diagnostics="));
        assertTrue(small.length() <= DiagnosticsReport.MAX_URL_CHARS);

        String big = DiagnosticsReport.issueUrl(new String(new char[10_000]).replace('\0', 'x'));
        assertEquals(DiagnosticsReport.NEW_ISSUE_URL, big);
    }

    @Test
    public void jsonLinesIsValidJsonWithOneObjectPerLine() {
        Gson g = new Gson().newBuilder().disableHtmlEscaping().create();
        JsonArray array = new JsonArray();
        JsonObject a = new JsonObject();
        a.addProperty("key", "Timer=1");
        array.add(a);
        array.add(new JsonObject());
        String text = AnchorCustomizerPlugin.jsonLines(g, array);

        assertEquals("[\n  {\"key\":\"Timer=1\"},\n  {}\n]", text);
        assertEquals(array, g.fromJson(text, JsonElement.class));
        assertEquals("[]", AnchorCustomizerPlugin.jsonLines(g, new JsonArray()));
    }
}

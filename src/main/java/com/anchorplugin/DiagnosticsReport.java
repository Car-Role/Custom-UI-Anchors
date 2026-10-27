/*
 * Copyright (c) 2024, Car_role
 * All rights reserved.
 */
package com.anchorplugin;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Formats the "Copy diagnostics" report as GitHub Markdown and builds the prefilled
 * bug-report URL. Pure string work so it can be unit-tested without a client: the
 * plugin gathers the data, this class lays it out, redacts it and keeps it within
 * GitHub's limits.
 */
final class DiagnosticsReport {
    static final String REPO_URL = "https://github.com/Car-Role/Custom-UI-Anchors";
    static final String NEW_ISSUE_URL = REPO_URL + "/issues/new?template=bug_report.yml";

    // GitHub caps an issue body at 65,536 characters; leave room for the user's own text.
    static final int MAX_REPORT_CHARS = 50_000;
    // GitHub answers 414 above roughly 8k characters of URL.
    static final int MAX_URL_CHARS = 7_500;

    private DiagnosticsReport() {
    }

    /**
     * Render the report: a summary table on top, then one collapsible block per section
     * and a final block with the recent events. Oldest events are dropped first if the
     * report would exceed {@link #MAX_REPORT_CHARS}; anything still over is cut short.
     *
     * @param summary    label -> value rows for the table, in order
     * @param sections   title -> JSON text, in order
     * @param events     recent events, oldest first
     * @param redactions literal text -> replacement, applied to the whole report
     */
    static String render(Map<String, String> summary, Map<String, String> sections,
                         List<String> events, Map<String, String> redactions) {
        List<String> kept = new ArrayList<>(events);
        String report = redact(build(summary, sections, kept, events.size()), redactions);
        while (report.length() > MAX_REPORT_CHARS && !kept.isEmpty()) {
            kept = new ArrayList<>(kept.subList(kept.size() / 2, kept.size()));
            if (kept.size() == 1) {
                kept.clear();
            }
            report = redact(build(summary, sections, kept, events.size()), redactions);
        }
        if (report.length() > MAX_REPORT_CHARS) {
            report = report.substring(0, MAX_REPORT_CHARS) + "\n\n(cut short)\n";
        }
        return report;
    }

    private static String build(Map<String, String> summary, Map<String, String> sections,
                                List<String> events, int totalEvents) {
        StringBuilder b = new StringBuilder();
        b.append("### Custom UI Anchors diagnostics\n\n");
        b.append("| | |\n|---|---|\n");
        for (Map.Entry<String, String> row : summary.entrySet()) {
            b.append("| ").append(cell(row.getKey())).append(" | ").append(cell(row.getValue())).append(" |\n");
        }
        for (Map.Entry<String, String> section : sections.entrySet()) {
            details(b, section.getKey(), "json", section.getValue());
        }
        String title = events.size() == totalEvents
                ? "Recent events (" + totalEvents + ")"
                : "Recent events (last " + events.size() + " of " + totalEvents + ")";
        details(b, title, "", events.isEmpty() ? "(none)" : String.join("\n", events));
        return b.toString();
    }

    private static void details(StringBuilder b, String title, String lang, String body) {
        b.append("\n<details><summary>").append(title).append("</summary>\n\n```").append(lang).append('\n')
                .append(body.replace("```", "'''")).append("\n```\n\n</details>\n");
    }

    private static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    /** Replace each non-trivial key (3+ characters) with its replacement. */
    static String redact(String text, Map<String, String> redactions) {
        String out = text;
        for (Map.Entry<String, String> r : redactions.entrySet()) {
            String secret = r.getKey();
            if (secret != null && secret.length() >= 3) {
                out = out.replace(secret, r.getValue());
            }
        }
        return out;
    }

    /**
     * The bug form URL. The report goes into the form's Diagnostics field when it fits
     * in a URL; otherwise the field is left empty for the user to paste into.
     */
    static String issueUrl(String report) {
        String withReport = NEW_ISSUE_URL + "&diagnostics=" + encode(report);
        return withReport.length() <= MAX_URL_CHARS ? withReport : NEW_ISSUE_URL;
    }

    private static String encode(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}

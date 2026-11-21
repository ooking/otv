package com.xiaomitv.dtmb;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.xiaomitv.dtmb.TvListings.Program;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

final class GuideCache extends SQLiteOpenHelper {
    static final long WINDOW = TimeUnit.DAYS.toMillis(7);
    static final int SYSTEM = 0;
    static final int XMLTV = 1;

    private static final int MAX_PROGRAMS = 2048;
    private static final Comparator<Program> BY_START = Comparator.comparingLong(Program::start)
            .thenComparingLong(Program::end).thenComparing(Program::title);

    GuideCache(Context context) {
        super(context, "programs.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE guide (channel TEXT NOT NULL, source INTEGER NOT NULL,"
                + " starts_at INTEGER NOT NULL, ends_at INTEGER NOT NULL, title TEXT NOT NULL,"
                + " PRIMARY KEY (channel, source, starts_at))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unsupported guide schema upgrade");
    }

    Map<String, List<Program>> read(long now) {
        Map<String, List<Program>> system = readSource(getReadableDatabase(), SYSTEM, now);
        Map<String, List<Program>> xmltv = readSource(getReadableDatabase(), XMLTV, now);
        for (Map.Entry<String, List<Program>> entry : system.entrySet()) {
            xmltv.put(entry.getKey(), merge(xmltv.get(entry.getKey()), entry.getValue()));
        }
        return xmltv;
    }

    void update(int source, Map<String, List<Program>> incoming, long now) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("guide", "ends_at<=?", new String[]{Long.toString(now)});
            Map<String, List<Program>> previous = readSource(db, source, now);
            for (Map.Entry<String, List<Program>> entry : incoming.entrySet()) {
                String channel = entry.getKey();
                List<Program> old = previous.getOrDefault(channel, Collections.emptyList());
                List<Program> updated = merge(old, entry.getValue());
                if (updated.equals(old)) continue;

                db.delete("guide", "channel=? AND source=?",
                        new String[]{channel, Integer.toString(source)});
                for (Program program : updated) {
                    ContentValues row = new ContentValues();
                    row.put("channel", channel);
                    row.put("source", source);
                    row.put("starts_at", program.start());
                    row.put("ends_at", program.end());
                    row.put("title", program.title());
                    db.insertOrThrow("guide", null, row);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private Map<String, List<Program>> readSource(SQLiteDatabase db, int source, long now) {
        Map<String, List<Program>> result = new HashMap<>();
        try (Cursor rows = db.query("guide", new String[]{"channel", "starts_at", "ends_at", "title"},
                "source=? AND ends_at>? AND starts_at<?",
                new String[]{Integer.toString(source), Long.toString(now),
                        Long.toString(now + WINDOW)}, null, null, "channel, starts_at")) {
            while (rows.moveToNext()) {
                result.computeIfAbsent(rows.getString(0), ignored -> new ArrayList<>())
                        .add(new Program(rows.getString(3), rows.getLong(1), rows.getLong(2)));
            }
        }
        return result;
    }

    // New or higher-priority rows replace overlapping slots, preserving non-overlapping coverage.
    private static List<Program> merge(List<Program> cached, List<Program> fresh) {
        TreeMap<Long, Program> slots = new TreeMap<>();
        if (cached != null) {
            for (Program program : cached) slots.put(program.start(), program);
        }
        List<Program> sorted = new ArrayList<>(fresh);
        sorted.sort(BY_START);
        for (Program program : sorted) {
            Map.Entry<Long, Program> previous = slots.floorEntry(program.start());
            if (previous != null && previous.getValue().end() > program.start()) {
                slots.remove(previous.getKey());
            }
            slots.subMap(program.start(), true, program.end(), false).clear();
            slots.put(program.start(), program);
        }
        List<Program> result = new ArrayList<>(Math.min(MAX_PROGRAMS, slots.size()));
        for (Program program : slots.values()) {
            if (result.size() == MAX_PROGRAMS) break;
            result.add(program);
        }
        return result;
    }
}

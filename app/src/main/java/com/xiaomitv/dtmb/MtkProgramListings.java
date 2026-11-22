package com.xiaomitv.dtmb;

import android.content.ContentResolver;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Optional firmware API. Only query the channel the user has actually tuned.
final class MtkProgramListings {
    private final ContentResolver resolver;
    private boolean unavailable;
    private Object service;
    private Method events, channelId, svlId, startTime, duration, title, detail, extended;

    MtkProgramListings(ContentResolver resolver) {
        this.resolver = resolver;
    }

    List<TvListings.Program> read(TvListings.Channel channel, long now, long until,
                                  CancellationSignal signal) {
        if (unavailable) return Collections.emptyList();
        try {
            if (service == null) bind();
            String[] columns = {"internal_provider_flag1", "internal_provider_flag3"};
            int listId;
            int nativeId;
            try (Cursor cursor = resolver.query(TvContract.buildChannelUri(channel.id()),
                    columns, null, null, null, signal)) {
                if (cursor == null || !cursor.moveToFirst() || cursor.isNull(0) || cursor.isNull(1)) {
                    return Collections.emptyList();
                }
                listId = cursor.getInt(0);
                nativeId = cursor.getInt(1);
            }
            signal.throwIfCanceled();
            Object result = events.invoke(service, nativeId, now / 1000, until / 1000);
            if (!(result instanceof List<?> rows)) return Collections.emptyList();
            List<TvListings.Program> programs = new ArrayList<>();
            for (Object row : rows) {
                signal.throwIfCanceled();
                if (row == null || (int) channelId.invoke(row) != nativeId
                        || (int) svlId.invoke(row) != listId) continue;
                long start = (long) startTime.invoke(row);
                long length = (long) duration.invoke(row);
                if (start <= 0 || start > until / 1000 || length <= 0
                        || length > Long.MAX_VALUE / 1000 - start) continue;
                long end = (start + length) * 1000;
                String name = (String) title.invoke(row);
                if (start * 1000 >= until || end <= now || name == null || name.trim().isEmpty()) continue;
                String description = (String) extended.invoke(row);
                if (description == null || description.trim().isEmpty()) description = (String) detail.invoke(row);
                programs.add(new TvListings.Program(name.trim(), start * 1000, end, description));
            }
            return programs;
        } catch (OperationCanceledException canceled) {
            throw canceled;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            unavailable = true;
            Log.i("DtmbGuide", "MTK guide unavailable: " + failure.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    private void bind() throws ReflectiveOperationException {
        Class<?> api = Class.forName("com.mediatek.twoworlds.tv.MtkTvEvent");
        Class<?> row = Class.forName("com.mediatek.twoworlds.tv.model.MtkTvEventInfoBase");
        events = api.getMethod("getEventListByChannelId", int.class, long.class, long.class);
        channelId = row.getMethod("getChannelId");
        svlId = row.getMethod("getSvlId");
        startTime = row.getMethod("getStartTime");
        duration = row.getMethod("getDuration");
        title = row.getMethod("getEventTitle");
        detail = row.getMethod("getEventDetail");
        extended = row.getMethod("getEventDetailExtend");
        service = api.getMethod("getInstance").invoke(null);
    }
}

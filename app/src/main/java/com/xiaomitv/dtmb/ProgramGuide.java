package com.xiaomitv.dtmb;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.OperationCanceledException;
import android.os.SystemClock;
import android.util.Log;

import com.xiaomitv.dtmb.TvListings.Channel;
import com.xiaomitv.dtmb.TvListings.Program;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class ProgramGuide {
    private static final String TAG = "DtmbGuide";
    private static final long MIN_REFRESH_INTERVAL = 20_000;
    private static final long POLL_INTERVAL = 60_000;

    private final Context context;
    private final Handler handler;
    private final TvListings listings;
    private final ContentResolver resolver;
    private final GuideCache cache;
    private final MtkProgramListings mtk;
    private final Runnable onChange;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable refresh = this::refresh;
    private final Runnable poll = this::poll;

    private final Runnable tunedRefresh = this::refreshTunedChannel;
    private Channel tunedChannel;
    private CancellationSignal tunedQuery;
    private int tunedAttempts;

    private List<Channel> channels = Collections.emptyList();
    private Map<String, List<Program>> snapshot = Collections.emptyMap();
    private CancellationSignal query;
    private long lastRefresh = -MIN_REFRESH_INTERVAL;
    private long scheduledAt = Long.MAX_VALUE;
    private boolean active;
    private boolean closed;
    private boolean refreshPending;

    ProgramGuide(Context context, TvListings listings, Handler handler, Runnable onChange) {
        this.context = context;
        this.handler = handler;
        this.listings = listings;
        this.onChange = onChange;
        resolver = context.getContentResolver();
        mtk = new MtkProgramListings(resolver);
        cache = new GuideCache(context.getApplicationContext());
        worker.execute(this::publishCache);
    }

    void start() {
        active = true;
        requestRefresh(0);
        handler.removeCallbacks(poll);
        handler.postDelayed(poll, POLL_INTERVAL);
    }

    void stop() {
        active = false;
        cancelTunedRefresh();
        refreshPending = false;
        scheduledAt = Long.MAX_VALUE;
        handler.removeCallbacks(refresh);
        handler.removeCallbacks(poll);
        if (query != null) query.cancel();
    }

    void close() {
        stop();
        closed = true;
        worker.execute(cache::close);
        worker.shutdown();
    }

    void setChannels(List<Channel> channels) {
        if (this.channels.equals(channels)) return;
        this.channels = channels;
        requestRefresh(0);
    }

    void requestRefresh(long delayMs) {
        if (!active || channels.isEmpty()) return;
        if (query != null) {
            refreshPending = true;
            return;
        }
        long now = SystemClock.uptimeMillis();
        long when = Math.max(now + delayMs, lastRefresh + MIN_REFRESH_INTERVAL);
        if (when >= scheduledAt) return;
        scheduledAt = when;
        handler.removeCallbacks(refresh);
        handler.postAtTime(refresh, when);
    }

    void cancelTunedRefresh() {
        handler.removeCallbacks(tunedRefresh);
        if (tunedQuery != null) tunedQuery.cancel();
        tunedQuery = null;
        tunedChannel = null;
    }

    void refreshAfterTune(Channel channel) {
        cancelTunedRefresh();
        if (!active) return;
        tunedChannel = channel;
        tunedAttempts = 0;
        handler.postDelayed(tunedRefresh, 1_000);
    }

    void onProgramsChanged() {
        requestRefresh(750);
        if (tunedChannel != null && tunedQuery == null) {
            handler.removeCallbacks(tunedRefresh);
            handler.post(tunedRefresh);
        }
    }

    private void refreshTunedChannel() {
        Channel channel = tunedChannel;
        if (channel == null) return;
        CancellationSignal signal = new CancellationSignal();
        tunedQuery = signal;
        tunedAttempts++;
        worker.execute(() -> {
            boolean currentFound = refreshSystem(Collections.singletonList(channel), signal, true);
            handler.post(() -> {
                if (tunedQuery != signal) return;
                tunedQuery = null;
                if (currentFound || tunedAttempts >= 3) {
                    tunedChannel = null;
                } else {
                    handler.postDelayed(tunedRefresh, tunedAttempts * 3_000L);
                }
            });
        });
    }

    List<Program> schedule(Channel channel) {
        return snapshot.getOrDefault(channel.guideKey(), Collections.emptyList());
    }

    void importXmlTv(Uri uri, Consumer<String> result) {
        importPrograms((lineup, now) -> XmlTvImporter.read(resolver, uri, lineup, now), result);
    }

    void downloadXmlTv(URL url, Consumer<String> result) {
        importPrograms((lineup, now) -> XmlTvImporter.download(url, lineup, now), result);
    }

    private interface ImportSource {
        Map<String, List<Program>> read(List<Channel> lineup, long now)
                throws IOException, XmlPullParserException;
    }

    private void importPrograms(ImportSource source, Consumer<String> result) {
        List<Channel> lineup = channels;
        worker.execute(() -> {
            String message;
            try {
                long now = System.currentTimeMillis();
                Map<String, List<Program>> programs = source.read(lineup, now);
                if (programs.isEmpty()) {
                    message = context.getString(R.string.xmltv_result_no_match);
                } else {
                    cache.update(GuideCache.XMLTV, programs, now);
                    publishCache();
                    message = context.getString(R.string.xmltv_result_imported, programs.size());
                }
            } catch (IOException | XmlPullParserException | RuntimeException failure) {
                Log.w(TAG, "XMLTV import failed: " + failure.getClass().getSimpleName());
                message = context.getString(R.string.xmltv_result_failed);
            }
            String outcome = message;
            handler.post(() -> {
                if (!closed) result.accept(outcome);
            });
        });
    }

    private void poll() {
        requestRefresh(0);
        onChange.run();
        handler.postDelayed(poll, POLL_INTERVAL);
    }

    private void refresh() {
        scheduledAt = Long.MAX_VALUE;
        if (!active || channels.isEmpty()) return;
        List<Channel> lineup = channels;
        CancellationSignal signal = new CancellationSignal();
        query = signal;
        lastRefresh = SystemClock.uptimeMillis();
        worker.execute(() -> {
            refreshSystem(lineup, signal, false);
            handler.post(() -> {
                if (closed || query != signal) return;
                query = null;
                if (refreshPending) {
                    refreshPending = false;
                    requestRefresh(0);
                }
                onChange.run();
            });
        });
    }

    private boolean refreshSystem(List<Channel> lineup, CancellationSignal signal, boolean tuned) {
        try {
            signal.throwIfCanceled();
            long now = System.currentTimeMillis();
            Map<String, List<Program>> programs = listings.programs(
                    lineup, now, now + GuideCache.WINDOW, signal);
            signal.throwIfCanceled();
            cache.update(GuideCache.SYSTEM, programs, now);
            boolean currentFound = false;
            for (Channel channel : lineup) {
                List<Program> rows = programs.getOrDefault(channel.guideKey(), Collections.emptyList());
                long until = 0;
                for (Program row : rows) {
                    until = Math.max(until, row.end());
                    if (row.start() <= now && now < row.end()) currentFound = true;
                }
                Log.d(TAG, "system_coverage channel=" + channel.id()
                        + " rows=" + rows.size() + " last_end=" + until
                        + " descriptions=" + rows.stream().filter(row -> !row.description().isEmpty()).count());
            }
            if (tuned) {
                Channel channel = lineup.get(0);
                List<Program> extra = mtk.read(channel, now, now + GuideCache.WINDOW, signal);
                signal.throwIfCanceled();
                if (!extra.isEmpty()) {
                    cache.update(GuideCache.MTK, Collections.singletonMap(channel.guideKey(), extra), now);
                    currentFound |= extra.stream().anyMatch(row -> row.start() <= now && now < row.end());
                }
                Log.d(TAG, "mtk_coverage channel=" + channel.id() + " rows=" + extra.size()
                        + " last_end=" + extra.stream().mapToLong(Program::end).max().orElse(0)
                        + " descriptions=" + extra.stream().filter(row -> !row.description().isEmpty()).count());
            }
            publishCache();
            return currentFound;
        } catch (OperationCanceledException ignored) {
            // A lifecycle change canceled the system query.
        } catch (RuntimeException failure) {
            Log.w(TAG, "System program query failed", failure);
        }
        return false;
    }

    private void publishCache() {
        Map<String, List<Program>> data;
        try {
            data = cache.read(System.currentTimeMillis());
        } catch (RuntimeException failure) {
            Log.w(TAG, "Cannot read cached guide", failure);
            data = Collections.emptyMap();
        }
        Map<String, List<Program>> published = data;
        handler.post(() -> {
            if (closed) return;
            snapshot = published;
            onChange.run();
        });
    }
}

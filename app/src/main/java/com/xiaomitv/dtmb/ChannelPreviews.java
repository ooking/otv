package com.xiaomitv.dtmb;

import android.graphics.Bitmap;
import android.os.Handler;
import android.util.Log;
import android.util.LruCache;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

final class ChannelPreviews {
    private static final int WIDTH = 160, HEIGHT = 90;
    private final LruCache<String, Bitmap> images = new LruCache<>(32);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler;
    private volatile long generation;
    private volatile boolean unavailable;
    private Runnable pending;

    ChannelPreviews(Handler handler) {
        this.handler = handler;
    }

    Bitmap get(TvListings.Channel channel) {
        return images.get(channel.guideKey());
    }

    void forget(TvListings.Channel channel) {
        images.remove(channel.guideKey());
    }

    void cancel() {
        generation++;
        if (pending != null) handler.removeCallbacks(pending);
        pending = null;
    }

    void schedule(TvListings.Channel channel, BooleanSupplier ready, Runnable updated) {
        cancel();
        if (unavailable) return;
        long request = generation;
        pending = () -> {
            if (!ready.getAsBoolean()) return;
            worker.execute(() -> {
                if (request != generation) return;
                Bitmap image = capture();
                if (image == null) return;
                handler.post(() -> {
                    if (request != generation || !ready.getAsBoolean()) return;
                    images.put(channel.guideKey(), image);
                    updated.run();
                });
            });
        };
        handler.postDelayed(pending, 2000);
    }

    private Bitmap capture() {
        try {
            Class<?> context = Class.forName("mitv.tv.TvContext");
            Object instance = context.getMethod("getInstance").invoke(null);
            Object manager = context.getMethod("getTvViewManager").invoke(instance);
            if (manager == null) return null;
            Method capture = Class.forName("mitv.tv.TvViewManager")
                    .getMethod("screenCapture", int.class, int.class);
            Bitmap frame = (Bitmap) capture.invoke(manager, WIDTH, HEIGHT);
            return frame == null ? null : Bitmap.createScaledBitmap(frame, WIDTH, HEIGHT, true);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            unavailable = true;
            Log.i("DtmbPreview", "Channel capture unavailable", failure);
            return null;
        }
    }

    void close() {
        cancel();
        worker.shutdownNow();
        images.evictAll();
    }
}

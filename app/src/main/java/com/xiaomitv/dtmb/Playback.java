package com.xiaomitv.dtmb;

import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputManager;
import android.media.tv.TvTrackInfo;
import android.media.tv.TvView;
import android.os.Handler;
import android.util.Log;

import java.util.Collections;
import java.util.List;

final class Playback {
    static final String TAG = "DtmbPlayback";

    interface Listener {
        void onStatus(String status);
        void onTuning(long channel, boolean switching);
        void onTuneReady(long channel, String notice);
        void onTuneFailed();
        void onPreviewInvalidated(long channel, boolean blocked);
        void onTrackStateChanged();
        void onPlaybackActiveChanged(boolean active);
    }

    private final TvView view;
    private final AudioManager audio;
    private final XiaomiTvAudio xiaomiAudio;
    private final BroadcastSubtitles subtitles;
    private final Handler handler;
    private final Listener listener;
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build();
    private final Runnable tuneTimeout = () -> onTuneTimeout();

    private AudioFocusRequest focusRequest;
    private int focusGeneration;
    private boolean focused;
    private boolean videoAvailable;
    private boolean audioOnly;
    private boolean tuning;
    private boolean submitted;
    private int tuneGeneration;
    private long lastReadyChannel = -1;
    private long queuedChannel = -1;
    private String recoveryMessage = "";
    private boolean contentBlocked;
    private long channelId = -1;
    private int audioTrackCount;
    private String state = "未播放";
    private String focusState = "未请求";
    private boolean activePlayback;

    Playback(TvView view, AudioManager audio, SharedPreferences preferences, Handler handler,
             Listener listener) {
        this.view = view;
        this.audio = audio;
        xiaomiAudio = new XiaomiTvAudio(audio);
        subtitles = new BroadcastSubtitles(view, preferences);
        this.handler = handler;
        this.listener = listener;
        view.setCallback(new TvView.TvInputCallback() {
            @Override
            public void onVideoAvailable(String inputId) {
                if (!isActive(inputId) || (tuning && !submitted)) return;
                videoAvailable = true;
                audioOnly = false;
                if (contentBlocked) return;
                if (tuning) {
                    completeTune("");
                } else {
                    setState("画面已就绪", "");
                    applyVolume("video_available");
                    restoreSubtitles();
                    updatePlaybackActive(true);
                }
            }

            @Override
            public void onVideoUnavailable(String inputId, int reason) {
                if (!isActive(inputId) || contentBlocked || (tuning && !submitted)) return;
                listener.onPreviewInvalidated(channelId, false);
                videoAvailable = false;
                audioOnly = reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY;
                String message = switch (reason) {
                    case TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING -> "正在切换频道…";
                    case TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL -> "DTMB 信号较弱";
                    case TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING -> "正在等待直播信号…";
                    case TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY -> "当前为纯音频节目";
                    case TvInputManager.VIDEO_UNAVAILABLE_REASON_INSUFFICIENT_RESOURCE ->
                            "调谐器资源不足，请关闭其他直播应用后重试";
                    default -> "暂时没有画面";
                };
                Log.i(TAG, "video_unavailable channel=" + channelId + " reason=" + reason);
                if (tuning && audioOnly) {
                    completeTune(message);
                } else if (tuning && reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_INSUFFICIENT_RESOURCE) {
                    failTune(message);
                } else {
                    setState(message, tuning ? "" : message);
                    syncXiaomiAudio(false);
                    if (!tuning) updatePlaybackActive(audioOnly);
                }
            }

            @Override
            public void onConnectionFailed(String inputId) {
                onSessionLost(inputId, "无法连接电视调谐器");
            }

            @Override
            public void onDisconnected(String inputId) {
                onSessionLost(inputId, "电视调谐器已断开");
            }

            @Override
            public void onContentBlocked(String inputId, android.media.tv.TvContentRating rating) {
                if (!isActive(inputId) || (tuning && !submitted)) return;
                listener.onPreviewInvalidated(channelId, true);
                contentBlocked = true;
                updatePlaybackActive(false);
                String message = "此频道受系统家长控制限制";
                if (tuning && lastReadyChannel != -1 && lastReadyChannel != channelId) {
                    failTune(message);
                } else {
                    boolean wasTuning = tuning;
                    finishRequest();
                    queuedChannel = -1;
                    setState(message, message);
                    applyVolume("content_blocked");
                    if (wasTuning) listener.onTuneFailed();
                }
            }

            @Override
            public void onContentAllowed(String inputId) {
                if (!isActive(inputId) || !contentBlocked) return;
                contentBlocked = false;
                String message = "正在等待直播信号…";
                setState(videoAvailable ? "画面已就绪" : message, videoAvailable ? "" : message);
                applyVolume("content_allowed");
                restoreSubtitles();
                if (videoAvailable) {
                    lastReadyChannel = channelId;
                    updatePlaybackActive(true);
                    listener.onTuneReady(channelId, "");
                }
            }

            @Override
            public void onTracksChanged(String inputId, List<TvTrackInfo> tracks) {
                if (!isActive(inputId)) return;
                audioTrackCount = 0;
                int subtitleTrackCount = 0;
                for (TvTrackInfo track : tracks) {
                    if (track.getType() == TvTrackInfo.TYPE_AUDIO) audioTrackCount++;
                    if (track.getType() == TvTrackInfo.TYPE_SUBTITLE) subtitleTrackCount++;
                }
                Log.i(TAG, "audio_tracks channel=" + channelId + " count=" + audioTrackCount);
                Log.i(TAG, "subtitle_tracks channel=" + channelId + " count=" + subtitleTrackCount);
                restoreSubtitles();
                listener.onTrackStateChanged();
            }

            @Override
            public void onTrackSelected(String inputId, int type, String trackId) {
                if (!isActive(inputId)) return;
                if (type == TvTrackInfo.TYPE_AUDIO) {
                    Log.i(TAG, "audio_track_selected channel=" + channelId + " track=" + trackId);
                } else if (type == TvTrackInfo.TYPE_SUBTITLE) {
                    Log.i(TAG, "subtitle_selected channel=" + channelId + " track=" + trackId);
                } else {
                    return;
                }
                listener.onTrackStateChanged();
            }
        });
    }

    long channelId() {
        return channelId;
    }

    String programBadges(long expectedChannel) {
        if (channelId != expectedChannel || !isReady(expectedChannel)) return "";
        String subtitles = subtitleTracks().isEmpty() ? "" : "CC";
        List<TvTrackInfo> video = tracks(TvTrackInfo.TYPE_VIDEO);
        String selected = view.getSelectedTrack(TvTrackInfo.TYPE_VIDEO);
        for (TvTrackInfo track : video) {
            if (!track.getId().equals(selected) && video.size() != 1) continue;
            int height = track.getVideoHeight();
            if (height > 0) return (subtitles.isEmpty() ? "" : subtitles + "   ") + height + " 线";
        }
        return subtitles;
    }

    List<TvTrackInfo> audioTracks() {
        return tracks(TvTrackInfo.TYPE_AUDIO);
    }

    List<TvTrackInfo> subtitleTracks() {
        return tracks(TvTrackInfo.TYPE_SUBTITLE);
    }

    String selectedSubtitleTrack() {
        return view.getSelectedTrack(TvTrackInfo.TYPE_SUBTITLE);
    }

    boolean selectSubtitleTrack(long channel, TvTrackInfo track) {
        return videoAvailable && !contentBlocked && subtitles.select(channel, track, subtitleTracks());
    }

    private List<TvTrackInfo> tracks(int type) {
        if (channelId == -1 || (!videoAvailable && !audioOnly) || contentBlocked) return Collections.emptyList();
        List<TvTrackInfo> tracks = view.getTracks(type);
        return tracks == null ? Collections.emptyList() : tracks;
    }

    private void restoreSubtitles() {
        if (videoAvailable && !contentBlocked) subtitles.restore(subtitleTracks());
    }

    String selectedAudioTrack() {
        return view.getSelectedTrack(TvTrackInfo.TYPE_AUDIO);
    }

    boolean selectAudioTrack(long expectedChannel, String trackId) {
        if (channelId != expectedChannel) return false;
        for (TvTrackInfo track : audioTracks()) {
            if (!trackId.equals(track.getId())) continue;
            if (!trackId.equals(selectedAudioTrack())) {
                try {
                    view.selectTrack(TvTrackInfo.TYPE_AUDIO, trackId);
                    Log.i(TAG, "audio_track_requested channel=" + channelId + " track=" + trackId);
                } catch (IllegalArgumentException | IllegalStateException exception) {
                    Log.w(TAG, "Audio track is no longer available", exception);
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    long requestedChannelId() {
        return queuedChannel != -1 ? queuedChannel : tuning ? channelId : -1;
    }

    boolean hasPreviewVideo(long id) {
        return videoAvailable && isReady(id);
    }

    boolean isReady(long id) {
        return channelId == id && !tuning && !contentBlocked && (videoAvailable || audioOnly);
    }

    void tune(long id) {
        if (tuning) {
            queuedChannel = id;
        } else {
            recoveryMessage = "";
            startTune(id);
        }
    }

    private void startTune(long id) {
        int generation = ++tuneGeneration;
        channelId = id;
        tuning = true;
        submitted = false;
        subtitles.reset(id);
        videoAvailable = false;
        audioOnly = false;
        xiaomiAudio.sync(false, false);
        contentBlocked = false;
        audioTrackCount = 0;
        boolean switching = lastReadyChannel != -1;
        if (!switching) updatePlaybackActive(false);
        setState("正在切换频道…", switching ? "" : "正在连接直播信号…");
        listener.onTuning(id, switching);

        // Give the window a frame to draw the transition before the tuner can clear its surface.
        view.postOnAnimation(() -> handler.post(() -> {
            if (tuneGeneration != generation || !tuning) return;
            if (focusRequest == null) requestFocus();
            submitted = true;
            Log.i(TAG, "tune channel=" + id + " input=" + TvListings.INPUT_ID);
            try {
                view.setStreamVolume(0.0f);
                view.tune(TvListings.INPUT_ID, TvContract.buildChannelUri(id));
                handler.postDelayed(tuneTimeout, switching ? 6_000 : 15_000);
            } catch (SecurityException | IllegalArgumentException | IllegalStateException exception) {
                Log.w(TAG, "tune rejected", exception);
                failTune("系统拒绝调谐，请检查频道和输入源");
            }
        }));
    }

    private void completeTune(String status) {
        finishRequest();
        long next = queuedChannel;
        queuedChannel = -1;
        if (next != -1 && next != channelId) {
            startTune(next);
            return;
        }
        lastReadyChannel = channelId;
        long ready = channelId;
        String notice = recoveryMessage;
        recoveryMessage = "";
        setState(videoAvailable ? "画面已就绪" : status, status);
        applyVolume(videoAvailable ? "video_available" : "audio_only");
        restoreSubtitles();
        updatePlaybackActive(videoAvailable || audioOnly);
        listener.onTuneReady(ready, notice);

    }

    private void finishRequest() {
        tuning = false;
        submitted = false;
        handler.removeCallbacks(tuneTimeout);
    }

    private void failTune(String message) {
        long attempted = channelId;
        long previous = lastReadyChannel;
        long next = queuedChannel;
        queuedChannel = -1;
        finishRequest();
        videoAvailable = false;
        audioOnly = false;
        xiaomiAudio.sync(false, false);
        view.reset();
        if (next != -1 && next != attempted) {
            recoveryMessage = "";
            startTune(next);
        } else if (previous != -1 && previous != attempted) {
            recoveryMessage = "换台失败，已返回上一频道";
            startTune(previous);
        } else {
            stop();
            setState(message, message);
            listener.onTuneFailed();
        }
    }

    void stop() {
        listener.onPreviewInvalidated(channelId, false);
        ++tuneGeneration;
        finishRequest();
        lastReadyChannel = -1;
        queuedChannel = -1;
        recoveryMessage = "";
        if (channelId != -1) {
            focused = false;
            applyVolume("stop");
            channelId = -1;
            subtitles.reset(-1);
            view.reset();
        }
        releaseFocus();
        videoAvailable = false;
        audioOnly = false;
        audioTrackCount = 0;
        state = "未播放";
        updatePlaybackActive(false);
    }

    boolean isPlaying() {
        return activePlayback;
    }

    private void updatePlaybackActive(boolean active) {
        if (activePlayback != active) {
            activePlayback = active;
            view.setKeepScreenOn(active);
            listener.onPlaybackActiveChanged(active);
        }
    }

    void retryVolume() {
        if (channelId == -1) return;
        if (!focused) {
            releaseFocus();
            requestFocus();
        }
        applyVolume("manual");
    }

    boolean supportsXiaomiAudio() {
        return xiaomiAudio.supported();
    }

    boolean xiaomiAudioEnabled() {
        return xiaomiAudio.enabled();
    }

    void setXiaomiAudioEnabled(boolean enabled) {
        xiaomiAudio.setEnabled(enabled);
        syncXiaomiAudio(false);
    }

    String diagnostics() {
        return "播放状态  " + state + "\n"
                + "屏幕常亮  " + (activePlayback ? "已开启（防止待机与屏保）" : "未开启") + "\n"
                + "音频焦点  " + focusState + "\n"
                + "TIF 音量  " + (channelId == -1 ? "无会话" : volume()) + "\n"
                + "会话报告的音轨数  " + audioTrackCount + "\n"
                + (xiaomiAudio.supported() ? xiaomiAudio.diagnostics() + "\n" : "")
                + "\n"
                + "电视是否出声需现场确认。";
    }

    private void onTuneTimeout() {
        if (tuning) failTune("调谐超时，可从菜单重新调谐");
    }

    private void onSessionLost(String inputId, String message) {
        if (!isActive(inputId)) return;
        if (tuning && submitted) {
            failTune(message);
        } else if (!tuning) {
            stop();
            setState(message, message);
        }
    }

    private void requestFocus() {
        int generation = ++focusGeneration;
        AudioFocusRequest request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(change -> {
                    if (focusGeneration == generation) onFocusChange(change);
                }, handler)
                .build();
        int result = audio.requestAudioFocus(request);
        if (result != AudioManager.AUDIOFOCUS_REQUEST_FAILED) focusRequest = request;
        focused = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        focusState = switch (result) {
            case AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "已取得";
            case AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "等待系统分配";
            default -> "系统未授予";
        };
        Log.i(TAG, "audio_focus_request channel=" + channelId + " result=" + result);
    }

    private void onFocusChange(int change) {
        if (focusRequest == null || channelId == -1) return;
        focused = change == AudioManager.AUDIOFOCUS_GAIN;
        if (change == AudioManager.AUDIOFOCUS_LOSS) releaseFocus();
        focusState = focused ? "已取得" : "已失去";
        Log.i(TAG, "audio_focus_change channel=" + channelId + " change=" + change);
        applyVolume("focus_change");
    }

    private void releaseFocus() {
        focusGeneration++;
        AudioFocusRequest request = focusRequest;
        focusRequest = null;
        focused = false;
        focusState = "未请求";
        if (request != null) audio.abandonAudioFocusRequest(request);
    }

    private boolean isActive(String inputId) {
        return channelId != -1 && TvListings.INPUT_ID.equals(inputId);
    }

    private float volume() {
        return focused && !tuning && !contentBlocked ? 1.0f : 0.0f;
    }

    private void applyVolume(String event) {
        float volume = volume();
        view.setStreamVolume(volume);
        Log.i(TAG, event + " channel=" + channelId + " tif_volume=" + volume);
        syncXiaomiAudio("manual".equals(event));
    }

    private void syncXiaomiAudio(boolean retry) {
        xiaomiAudio.sync(channelId != -1 && (videoAvailable || audioOnly) && !tuning && focused && !contentBlocked, retry);
    }

    private void setState(String state, String overlay) {
        this.state = state;
        listener.onStatus(overlay);
    }
}

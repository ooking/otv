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
    private boolean contentBlocked;
    private long channelId = -1;
    private int audioTrackCount;
    private String state = "未播放";
    private String focusState = "未请求";

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
                if (!isActive(inputId)) return;
                handler.removeCallbacks(tuneTimeout);
                videoAvailable = true;
                setState(contentBlocked ? "此频道受系统家长控制限制" : "画面已就绪",
                        contentBlocked ? "此频道受系统家长控制限制" : "");
                applyVolume("video_available");
                restoreSubtitles();
            }

            @Override
            public void onVideoUnavailable(String inputId, int reason) {
                if (!isActive(inputId)) return;
                videoAvailable = false;
                String message = switch (reason) {
                    case TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING -> "正在切换频道…";
                    case TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL -> "DTMB 信号较弱";
                    case TvInputManager.VIDEO_UNAVAILABLE_REASON_BUFFERING -> "正在等待直播信号…";
                    case TvInputManager.VIDEO_UNAVAILABLE_REASON_AUDIO_ONLY -> "当前为纯音频节目";
                    case TvInputManager.VIDEO_UNAVAILABLE_REASON_INSUFFICIENT_RESOURCE ->
                            "调谐器资源不足，请关闭其他直播应用后重试";
                    default -> "暂时没有画面";
                };
                setState(message, message);
                xiaomiAudio.sync(false, false);
                Log.i(TAG, "video_unavailable channel=" + channelId + " reason=" + reason);
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
                if (!isActive(inputId)) return;
                handler.removeCallbacks(tuneTimeout);
                contentBlocked = true;
                setState("此频道受系统家长控制限制", "此频道受系统家长控制限制");
                applyVolume("content_blocked");
            }

            @Override
            public void onContentAllowed(String inputId) {
                if (!isActive(inputId) || !contentBlocked) return;
                contentBlocked = false;
                String message = "正在等待直播信号…";
                setState(videoAvailable ? "画面已就绪" : message, videoAvailable ? "" : message);
                applyVolume("content_allowed");
                restoreSubtitles();
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
            }

            @Override
            public void onTrackSelected(String inputId, int type, String trackId) {
                if (!isActive(inputId)) return;
                if (type == TvTrackInfo.TYPE_AUDIO) {
                    Log.i(TAG, "audio_track_selected channel=" + channelId + " track=" + trackId);
                } else if (type == TvTrackInfo.TYPE_SUBTITLE) {
                    Log.i(TAG, "subtitle_selected channel=" + channelId + " track=" + trackId);
                }
            }
        });
    }

    long channelId() {
        return channelId;
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
        if (channelId == -1 || !videoAvailable || contentBlocked) return Collections.emptyList();
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

    void tune(long id) {
        channelId = id;
        subtitles.reset(id);
        videoAvailable = false;
        xiaomiAudio.sync(false, false);
        contentBlocked = false;
        audioTrackCount = 0;
        setState("正在切换频道…", "正在切换频道…");
        handler.removeCallbacks(tuneTimeout);
        if (focusRequest == null) requestFocus();
        Log.i(TAG, "tune channel=" + id + " input=" + TvListings.INPUT_ID);
        try {
            view.tune(TvListings.INPUT_ID, TvContract.buildChannelUri(id));
            applyVolume("tune");
            handler.postDelayed(tuneTimeout, 15_000);
        } catch (SecurityException | IllegalArgumentException exception) {
            Log.w(TAG, "tune rejected", exception);
            onSessionLost(TvListings.INPUT_ID, "系统拒绝调谐，请检查频道和输入源");
        }
    }

    void stop() {
        handler.removeCallbacks(tuneTimeout);
        if (channelId != -1) {
            focused = false;
            applyVolume("stop");
            channelId = -1;
            subtitles.reset(-1);
            view.reset();
        }
        releaseFocus();
        audioTrackCount = 0;
        state = "未播放";
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
                + "音频焦点  " + focusState + "\n"
                + "TIF 音量  " + (channelId == -1 ? "无会话" : volume()) + "\n"
                + "会话报告的音轨数  " + audioTrackCount + "\n"
                + (xiaomiAudio.supported() ? xiaomiAudio.diagnostics() + "\n" : "")
                + "\n"
                + "电视是否出声需现场确认。";
    }

    private void onTuneTimeout() {
        if (channelId != -1 && !videoAvailable && !contentBlocked && "正在切换频道…".equals(state)) {
            setState("调谐超时", "调谐超时，可从菜单重新调谐");
        }
    }

    private void onSessionLost(String inputId, String message) {
        if (!isActive(inputId)) return;
        stop();
        setState(message, message);
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
        return focused && !contentBlocked ? 1.0f : 0.0f;
    }

    private void applyVolume(String event) {
        float volume = volume();
        view.setStreamVolume(volume);
        Log.i(TAG, event + " channel=" + channelId + " tif_volume=" + volume);
        syncXiaomiAudio("manual".equals(event));
    }

    private void syncXiaomiAudio(boolean retry) {
        xiaomiAudio.sync(channelId != -1 && videoAvailable && focused && !contentBlocked, retry);
    }

    private void setState(String state, String overlay) {
        this.state = state;
        listener.onStatus(overlay);
    }
}

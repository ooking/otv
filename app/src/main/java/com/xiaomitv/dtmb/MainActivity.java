package com.xiaomitv.dtmb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvTrackInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.OperationCanceledException;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.Toast;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements TvScreen.Actions, Playback.Listener {
    private static final String TAG = "DtmbListings";
    private static final int LISTINGS_REQUEST = 1;
    private static final int XMLTV_REQUEST = 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService queries = Executors.newSingleThreadExecutor();
    private final Runnable refreshChannels = this::loadChannels;
    private final ContentObserver channelObserver = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            scheduleChannelRefresh();
        }
    };
    private final ContentObserver guideObserver = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            if (active) guide.requestRefresh(750);
        }
    };
    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputAdded(String inputId) {
            if (TvListings.INPUT_ID.equals(inputId)) scheduleChannelRefresh();
        }

        @Override
        public void onInputRemoved(String inputId) {
            if (TvListings.INPUT_ID.equals(inputId)) scheduleChannelRefresh();
        }
    };

    private TvInputManager inputs;
    private TvListings listings;
    private ProgramGuide guide;
    private TvScreen screen;
    private Playback playback;
    private SharedPreferences preferences;
    private Set<String> favorites = Collections.emptySet();
    private List<TvListings.Channel> channels = Collections.emptyList();
    private TvListings.Channel current;
    private CancellationSignal channelQuery;
    private boolean active;
    private boolean resumePlayback;
    private boolean observing;
    private boolean onlyFavorites;
    private String emptyMessage = "正在读取频道…";

    @Override
    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        inputs = getSystemService(TvInputManager.class);
        listings = new TvListings(getContentResolver());
        preferences = getSharedPreferences("live_tv", MODE_PRIVATE);
        favorites = new HashSet<>(preferences.getStringSet("favorites", Collections.emptySet()));
        screen = new TvScreen(this, handler, this);
        guide = new ProgramGuide(this, listings, handler, this::renderGuide);
        playback = new Playback(screen.video, getSystemService(AudioManager.class), preferences, handler,
                this);
        playback.setXiaomiAudioEnabled(preferences.getBoolean("xiaomi_audio", false));
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
        resumePlayback = true;
        if (inputs != null) inputs.registerCallback(inputCallback, handler);
        if (canReadListings()) {
            observeListings();
            guide.start();
            if (current != null && findInput() != null) {
                playback.tune(current.id());
                resumePlayback = false;
            }
        }
        loadChannels();
    }

    @Override
    protected void onPause() {
        active = false;
        handler.removeCallbacks(refreshChannels);
        guide.stop();
        screen.clearBanner();
        screen.clearTransition();
        cancelChannelQuery();
        if (observing) {
            getContentResolver().unregisterContentObserver(channelObserver);
            getContentResolver().unregisterContentObserver(guideObserver);
            observing = false;
        }
        if (inputs != null) inputs.unregisterCallback(inputCallback);
        playback.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        screen.finish();
        guide.close();
        queries.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == LISTINGS_REQUEST && active) {
            if (canReadListings()) {
                observeListings();
                guide.start();
            }
            loadChannels();
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != XMLTV_REQUEST || result != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null || !"content".equals(uri.getScheme())) return;
        Toast.makeText(this, "正在导入节目表…", Toast.LENGTH_SHORT).show();
        guide.importXmlTv(uri, this::showImportResult);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean firstPress = event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0;
        switch (key) {
            case KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (firstPress) step(key == KeyEvent.KEYCODE_CHANNEL_UP ? 1 : -1);
                return true;
            }
            case KeyEvent.KEYCODE_MENU -> {
                if (firstPress) options();
                return true;
            }
            case KeyEvent.KEYCODE_GUIDE -> {
                if (firstPress) openGuide();
                return true;
            }
            case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> {
                if (firstPress) showAudioTracks();
                return true;
            }
            case KeyEvent.KEYCODE_CAPTIONS -> {
                if (firstPress) showSubtitleTracks();
                return true;
            }
            case KeyEvent.KEYCODE_INFO -> {
                if (firstPress && !screen.panelOpen()) showProgramInfo();
                return true;
            }
            default -> { }
        }
        if (screen.handleGuideKey(event)) return true;
        if (!screen.panelOpen()) {
            boolean released = event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled();
            switch (key) {
                case KeyEvent.KEYCODE_DPAD_UP -> {
                    if (firstPress) step(1);
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (released) openChannels();
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (released) openGuide();
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (released) showProgramInfo();
                    return true;
                }
                default -> { }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (!screen.back()) super.onBackPressed();
    }

    @Override
    public void select(TvListings.Channel channel) {
        if (!active) return;
        screen.hidePanels();
        if (playback.requestedChannelId() == -1 && playback.isReady(channel.id())) {
            showProgram(channel, true, false);
        } else {
            playback.tune(channel.id());
        }
    }

    @Override
    public void onStatus(String status) {
        screen.status(status);
    }

    @Override
    public void onTuning(long id, boolean switching) {
        if (switching) screen.beginTransition();
        TvListings.Channel target = findChannel(id);
        if (target != null) showProgram(target, true, true);
    }

    @Override
    public void onTuneReady(long id, String notice) {
        if (!active) return;
        TvListings.Channel ready = findChannel(id);
        if (ready == null) {
            if (current != null && current.id() != id) playback.tune(current.id());
            return;
        }
        current = ready;
        if (!notice.isEmpty()) Toast.makeText(this, notice, Toast.LENGTH_SHORT).show();
        preferences.edit().putLong("last_channel", id).apply();
        guide.requestRefresh(5_000);
        screen.revealVideo();
        showProgram(ready, true, false);
        renderGuide();
        refreshOptions();
    }

    @Override
    public void onTuneFailed() {
        screen.clearBanner();
    }

    private void showProgramInfo() {
        if (screen.hasProgramOverlay()) {
            screen.openProgramDescription();
        } else if (current != null) {
            showProgram(current, false, false);
        } else {
            openChannels();
        }
    }

    @Override
    public void filter() {
        onlyFavorites = !onlyFavorites;
        renderChannels();
    }

    private void favorite() {
        if (current == null) return;
        String id = Long.toString(current.id());
        if (!favorites.add(id)) favorites.remove(id);
        preferences.edit().putStringSet("favorites", new HashSet<>(favorites)).apply();
        renderChannels();
        refreshOptions();
    }

    @Override
    public void openGuide() {
        if (channels.isEmpty()) {
            openChannels();
        } else {
            screen.showGuide(channels, currentId(), guide::schedule);
        }
    }

    @Override
    public void programDetails(TvListings.Channel channel, TvListings.Program program) {
        screen.programDetails(channel, program);
    }

    @Override
    public void options() {
        screen.showOptions(optionItems());
    }

    private void refreshOptions() {
        if (screen.optionsOpen()) screen.updateOptions(optionItems());
    }

    private List<TvScreen.Option> optionItems() {
        List<TvScreen.Option> items = new ArrayList<>();
        if (current != null) {
            items.add(new TvScreen.Option("声道与音轨", this::showAudioTracks));
            items.add(new TvScreen.Option("字幕", this::showSubtitleTracks));
            String favorite = favorites.contains(Long.toString(current.id()))
                    ? "取消收藏当前频道" : "收藏当前频道";
            items.add(new TvScreen.Option(favorite, this::favorite));
        }
        if (!canReadListings()) {
            items.add(new TvScreen.Option("授予电视节目权限", this::requestListingsPermission));
        }
        items.add(new TvScreen.Option("刷新频道", this::loadChannels));
        items.add(new TvScreen.Option("系统搜台设置", this::openSetup));
        if (current != null) {
            items.add(new TvScreen.Option("刷新节目表", () -> {
                guide.requestRefresh(0);
                renderGuide();
            }));
            items.add(new TvScreen.Option("导入 XMLTV 节目表", this::chooseXmlTvSource));
            items.add(new TvScreen.Option("重新调谐当前频道", this::retune));
            items.add(new TvScreen.Option("音频诊断", this::showAudioDiagnostics));
        }
        if (playback.supportsXiaomiAudio()) {
            String label = "小米音频兼容" + (playback.xiaomiAudioEnabled() ? " · 开启" : " · 关闭");
            items.add(new TvScreen.Option(label, this::showXiaomiAudioSetting));
        }
        return items;
    }

    private void retune() {
        if (current == null || !active) return;
        screen.hidePanels();
        playback.tune(current.id());
    }

    private void showSubtitleTracks() {
        List<TvTrackInfo> tracks = playback.subtitleTracks();
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("广播字幕")
                .setNegativeButton("返回", null)
                .setNeutralButton("系统字幕样式", (view, which) -> showCaptionStyleSettings());
        if (tracks.isEmpty()) {
            dialog.setMessage("当前节目没有报告可选的字幕轨。画面中的内嵌字幕随节目播出。");
        } else {
            String[] labels = new String[tracks.size() + 1];
            labels[0] = "关闭字幕";
            String selectedId = playback.selectedSubtitleTrack();
            int selected = 0;
            long channel = playback.channelId();
            for (int index = 0; index < tracks.size(); index++) {
                TvTrackInfo track = tracks.get(index);
                String name = TrackLabels.trackName(track);
                labels[index + 1] = name.isEmpty() ? "字幕 " + (index + 1)
                        : "字幕 " + (index + 1) + " · " + name;
                if (track.getId().equals(selectedId)) selected = index + 1;
            }
            dialog.setSingleChoiceItems(labels, selected, (view, index) -> {
                TvTrackInfo track = index == 0 ? null : tracks.get(index - 1);
                if (!playback.selectSubtitleTrack(channel, track)) {
                    Toast.makeText(this, "字幕轨已变化，请重新打开菜单。", Toast.LENGTH_SHORT).show();
                }
                view.dismiss();
            });
        }
        dialog.show();
    }

    private void showCaptionStyleSettings() {
        new AlertDialog.Builder(this).setTitle("系统字幕样式")
                .setMessage("系统样式适用于支持 Android 字幕偏好的内容。"
                        + "DTMB 广播字幕由电视调谐器绘制，样式取决于节目和固件。")
                .setPositiveButton("打开系统设置", (dialog, which) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_CAPTIONING_SETTINGS));
                    } catch (ActivityNotFoundException | SecurityException exception) {
                        new AlertDialog.Builder(this).setTitle("系统字幕样式")
                                .setMessage("此电视未提供 Android 字幕样式设置入口。")
                                .setPositiveButton("关闭", null).show();
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void chooseXmlTvSource() {
        new AlertDialog.Builder(this).setTitle("导入 XMLTV 节目表")
                .setItems(new String[]{"香港节目源（epg.pw）", "HOY 补充节目源",
                                "输入 HTTPS 网址", "选择本地文件"},
                        (dialog, which) -> {
                            switch (which) {
                                case 0 -> downloadXmlTv("https://epg.pw/xmltv/epg_HK.xml.gz");
                                case 1 -> downloadXmlTv(
                                        "https://raw.githubusercontent.com/litiande03/epg/master/pl.xml.gz");
                                case 2 -> askXmlTvUrl();
                                case 3 -> openXmlTv();
                                default -> { }
                            }
                        })
                .setNegativeButton("取消", null).show();
    }

    private void askXmlTvUrl() {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setHint("https://…");
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this).setTitle("XMLTV 网址").setView(field)
                .setPositiveButton("导入", (dialog, which) ->
                        downloadXmlTv(field.getText().toString().trim()))
                .setNegativeButton("取消", null).show();
    }

    private void downloadXmlTv(String address) {
        try {
            URI uri = new URI(address);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                showImportResult("请输入可直接访问的 HTTPS 节目表网址。");
                return;
            }
            guide.downloadXmlTv(uri.toURL(), this::showImportResult);
            Toast.makeText(this, "正在下载节目表…", Toast.LENGTH_SHORT).show();
        } catch (URISyntaxException | MalformedURLException exception) {
            showImportResult("请输入有效的 HTTPS 节目表网址。");
        }
    }

    private void showImportResult(String message) {
        new AlertDialog.Builder(this).setTitle("导入 XMLTV")
                .setMessage(message).setPositiveButton("关闭", null).show();
    }

    private void openXmlTv() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try {
            startActivityForResult(picker, XMLTV_REQUEST);
        } catch (ActivityNotFoundException exception) {
            new AlertDialog.Builder(this).setTitle("导入 XMLTV")
                    .setMessage("此电视没有可用的系统文件选择器。")
                    .setPositiveButton("关闭", null).show();
        }
    }

    private void showAudioTracks() {
        List<TvTrackInfo> tracks = playback.audioTracks();
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("声道与音轨")
                .setNegativeButton("关闭", null);
        if (tracks.isEmpty()) {
            dialog.setMessage("当前节目尚未报告可用音轨。画面就绪后可再打开菜单。");
        } else {
            String[] labels = new String[tracks.size()];
            int selected = -1;
            String selectedId = playback.selectedAudioTrack();
            long channel = playback.channelId();
            for (int index = 0; index < tracks.size(); index++) {
                TvTrackInfo track = tracks.get(index);
                labels[index] = TrackLabels.label(track, index);
                if (track.getId().equals(selectedId)) selected = index;
            }
            dialog.setSingleChoiceItems(labels, selected, (view, index) -> {
                if (!playback.selectAudioTrack(channel, tracks.get(index).getId())) {
                    Toast.makeText(this, "音轨已变化，请重新打开菜单。", Toast.LENGTH_SHORT).show();
                }
                view.dismiss();
            });
        }
        dialog.show();
    }

    private void requestListingsPermission() {
        requestPermissions(new String[]{TvListings.PERMISSION}, LISTINGS_REQUEST);
    }

    private void showAudioDiagnostics() {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("音频诊断")
                .setMessage(playback.diagnostics()).setNegativeButton("关闭", null);
        if (playback.channelId() != -1) {
            dialog.setPositiveButton("重试音频", (view, which) -> {
                playback.retryVolume();
                showAudioDiagnostics();
            });
        }
        dialog.show();
    }

    private void showXiaomiAudioSetting() {
        boolean enable = !playback.xiaomiAudioEnabled();
        new AlertDialog.Builder(this).setTitle("小米音频兼容")
                .setMessage("用于小米电视 DTMB 有画面但无声的情况。播放时按音频焦点和媒体静音状态控制电视输入音频。")
                .setPositiveButton(enable ? "开启" : "关闭", (dialog, which) -> {
                    playback.setXiaomiAudioEnabled(enable);
                    preferences.edit().putBoolean("xiaomi_audio", enable).apply();
                    refreshOptions();
                })
                .setNegativeButton("取消", null).show();
    }

    private void openSetup() {
        TvInputInfo input = findInput();
        try {
            Intent setup = input == null ? null : input.createSetupIntent();
            if (setup != null) {
                startActivity(setup);
                return;
            }
        } catch (ActivityNotFoundException | SecurityException exception) {
            Log.i(TAG, "TIF setup activity unavailable", exception);
        }
        new AlertDialog.Builder(this).setTitle("系统搜台")
                .setMessage("请在小米电视自带的直播应用中切换至地面数字电视并完成搜台，再返回这里刷新频道。")
                .setPositiveButton("关闭", null).show();
    }

    private TvInputInfo findInput() {
        return inputs == null ? null : inputs.getTvInputInfo(TvListings.INPUT_ID);
    }

    private boolean canReadListings() {
        return checkSelfPermission(TvListings.PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    private void observeListings() {
        if (observing) return;
        try {
            getContentResolver().registerContentObserver(TvContract.Channels.CONTENT_URI,
                    true, channelObserver);
            getContentResolver().registerContentObserver(TvContract.Programs.CONTENT_URI,
                    true, guideObserver);
            observing = true;
        } catch (SecurityException | IllegalArgumentException exception) {
            getContentResolver().unregisterContentObserver(channelObserver);
            Log.i(TAG, "TV listings observer unavailable", exception);
        }
    }

    private void scheduleChannelRefresh() {
        if (!active) return;
        handler.removeCallbacks(refreshChannels);
        handler.postDelayed(refreshChannels, 350);
    }

    private void loadChannels() {
        if (!active) return;
        cancelChannelQuery();
        if (findInput() == null) {
            applyChannels(Collections.emptyList(), "未发现小米电视的 MediaTek 调谐器输入。");
            return;
        }
        if (!canReadListings()) {
            applyChannels(Collections.emptyList(), "请在「选项」中授予电视节目权限。曾拒绝授权时，可从系统应用详情开启。");
            return;
        }

        CancellationSignal signal = new CancellationSignal();
        channelQuery = signal;
        queries.execute(() -> {
            try {
                List<TvListings.Channel> result = listings.channels(signal);
                handler.post(() -> {
                    if (channelQuery != signal || !active) return;
                    channelQuery = null;
                    Log.i(TAG, "visible_channels=" + result.size());
                    applyChannels(result, "没有可读取的 DTMB 频道。请先在小米电视自带的直播应用中搜台，再返回刷新。");
                });
            } catch (OperationCanceledException ignored) {
                // A newer request or a lifecycle change replaced this query.
            } catch (RuntimeException failure) {
                Log.w(TAG, "channel query failed", failure);
                handler.post(() -> {
                    if (channelQuery != signal || !active) return;
                    channelQuery = null;
                    if (channels.isEmpty() || failure instanceof SecurityException) {
                        applyChannels(Collections.emptyList(), "系统频道数据不可读，请检查电视节目权限后重试。");
                    } else {
                        screen.status("频道刷新失败，可从菜单重试");
                    }
                });
            }
        });
    }

    private void applyChannels(List<TvListings.Channel> result, String message) {
        channels = result;
        guide.setChannels(result);
        emptyMessage = message;
        if (result.isEmpty()) {
            current = null;
            playback.stop();
            screen.status("");
            screen.clearBanner();
            screen.clearTransition();
            screen.updateGuide(channels, currentId(), guide::schedule);
            refreshOptions();
            renderChannels();
            if (!screen.panelOpen()) screen.showChannels();
            return;
        }

        boolean initialSelection = current == null;
        long previous = initialSelection ? preferences.getLong("last_channel", -1) : current.id();
        TvListings.Channel previousChannel = findChannel(previous);
        current = previousChannel == null ? result.get(0) : previousChannel;
        boolean shouldTune = resumePlayback || initialSelection || current.id() != previous;
        resumePlayback = false;
        if (initialSelection) screen.hidePanels();
        if (shouldTune && playback.requestedChannelId() != current.id()) playback.tune(current.id());
        renderGuide();
        refreshOptions();
    }

    private void renderGuide() {
        if (!active) return;
        renderChannels();
        if (screen.guideOpen()) screen.updateGuide(channels, currentId(), guide::schedule);
        if (screen.hasProgramOverlay()) {
            TvListings.Channel channel = findChannel(playback.channelId());
            if (channel != null) {
                long now = System.currentTimeMillis();
                screen.updateBanner(channel, nowPlaying(channel, now), nextProgram(channel, now),
                        playback.programBadges(channel.id()));
            }
        }
    }

    private void showProgram(TvListings.Channel channel, boolean temporary, boolean switching) {
        long now = System.currentTimeMillis();
        screen.showProgram(channel, nowPlaying(channel, now), nextProgram(channel, now),
                playback.programBadges(channel.id()), temporary, switching);
    }

    private TvListings.Channel findChannel(long id) {
        for (TvListings.Channel channel : channels) {
            if (channel.id() == id) return channel;
        }
        return null;
    }

    private TvListings.Program nowPlaying(TvListings.Channel channel, long now) {
        return GuideTimeline.slotAt(guide.schedule(channel), now, now, now + GuideCache.WINDOW)
                .program();
    }

    private TvListings.Program nextProgram(TvListings.Channel channel, long now) {
        return GuideTimeline.nextProgram(guide.schedule(channel), now, now + GuideCache.WINDOW);
    }

    private long currentId() {
        return current == null ? -1 : current.id();
    }

    private void cancelChannelQuery() {
        if (channelQuery != null) {
            channelQuery.cancel();
            channelQuery = null;
        }
    }

    private List<TvListings.Channel> visibleChannels() {
        if (!onlyFavorites) return channels;
        List<TvListings.Channel> result = new ArrayList<>();
        for (TvListings.Channel channel : channels) {
            if (favorites.contains(Long.toString(channel.id()))) result.add(channel);
        }
        return result;
    }

    private void openChannels() {
        renderChannels();
        screen.showChannels();
    }

    private void renderChannels() {
        List<TvListings.Channel> visible = visibleChannels();
        String message = channels.isEmpty() ? emptyMessage : "还没有收藏频道。";
        long now = System.currentTimeMillis();
        screen.channels(visible, favorites, currentId(), onlyFavorites, message,
                channel -> nowPlaying(channel, now));
    }

    private void step(int direction) {
        List<TvListings.Channel> visible = visibleChannels();
        if (visible.isEmpty()) {
            openChannels();
            return;
        }
        long pending = playback.requestedChannelId();
        TvListings.Channel base = pending == -1 ? current : findChannel(pending);
        int index = base == null ? -1 : visible.indexOf(base);
        int next = index < 0 ? (direction > 0 ? 0 : visible.size() - 1)
                : (index + direction + visible.size()) % visible.size();
        select(visible.get(next));
    }
}

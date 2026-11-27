package com.xiaomitv.dtmb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
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
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
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
    private static final String PREF_PERMISSION_ASKED = "listings_permission_asked";
    private static final String STATE_AUTO_PERM_REQUESTED = "auto_perm_requested";
    private static final String STATE_AUTO_SCAN_PROMPTED = "auto_scan_prompted";
    private static final String STATE_MUTE_WARNED = "mute_warned";

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
            if (active) guide.onProgramsChanged();
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

    private AudioManager audioManager;
    private TvInputManager inputs;
    private TvListings listings;
    private ProgramGuide guide;
    private TvScreen screen;
    private Playback playback;
    private XiaomiAvSettings avSettings;
    private ChannelPreviews previews;
    private MediaSession mediaSession;
    private SharedPreferences preferences;
    private AlertDialog scanPromptDialog;
    private RemoteHelpDialog helpDialog;
    private Set<String> favorites = Collections.emptySet();
    private Set<String> hiddenChannels = Collections.emptySet();
    private List<Long> channelOrder = Collections.emptyList();
    private List<TvListings.Channel> rawChannels = Collections.emptyList();
    private List<TvListings.Channel> allChannels = Collections.emptyList();
    private List<TvListings.Channel> channels = Collections.emptyList();
    private TvListings.Channel current;
    private CancellationSignal channelQuery;
    private boolean active;
    private boolean resumePlayback;
    private boolean observing;
    private boolean onlyFavorites;
    private boolean autoPermissionRequested;
    private boolean autoScanPrompted;
    private boolean muteWarned;
    private TvScreen.EmptyState emptyState = TvScreen.EmptyState.messageOnly("");

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLanguage.wrap(newBase));
    }

    @Override
    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        if (savedState != null) {
            autoPermissionRequested = savedState.getBoolean(STATE_AUTO_PERM_REQUESTED, false);
            autoScanPrompted = savedState.getBoolean(STATE_AUTO_SCAN_PROMPTED, false);
            muteWarned = savedState.getBoolean(STATE_MUTE_WARNED, false);
        }
        emptyState = TvScreen.EmptyState.messageOnly(getString(R.string.empty_loading_channels));
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        mediaSession = new MediaSession(this, TAG);
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                if (current != null) playback.tune(current.id());
            }

            @Override
            public void onPause() {
                playback.stop();
            }

            @Override
            public void onStop() {
                playback.stop();
            }
        });
        audioManager = getSystemService(AudioManager.class);
        inputs = getSystemService(TvInputManager.class);
        listings = new TvListings(this);
        preferences = getSharedPreferences("live_tv", MODE_PRIVATE);
        favorites = new HashSet<>(preferences.getStringSet("favorites", Collections.emptySet()));
        hiddenChannels = ChannelManager.loadHidden(preferences);
        channelOrder = ChannelManager.loadOrder(preferences);
        previews = new ChannelPreviews(handler);
        screen = new TvScreen(this, handler, this, previews);
        avSettings = new XiaomiAvSettings(this, screen::hidePanels);
        guide = new ProgramGuide(this, listings, handler, this::renderGuide);
        playback = new Playback(screen.video, audioManager, preferences, handler,
                this);
        // 新安装默认开启小米音频兼容，避免小米/红米电视首次打开有画无声
        playback.setXiaomiAudioEnabled(preferences.getBoolean("xiaomi_audio", true));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_AUTO_PERM_REQUESTED, autoPermissionRequested);
        outState.putBoolean(STATE_AUTO_SCAN_PROMPTED, autoScanPrompted);
        outState.putBoolean(STATE_MUTE_WARNED, muteWarned);
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
        updateScreenKeepOn(false);
        updateMediaSession(false);
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
        dismissScanPrompt();
        dismissHelpDialog();
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        previews.close();
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
        Toast.makeText(this, R.string.xmltv_toast_importing, Toast.LENGTH_SHORT).show();
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
                case KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (firstPress) step(key == KeyEvent.KEYCODE_DPAD_UP ? 1 : -1);
                    return true;
                }
                case KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
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
        current = channel;
        preferences.edit().putLong("last_channel", channel.id()).apply();
        screen.hidePanels();
        renderChannels();
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
        previews.cancel();
        guide.cancelTunedRefresh();
        if (switching) screen.beginTransition();
        TvListings.Channel target = findChannel(id);
        if (target != null) {
            current = target;
            showProgram(target, true, true);
            renderChannels();
            refreshOpenGuide();
            refreshOptions();
        }
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
        updateMediaMetadata(ready);
        if (!notice.isEmpty()) {
            Toast.makeText(this, notice, Toast.LENGTH_SHORT).show();
        } else {
            warnIfSystemMuted();
        }
        preferences.edit().putLong("last_channel", id).apply();
        guide.refreshAfterTune(ready);
        if (playback.hasPreviewVideo(id)) {
            previews.schedule(ready, () -> active && playback.hasPreviewVideo(id), this::renderChannels);
        }
        screen.revealVideo();
        showProgram(ready, true, false);
        renderGuide();
        refreshOptions();
    }

    @Override
    public void onPreviewInvalidated(long id, boolean blocked) {
        previews.cancel();
        if (blocked) {
            TvListings.Channel channel = findChannel(id);
            if (channel != null) previews.forget(channel);
            renderChannels();
        }
    }

    @Override
    public void onTuneFailed() {
        updateScreenKeepOn(false);
        updateMediaSession(false);
        screen.clearBanner();
        refreshOptions();
    }

    @Override
    public void onTrackStateChanged() {
        refreshOptions();
    }

    @Override
    public void onPlaybackActiveChanged(boolean playing) {
        updateScreenKeepOn(playing);
        updateMediaSession(playing);
    }

    private void updateScreenKeepOn(boolean keepOn) {
        if (active && keepOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void updateMediaSession(boolean playing) {
        if (mediaSession == null) return;
        if (playing) {
            PlaybackState state = new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_STOP)
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                    .build();
            mediaSession.setPlaybackState(state);
            updateMediaMetadata(current);
            mediaSession.setActive(true);
        } else {
            PlaybackState state = new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_STOP)
                    .setState(PlaybackState.STATE_STOPPED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0.0f)
                    .build();
            mediaSession.setPlaybackState(state);
            mediaSession.setActive(false);
        }
    }

    private void updateMediaMetadata(TvListings.Channel channel) {
        if (mediaSession == null || channel == null) return;
        MediaMetadata metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, channel.label())
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, channel.label())
                .build();
        mediaSession.setMetadata(metadata);
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
        refreshOpenGuide();
    }

    private void favorite() {
        if (current == null) return;
        String id = Long.toString(current.id());
        if (!favorites.add(id)) favorites.remove(id);
        preferences.edit().putStringSet("favorites", new HashSet<>(favorites)).apply();
        renderChannels();
        refreshOpenGuide();
        refreshOptions();
    }

    @Override
    public void openGuide() {
        if (channels.isEmpty()) {
            openChannels();
        } else {
            screen.showGuide(visibleChannels(), currentId(), onlyFavorites, guide::schedule);
        }
    }

    private void warnIfSystemMuted() {
        if (muteWarned || audioManager == null) return;
        if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
                || audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0) {
            muteWarned = true;
            Toast.makeText(this, R.string.playback_tv_muted_hint, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void openHelp() {
        dismissHelpDialog();
        helpDialog = new RemoteHelpDialog(this, screen.style);
        helpDialog.show();
    }

    private void dismissHelpDialog() {
        if (helpDialog != null) {
            if (helpDialog.isShowing()) helpDialog.dismiss();
            helpDialog = null;
        }
    }

    @Override
    public void importXmlTv() {
        chooseXmlTvSource();
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
        if (!canReadListings()) {
            items.add(new TvScreen.Option(permissionActionLabel(), R.drawable.ic_guide,
                    this::handleListingsPermissionAction));
        }
        if (current != null) {
            items.add(new TvScreen.Option(getString(R.string.option_audio_tracks), R.drawable.ic_audio,
                    audioTrackSummary(), this::showAudioTracks));
            items.add(new TvScreen.Option(getString(R.string.option_subtitles), R.drawable.ic_subtitles,
                    subtitleTrackSummary(), this::showSubtitleTracks));
            if (avSettings.available()) {
                items.add(new TvScreen.Option(getString(R.string.option_picture_settings),
                        R.drawable.ic_picture, avSettings::openPicture));
                items.add(new TvScreen.Option(getString(R.string.option_sound_settings),
                        R.drawable.ic_audio, avSettings::openSound));
            }
            String favorite = getString(favorites.contains(Long.toString(current.id()))
                    ? R.string.option_favorite_remove : R.string.option_favorite_add);
            items.add(new TvScreen.Option(favorite, R.drawable.ic_favorite, this::favorite));
            String hideLabel = getString(hiddenChannels.contains(Long.toString(current.id()))
                    ? R.string.option_unhide_channel : R.string.option_hide_channel);
            items.add(new TvScreen.Option(hideLabel, R.drawable.ic_visibility, this::toggleCurrentChannelHidden));
        }
        if (!rawChannels.isEmpty()) {
            items.add(new TvScreen.Option(getString(R.string.option_channel_manager), R.drawable.ic_channels,
                    "", current != null, this::openChannelManager));
        }
        items.add(new TvScreen.Option(getString(R.string.option_language), R.drawable.ic_language,
                AppLanguage.summary(this), this::showLanguageSettings));
        items.add(new TvScreen.Option(getString(R.string.sidebar_help), R.drawable.ic_help,
                this::openHelp));
        items.add(new TvScreen.Option(getString(R.string.option_xiaomi_scan), R.drawable.ic_settings,
                getString(R.string.option_xiaomi_scan_summary), false, this::openSetup));
        if (!channels.isEmpty()) {
            items.add(new TvScreen.Option(getString(R.string.option_import_xmltv),
                    R.drawable.ic_import, this::chooseXmlTvSource));
        }
        if (current != null) {
            items.add(new TvScreen.Option(getString(R.string.option_audio_diagnostics),
                    R.drawable.ic_audio, this::showAudioDiagnostics));
        }
        if (playback.supportsXiaomiAudio()) {
            String label = getString(playback.xiaomiAudioEnabled()
                    ? R.string.option_xiaomi_audio_on : R.string.option_xiaomi_audio_off);
            items.add(new TvScreen.Option(label, R.drawable.ic_audio, this::showXiaomiAudioSetting));
        }
        return items;
    }

    private void showLanguageSettings() {
        String currentTag = AppLanguage.savedTag(this);
        String[] labels = new String[AppLanguage.OPTIONS.length];
        int selected = 0;
        for (int i = 0; i < AppLanguage.OPTIONS.length; i++) {
            String tag = AppLanguage.OPTIONS[i];
            labels[i] = AppLanguage.optionLabel(this, tag);
            if (tag.equals(currentTag)) selected = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.language_dialog_title)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    dialog.dismiss();
                    AppLanguage.apply(this, AppLanguage.OPTIONS[which]);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private String audioTrackSummary() {
        List<TvTrackInfo> tracks = playback.audioTracks();
        if (tracks.isEmpty()) return getString(R.string.audio_summary_none);
        int selected = TrackLabels.selectedIndex(tracks, playback.selectedAudioTrack());
        return selected >= 0
                ? TrackLabels.label(this, tracks.get(selected), selected)
                : getString(R.string.audio_summary_unreported);
    }

    private String subtitleTrackSummary() {
        List<TvTrackInfo> tracks = playback.subtitleTracks();
        if (tracks.isEmpty()) return getString(R.string.subtitle_summary_none);
        int selected = TrackLabels.selectedIndex(tracks, playback.selectedSubtitleTrack());
        return selected >= 0
                ? TrackLabels.subtitleLabel(this, tracks.get(selected), selected)
                : getString(R.string.subtitle_off);
    }

    private void toggleCurrentChannelHidden() {
        if (current == null) return;
        String idStr = Long.toString(current.id());
        if (hiddenChannels.contains(idStr)) {
            hiddenChannels.remove(idStr);
            Toast.makeText(this, getString(R.string.channel_mgr_toast_restored, current.name()),
                    Toast.LENGTH_SHORT).show();
        } else {
            hiddenChannels.add(idStr);
            Toast.makeText(this, getString(R.string.channel_mgr_toast_hidden_hint, current.name()),
                    Toast.LENGTH_SHORT).show();
        }
        ChannelManager.saveHidden(preferences, hiddenChannels);
        reapplyChannels();
    }

    private void openChannelManager() {
        if (allChannels.isEmpty() && rawChannels.isEmpty()) {
            Toast.makeText(this, R.string.channel_mgr_toast_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        screen.hidePanels();
        ChannelManageDialog dialog = new ChannelManageDialog(this, screen.style, allChannels,
                hiddenChannels, favorites, currentId(), new ChannelManageDialog.Listener() {
            @Override
            public void onChannelsUpdated(List<TvListings.Channel> newOrder, Set<String> hidden, Set<String> favs) {
                channelOrder = ChannelManager.extractIds(newOrder);
                ChannelManager.saveOrder(preferences, channelOrder);
                hiddenChannels = new HashSet<>(hidden);
                ChannelManager.saveHidden(preferences, hiddenChannels);
                favorites = new HashSet<>(favs);
                preferences.edit().putStringSet("favorites", new HashSet<>(favorites)).apply();
                reapplyChannels();
            }

            @Override
            public void onResetDefaultOrder() {
                channelOrder = Collections.emptyList();
                ChannelManager.saveOrder(preferences, channelOrder);
                reapplyChannels();
                Toast.makeText(MainActivity.this, R.string.channel_mgr_toast_order_reset, Toast.LENGTH_SHORT).show();
            }
        });
        dialog.show();
    }

    private void showSubtitleTracks() {
        List<TvTrackInfo> tracks = playback.subtitleTracks();
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(R.string.subtitle_dialog_title)
                .setNegativeButton(R.string.action_back, null)
                .setNeutralButton(R.string.subtitle_system_style, (view, which) -> showCaptionStyleSettings());
        if (tracks.isEmpty()) {
            dialog.setMessage(R.string.subtitle_dialog_empty);
        } else {
            String[] labels = new String[tracks.size() + 1];
            labels[0] = getString(R.string.subtitle_off);
            int selected = TrackLabels.selectedIndex(tracks, playback.selectedSubtitleTrack()) + 1;
            long channel = playback.channelId();
            for (int index = 0; index < tracks.size(); index++) {
                labels[index + 1] = TrackLabels.subtitleLabel(this, tracks.get(index), index);
            }
            dialog.setSingleChoiceItems(labels, selected, (view, index) -> {
                TvTrackInfo track = index == 0 ? null : tracks.get(index - 1);
                if (!playback.selectSubtitleTrack(channel, track)) {
                    Toast.makeText(this, R.string.subtitle_toast_changed, Toast.LENGTH_SHORT).show();
                }
                view.dismiss();
            });
        }
        dialog.show();
    }

    private void showCaptionStyleSettings() {
        new AlertDialog.Builder(this).setTitle(R.string.subtitle_system_style)
                .setMessage(R.string.caption_style_msg)
                .setPositiveButton(R.string.caption_style_open_settings, (dialog, which) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_CAPTIONING_SETTINGS));
                    } catch (ActivityNotFoundException | SecurityException exception) {
                        new AlertDialog.Builder(this).setTitle(R.string.subtitle_system_style)
                                .setMessage(R.string.caption_style_unavailable)
                                .setPositiveButton(R.string.action_close, null).show();
                    }
                }).setNegativeButton(R.string.action_cancel, null).show();
    }

    private void chooseXmlTvSource() {
        new AlertDialog.Builder(this).setTitle(R.string.option_import_xmltv)
                .setItems(new String[]{
                                getString(R.string.xmltv_source_hk),
                                getString(R.string.xmltv_source_hoy),
                                getString(R.string.xmltv_source_url),
                                getString(R.string.xmltv_source_local)
                        },
                        (dialog, which) -> {
                            switch (which) {
                                case 0 -> downloadXmlTv(XmlTvImporter.HONG_KONG_FEED);
                                case 1 -> downloadXmlTv(
                                        "https://raw.githubusercontent.com/litiande03/epg/master/pl.xml.gz");
                                case 2 -> askXmlTvUrl();
                                case 3 -> openXmlTv();
                                default -> { }
                            }
                        })
                .setNegativeButton(R.string.action_cancel, null).show();
    }

    private void askXmlTvUrl() {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setHint("https://…");
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this).setTitle(R.string.xmltv_url_title).setView(field)
                .setPositiveButton(R.string.action_import, (dialog, which) ->
                        downloadXmlTv(field.getText().toString().trim()))
                .setNegativeButton(R.string.action_cancel, null).show();
    }

    private void downloadXmlTv(String address) {
        try {
            URI uri = new URI(address);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                showImportResult(getString(R.string.xmltv_err_direct_https));
                return;
            }
            guide.downloadXmlTv(uri.toURL(), this::showImportResult);
            Toast.makeText(this, R.string.xmltv_toast_downloading, Toast.LENGTH_SHORT).show();
        } catch (URISyntaxException | MalformedURLException exception) {
            showImportResult(getString(R.string.xmltv_err_valid_https));
        }
    }

    private void showImportResult(String message) {
        new AlertDialog.Builder(this).setTitle(R.string.xmltv_result_title)
                .setMessage(message).setPositiveButton(R.string.action_close, null).show();
    }

    private void openXmlTv() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try {
            startActivityForResult(picker, XMLTV_REQUEST);
        } catch (ActivityNotFoundException exception) {
            new AlertDialog.Builder(this).setTitle(R.string.xmltv_result_title)
                    .setMessage(R.string.xmltv_err_no_picker)
                    .setPositiveButton(R.string.action_close, null).show();
        }
    }

    private void showAudioTracks() {
        List<TvTrackInfo> tracks = playback.audioTracks();
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(R.string.option_audio_tracks)
                .setNegativeButton(R.string.action_close, null);
        boolean hasUnlabeledTrack = false;
        if (tracks.isEmpty()) {
            builder.setMessage(R.string.audio_dialog_empty);
        } else {
            String[] labels = new String[tracks.size()];
            int selected = TrackLabels.selectedIndex(tracks, playback.selectedAudioTrack());
            long channel = playback.channelId();
            for (int index = 0; index < tracks.size(); index++) {
                TvTrackInfo track = tracks.get(index);
                labels[index] = TrackLabels.label(this, track, index);
                if (TrackLabels.trackName(this, track).isEmpty()) hasUnlabeledTrack = true;
            }
            builder.setSingleChoiceItems(labels, selected, (view, index) -> {
                if (!playback.selectAudioTrack(channel, tracks.get(index).getId())) {
                    Toast.makeText(this, R.string.audio_toast_changed, Toast.LENGTH_SHORT).show();
                }
                view.dismiss();
            });
        }
        AlertDialog dialog = builder.show();
        if (tracks.size() > 1 && hasUnlabeledTrack && dialog.getListView() != null) {
            TvStyle style = new TvStyle(this);
            TextView hint = new TextView(this);
            hint.setText(R.string.audio_dialog_hint);
            hint.setTextColor(TvStyle.MUTED);
            hint.setTextSize(14f);
            int padH = style.dp(24);
            int padV = style.dp(12);
            hint.setPadding(padH, padV, padH, padV);
            hint.setFocusable(false);
            dialog.getListView().addFooterView(hint, null, false);
        }
    }

    private boolean isListingsPermissionPermanentlyDenied() {
        return !canReadListings()
                && preferences.getBoolean(PREF_PERMISSION_ASKED, false)
                && !shouldShowRequestPermissionRationale(TvListings.PERMISSION);
    }

    private String permissionActionLabel() {
        return getString(isListingsPermissionPermanentlyDenied()
                ? R.string.option_open_app_settings
                : R.string.option_grant_listings_permission);
    }

    private void handleListingsPermissionAction() {
        if (isListingsPermissionPermanentlyDenied()) {
            openAppPermissionSettings();
        } else {
            requestListingsPermission();
        }
    }

    private void requestListingsPermission() {
        preferences.edit().putBoolean(PREF_PERMISSION_ASKED, true).apply();
        requestPermissions(new String[]{TvListings.PERMISSION}, LISTINGS_REQUEST);
    }

    private void openAppPermissionSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
        try {
            startActivity(intent);
            screen.hidePanels();
        } catch (ActivityNotFoundException | SecurityException exception) {
            Log.i(TAG, "App details settings unavailable", exception);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.option_open_app_settings)
                    .setMessage(R.string.app_settings_unavailable)
                    .setPositiveButton(R.string.action_close, null)
                    .show();
        }
    }

    private void promptChannelScanIfNeeded() {
        if (autoScanPrompted || !active) return;
        autoScanPrompted = true;
        dismissScanPrompt();
        scanPromptDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.scan_prompt_title)
                .setMessage(R.string.scan_prompt_msg)
                .setPositiveButton(R.string.option_xiaomi_scan, (dialog, which) -> openSetup())
                .setNegativeButton(R.string.action_later, null)
                .show();
        android.widget.Button positive = scanPromptDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) positive.requestFocus();
    }

    private void dismissScanPrompt() {
        if (scanPromptDialog != null) {
            if (scanPromptDialog.isShowing()) scanPromptDialog.dismiss();
            scanPromptDialog = null;
        }
    }

    private void showAudioDiagnostics() {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(R.string.option_audio_diagnostics)
                .setMessage(playback.diagnostics()).setNegativeButton(R.string.action_close, null);
        if (playback.channelId() != -1) {
            dialog.setPositiveButton(R.string.diag_retry_audio, (view, which) -> {
                playback.retryVolume();
                showAudioDiagnostics();
            });
        }
        dialog.show();
    }

    private void showXiaomiAudioSetting() {
        boolean enable = !playback.xiaomiAudioEnabled();
        new AlertDialog.Builder(this).setTitle(R.string.xiaomi_audio_title)
                .setMessage(R.string.xiaomi_audio_msg)
                .setPositiveButton(enable ? R.string.action_enable : R.string.action_disable, (dialog, which) -> {
                    playback.setXiaomiAudioEnabled(enable);
                    preferences.edit().putBoolean("xiaomi_audio", enable).apply();
                    refreshOptions();
                })
                .setNegativeButton(R.string.action_cancel, null).show();
    }

    private void openSetup() {
        Intent dtmb = new Intent("com.xiaomi.mitv.tvplayer.DTMB_PLAY")
                .setClassName("com.xiaomi.mitv.tvplayer", "com.xiaomi.mitv.tvplayer.dtmb.DTMBActivity");
        try {
            startActivity(dtmb);
            screen.hidePanels();
        } catch (ActivityNotFoundException | SecurityException exception) {
            Log.i(TAG, "Xiaomi DTMB activity unavailable", exception);
            new AlertDialog.Builder(this).setTitle(R.string.option_xiaomi_scan)
                    .setMessage(R.string.xiaomi_scan_unavailable)
                    .setPositiveButton(R.string.action_close, null).show();
        }
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
            applyChannels(Collections.emptyList(),
                    TvScreen.EmptyState.messageOnly(getString(R.string.empty_no_tuner)));
            return;
        }
        if (!canReadListings()) {
            // 首次进入且未被系统永久拒绝时，自动拉起系统权限请求弹窗
            if (!autoPermissionRequested && !isListingsPermissionPermanentlyDenied()) {
                autoPermissionRequested = true;
                requestListingsPermission();
                return;
            }
            applyChannels(Collections.emptyList(), new TvScreen.EmptyState(
                    getString(R.string.empty_no_permission),
                    permissionActionLabel(),
                    R.drawable.ic_guide,
                    this::handleListingsPermissionAction));
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
                    applyChannels(result, new TvScreen.EmptyState(
                            getString(R.string.empty_no_dtmb_channels),
                            getString(R.string.option_xiaomi_scan),
                            R.drawable.ic_settings,
                            this::openSetup));
                    if (result.isEmpty()) {
                        promptChannelScanIfNeeded();
                    } else {
                        dismissScanPrompt();
                    }
                });
            } catch (OperationCanceledException ignored) {
                // A newer request or a lifecycle change replaced this query.
            } catch (RuntimeException failure) {
                Log.w(TAG, "channel query failed", failure);
                handler.post(() -> {
                    if (channelQuery != signal || !active) return;
                    channelQuery = null;
                    if (channels.isEmpty() || failure instanceof SecurityException) {
                        TvScreen.EmptyState errorState = !canReadListings()
                                ? new TvScreen.EmptyState(
                                        getString(R.string.empty_listings_unreadable),
                                        permissionActionLabel(),
                                        R.drawable.ic_guide,
                                        this::handleListingsPermissionAction)
                                : new TvScreen.EmptyState(
                                        getString(R.string.empty_listings_unreadable),
                                        getString(R.string.empty_action_retry),
                                        R.drawable.ic_refresh,
                                        this::loadChannels);
                        applyChannels(Collections.emptyList(), errorState);
                    } else {
                        screen.status(getString(R.string.status_channel_load_failed));
                    }
                });
            }
        });
    }

    private TvScreen.EmptyState allHiddenEmptyState() {
        return new TvScreen.EmptyState(
                getString(R.string.empty_all_hidden),
                getString(R.string.option_channel_manager),
                R.drawable.ic_channels,
                this::openChannelManager);
    }

    private void applyChannels(List<TvListings.Channel> result, TvScreen.EmptyState state) {
        rawChannels = result;
        ChannelManager.Result channelResult = ChannelManager.apply(result, channelOrder, hiddenChannels);
        allChannels = channelResult.allSorted();
        channels = channelResult.visible();
        guide.setChannels(channels);
        emptyState = state;
        if (result.isEmpty()) {
            current = null;
            playback.stop();
            screen.status("");
            screen.clearBanner();
            screen.clearTransition();
            screen.closeGuide();
            refreshOptions();
            renderChannels();
            if (!screen.panelOpen()) screen.showChannels();
            return;
        }

        if (channels.isEmpty()) {
            current = null;
            playback.stop();
            screen.status("");
            screen.clearBanner();
            screen.clearTransition();
            screen.closeGuide();
            refreshOptions();
            emptyState = allHiddenEmptyState();
            renderChannels();
            if (!screen.panelOpen()) screen.showChannels();
            return;
        }

        boolean initialSelection = current == null;
        long previous = initialSelection ? preferences.getLong("last_channel", -1) : current.id();
        TvListings.Channel previousChannel = findChannel(previous);
        current = previousChannel == null ? channels.get(0) : previousChannel;
        boolean shouldTune = resumePlayback || initialSelection || current.id() != previous;
        resumePlayback = false;
        if (initialSelection) screen.hidePanels();
        if (shouldTune && playback.requestedChannelId() != current.id()) playback.tune(current.id());
        renderGuide();
        refreshOptions();
    }

    private void reapplyChannels() {
        if (rawChannels.isEmpty()) return;
        ChannelManager.Result channelResult = ChannelManager.apply(rawChannels, channelOrder, hiddenChannels);
        allChannels = channelResult.allSorted();
        channels = channelResult.visible();
        guide.setChannels(channels);

        if (channels.isEmpty()) {
            current = null;
            playback.stop();
            screen.status("");
            screen.clearBanner();
            screen.clearTransition();
            screen.closeGuide();
            refreshOptions();
            emptyState = allHiddenEmptyState();
            renderChannels();
            return;
        }

        boolean currentValid = current != null && findChannel(current.id()) != null;
        if (!currentValid) {
            long last = preferences.getLong("last_channel", -1);
            TvListings.Channel lastChan = findChannel(last);
            current = lastChan != null ? lastChan : channels.get(0);
            preferences.edit().putLong("last_channel", current.id()).apply();
            playback.tune(current.id());
        }

        renderChannels();
        renderGuide();
        refreshOptions();
    }

    private void renderGuide() {
        if (!active) return;
        renderChannels();
        refreshOpenGuide();
        if (screen.hasProgramOverlay()) {
            TvListings.Channel channel = findChannel(playback.channelId());
            if (channel != null) {
                long now = System.currentTimeMillis();
                screen.updateBanner(channel, nowPlaying(channel, now), nextProgram(channel, now),
                        playback.programBadges(channel.id()));
            }
        }
    }

    private void refreshOpenGuide() {
        if (screen.guideOpen()) {
            screen.updateGuide(visibleChannels(), currentId(), onlyFavorites, guide::schedule);
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

    private TvListings.Channel findAnyChannel(long id) {
        for (TvListings.Channel channel : allChannels) {
            if (channel.id() == id) return channel;
        }
        return findChannel(id);
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
        TvScreen.EmptyState state = channels.isEmpty()
                ? emptyState
                : new TvScreen.EmptyState(
                        getString(R.string.empty_no_favorites),
                        getString(R.string.empty_action_show_all_channels),
                        R.drawable.ic_channels,
                        this::filter);
        long now = System.currentTimeMillis();
        screen.channels(visible, favorites, currentId(), onlyFavorites, state,
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

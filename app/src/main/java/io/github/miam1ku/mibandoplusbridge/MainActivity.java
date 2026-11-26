// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import io.github.miam1ku.mibandoplusbridge.data.BandCatalog;
import io.github.miam1ku.mibandoplusbridge.data.BandStateRepository;
import io.github.miam1ku.mibandoplusbridge.data.BindingStore;
import io.github.miam1ku.mibandoplusbridge.data.HealthRecordStore;
import io.github.miam1ku.mibandoplusbridge.data.LocalPrefs;
import io.github.miam1ku.mibandoplusbridge.data.TodaySummary;
import io.github.miam1ku.mibandoplusbridge.data.TransportObservation;
import io.github.miam1ku.mibandoplusbridge.health.MiHealthJournal;
import io.github.miam1ku.mibandoplusbridge.health.MiHealthLogText;
import io.github.miam1ku.mibandoplusbridge.health.MiHealthSync;
import io.github.miam1ku.mibandoplusbridge.integration.BandDetailsActivity;
import io.github.miam1ku.mibandoplusbridge.integration.CredentialProvider;
import io.github.miam1ku.mibandoplusbridge.integration.DeviceCardProvider;
import io.github.miam1ku.mibandoplusbridge.integration.HealthQueueProvider;
import io.github.miam1ku.mibandoplusbridge.notify.BandNotificationListener;
import io.github.miam1ku.mibandoplusbridge.notify.SleepMusic;
import io.github.miam1ku.mibandoplusbridge.protocol.SppDiagnosticClient;
import io.github.miam1ku.mibandoplusbridge.service.BandLiveService;
import io.github.miam1ku.mibandoplusbridge.service.OwnershipController;
import io.github.miam1ku.mibandoplusbridge.service.PhoneStepControl;
import io.github.miam1ku.mibandoplusbridge.ui.BridgeScreen;
import io.github.miam1ku.mibandoplusbridge.ui.BridgeScreen.StepState;
import io.github.miam1ku.mibandoplusbridge.ui.BridgeScreen.Tone;
import io.github.miam1ku.mibandoplusbridge.ui.BridgePager;
import io.github.miam1ku.mibandoplusbridge.ui.Copy;
import io.github.miam1ku.mibandoplusbridge.ui.MetricGrid;
import io.github.miam1ku.mibandoplusbridge.ui.SetupProgress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Home screen. Reads top to bottom: band status with one primary action, first-run checklist,
 * things to fix now, features, help, management, advanced import and debug tools.
 * Device registration stays independent of connection ownership.
 */
public final class MainActivity extends AppCompatActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final int BLUETOOTH_PERMISSION = 1;
    private static final int CALL_PERMISSIONS = 2;
    enum Change { ADD, RECONNECT, REMOVE, RESTORE }

    private BridgeScreen screen;
    /** Page order of {@link BridgePager}: one function per page, most urgent first. */
    private static final int PAGE_OVERVIEW = 0;
    private static final int PAGE_HEALTH = 1;
    private static final int PAGE_DEVICE = 2;
    private static final int PAGE_LOG = 3;
    private static final int PAGE_SETTINGS = 4;

    /** The journal keeps 500 rows; a page that renders all of them is not a list anyone reads. */
    private static final int VISIBLE_LOGS = 100;

    private BridgePager pager;
    // status card
    private TextView deviceName;
    private TextView battery;
    private TextView statusChip;
    private TextView connectionHint;
    private TextView metricsLine;
    private TextView healthLine;
    private TextView ownershipStatus;
    private Button homePrimary;
    // today summary
    private TextView todayHeading;
    private LinearLayout todayCard;
    private MetricGrid todayGrid;
    private TextView todayNote;
    private boolean todayLoading;
    private long todayReadAtMs;
    /** Shortest gap between two throttled reads of the today card. */
    private static final long TODAY_MIN_INTERVAL_MS = 20_000L;
    /** Re-reads the card once the throttle window has passed. */
    private final Runnable todayTrailing = () -> refreshToday(true);
    private int todayColumns = 2;
    // data pipeline
    private TextView pipelineHeading;
    private LinearLayout pipelineCard;
    private BridgeScreen.TwoLines pipeLines;
    // setup card
    private LinearLayout setupCard;
    private TextView setupTitle;
    private TextView stepRoot;
    private TextView stepImport;
    private TextView stepAdd;
    private TextView primaryHint;
    private Button openKsu;
    // notices
    private LinearLayout nearbyNotice;
    private LinearLayout notifyNotice;
    private LinearLayout healthAccessNotice;
    // management
    private TextView manageHeading;
    private LinearLayout manageCard;
    private Button remove;
    private Button restore;
    // advanced
    private LinearLayout bonded;
    private TextView status;
    private TextView deviceLogHint;
    private Button open;
    private Button advancedRestore;
    // Mi Health manual sync
    private TextView miHealthLine;
    private Button miHealthSyncButton;
    private boolean miHealthSyncRunning;
    // Mi Health sync journal page
    private TextView logSummary;
    private LinearLayout logList;
    // OPPO Health's own pedometer
    private MaterialSwitch oppoStepSwitch;
    private TextView oppoStepLine;
    private boolean oppoStepWriting;

    private String selectedAddress = "";
    private String databaseImportNote = "";
    private boolean hostSupported;
    private boolean callPermissionsAsked;
    private boolean changingOwnership;
    private boolean versionPromptShown;
    private boolean launchHostAfterOpen;
    private boolean profileAfterRestore;
    private boolean rootGranted;
    private boolean probingRoot;
    private boolean sleepPauseWriting;
    private Change pendingPermission;

    /** Open the profile window when this restore task succeeded and the user came in from the capture entry. */
    static boolean openProfileAfterRestore(Change change, boolean restored) {
        return restored && change == Change.RESTORE;
    }

    private final ContentObserver deviceObserver = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { refreshDevice(); }
    };
    private final ContentObserver observer = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) {
            refresh();
            refreshDevice();
        }
    };
    private final Runnable expireRefresh = this::refresh;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        pager = BridgePager.attach(this, "小米手环桥接", "概览", "健康", "设备", "日志", "设置");

        // 概览: what the band is doing right now, what still blocks setup, what needs a permission.
        screen = pager.page(PAGE_OVERVIEW);
        buildStatusCard();
        buildSetupCard();
        buildNotices();
        buildTodaySection();
        buildPipeline();

        // 健康: the Mi Health hand-off and the sleep behaviour. Both are about the data, not the link.
        screen = pager.page(PAGE_HEALTH);
        buildSyncSection();
        buildOppoHealthSection();
        buildSleepSection();

        // 设备: entries to the per-device screens, then ownership (hand back / remove).
        screen = pager.page(PAGE_DEVICE);
        buildDeviceFunctions();
        buildManagement();

        // 日志: every run of the hand-off, so "did not sync" and "synced and skipped" differ.
        screen = pager.page(PAGE_LOG);
        buildLogPage();

        // 设置: help, permissions, the import window and the debug tools.
        screen = pager.page(PAGE_SETTINGS);
        buildHelp();
        buildAdvanced();
        screen = pager.page(PAGE_OVERVIEW);

        getContentResolver().registerContentObserver(CredentialProvider.URI, false, observer);
        getContentResolver().registerContentObserver(DeviceCardProvider.URI, false, deviceObserver);
        getContentResolver().registerContentObserver(HealthQueueProvider.URI, false, deviceObserver);
        refreshDevice();
        requestRoot();
    }

    // ---- layout ----

    private void buildStatusCard() {
        screen.heading("手环状态");
        LinearLayout card = screen.card();
        // The battery rides on the name line rather than on a line of its own: it is the one number
        // that never needs a label, and a card whose height is mostly one-line captions pushes the
        // numbers the page exists for below the fold.
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.addView(nameRow, new LinearLayout.LayoutParams(-1, -2));
        deviceName = screen.title(nameRow, "小米手环");
        deviceName.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        battery = screen.caption(nameRow, "");
        LinearLayout.LayoutParams batteryParams = new LinearLayout.LayoutParams(-2, -2);
        batteryParams.leftMargin = BridgeScreen.dp(this, 8);
        battery.setLayoutParams(batteryParams);
        // The chip shares the name line instead of taking one of its own: on a page whose job is the
        // numbers below, a status card that spends three lines saying "connected" is the wrong trade.
        statusChip = screen.chip(nameRow, "正在读取", Tone.NEUTRAL);
        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(-2, -2);
        chipParams.leftMargin = BridgeScreen.dp(this, 10);
        statusChip.setLayoutParams(chipParams);
        connectionHint = screen.caption(card, "");
        metricsLine = screen.caption(card, "");
        healthLine = screen.caption(card, "");
        ownershipStatus = screen.bodyText(card, "");
        ownershipStatus.setTextSize(14);
        homePrimary = screen.filled(card, Copy.setupLabel(SetupProgress.Step.ROOT), this::runHomePrimary);
        connectionHint.setVisibility(View.GONE);
        metricsLine.setVisibility(View.GONE);
        healthLine.setVisibility(View.GONE);
    }

    /**
     * Today's numbers, pulled from the same store the OPPO device card reads.
     *
     * <p>The block stays hidden until the band is registered: a grid of four em dashes is not a
     * place to start, and the first-run checklist right above it already says what to do.
     */
    private void buildTodaySection() {
        todayHeading = screen.heading("今日健康");
        todayCard = screen.card();
        // Decided from the short form of each value's upper bound, never from today's numbers: a
        // layout that switches columns on the day someone sleeps eleven hours is a layout that
        // rearranges itself while they are reading it. Sleep's short form is the hour-minute one,
        // so "23h59m" is what decides — at 1.5× that reaches 149dp and the two columns are simply
        // not there any more. A rounded "9.4h" third candidate would push the switch later, but it
        // changes what the cell says to save a layout, and one cell reading "9.4h" next to three
        // reading "79" is a bug report waiting to be filed.
        int columns = MetricGrid.fitsTwoColumns(this,
                new String[]{"1.2万", "199", "23h59m", "100"}, "") ? 2 : 1;
        todayColumns = columns;
        todayGrid = MetricGrid.attach(this, todayCard, columns, 4);
        todayGrid.speak(0, "今日步数");
        todayGrid.speak(1, "最近心率");
        todayGrid.speak(2, "昨夜睡眠");
        todayGrid.speak(3, "血氧");
        todayNote = screen.caption(todayCard, "");
        todayNote.setVisibility(View.GONE);
        setShown(todayCard, false);
        todayHeading.setVisibility(View.GONE);
    }

    /**
     * The two hand-offs the app performs, as one block that leads to the log page.
     *
     * <p>One tap target, not one per line: both lines answer "is my data where it should be", and a
     * reader who wants either answer wants the log underneath both. No rule between them — a
     * hairline here reads as two independent links and promises a destination the second one does
     * not have. The second line carries its own subject ("小米健康补写") because the first one is
     * about the band, and a bare "11:11 · 6 条" under it would be read as six of something from the
     * band.
     */
    private void buildPipeline() {
        pipelineHeading = screen.heading("数据管线");
        pipelineCard = screen.card();
        pipeLines = screen.linkRow(pipelineCard, "手环拉取", () -> pager.pick(PAGE_LOG));
    }

    private void buildSetupCard() {
        setupCard = screen.card();
        setupTitle = screen.overline(setupCard, "首次设置");
        stepRoot = screen.stepRow(setupCard, 1, "授权 Root", StepState.CURRENT);
        stepImport = screen.stepRow(setupCard, 2, "导入绑定", StepState.LATER);
        stepAdd = screen.stepRow(setupCard, 3, "添加到健康", StepState.LATER);
        primaryHint = screen.caption(setupCard, "");
        openKsu = screen.outlined(setupCard, "打开 Root 管理器", this::openKernelSu);
        LinearLayout prereq = screen.expander(setupCard, "开始前准备", null);
        screen.caption(prereq, Copy.SETUP_PREREQ);
    }

    private void buildNotices() {
        nearbyNotice = screen.noticeCard(Tone.ATTENTION);
        screen.bodyText(nearbyNotice, "授权附近设备后即可连接手环。");
        screen.filled(nearbyNotice, "授权附近设备", () -> {
            pendingPermission = null;
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, BLUETOOTH_PERMISSION);
        });
        notifyNotice = screen.noticeCard(Tone.NEUTRAL);
        screen.bodyText(notifyNotice, "开启通知后，可在通知栏看到手环连接状态。");
        screen.outlined(notifyNotice, "开启通知", this::openAppNotificationSettings);
        healthAccessNotice = screen.noticeCard(Tone.ACTIVE);
        screen.bodyText(healthAccessNotice,
                "在系统设置里把 OPPO 健康的通知使用权关掉再打开一次，手机通知就能转到手环。");
        screen.outlined(healthAccessNotice, "打开通知使用权", this::openHealthListenerSettings);
        setShown(nearbyNotice, false);
        setShown(notifyNotice, false);
        setShown(healthAccessNotice, false);
    }

    private void buildDeviceFunctions() {
        screen.heading("设备功能");
        LinearLayout links = screen.card();
        screen.navRow(links, "设备详情", () -> startActivity(new Intent(this, BandDetailsActivity.class)));
        screen.navRow(links, "天气同步", () -> startActivity(new Intent(this, WeatherActivity.class)));
        screen.caption(links, "在控制中心添加「手环」磁贴，下拉或点按即可连接。");
    }

    private void buildSyncSection() {
        screen.heading("同步到小米健康");
        buildMiHealthCard();
    }

    private void buildSleepSection() {
        screen.heading("睡眠");
        LinearLayout sleepCard = screen.card();
        boolean sleepStateUnsupported = BandLiveService.sleepStateUnsupportedForCurrentDevice(this);
        if (sleepStateUnsupported) {
            screen.bodyText(sleepCard, "当前手环会在下次同步时更新睡眠状态。");
            return;
        }
        MaterialSwitch sleepPause = screen.switchRow(sleepCard, "入睡后暂停音乐",
                "手环报告入睡时暂停正在播放的音乐，醒来后保持暂停。");
        sleepPause.setChecked(SleepMusic.enabled(this));
        sleepPause.setOnCheckedChangeListener((button, checked) -> {
            if (sleepPauseWriting) return;
            applySleepPause(button, checked);
        });
    }

    /**
     * OPPO Health counts steps on its own.
     *
     * <p>Measured on 2026-11-24: the band reported 1241 for the day, Health's home card showed
     * 6568, and 111 steps taken with the phone in hand moved the card to 6679 while the band stayed
     * at 1241. Health's {@code StepCounterListener} reads the phone's step sensor and adds it to the
     * number on the card; the band's own day is a separate {@code sport_mode=-3} row that the card
     * never shows. Revoking one permission is the whole fix, and it leaves Health's stored history
     * untouched — only the phone half of the count stops.
     */
    private void buildOppoHealthSection() {
        screen.heading("OPPO 健康");
        LinearLayout card = screen.card();
        oppoStepSwitch = screen.switchRow(card, "关闭手机计步",
                "OPPO 首页的步数是它自己读手机传感器算的，会叠上你随身带手机走的步，所以比手环大。"
                        + "关掉后手机这一路就停止加数。代价是「自动识别健走/跑步」和久坐提醒也会一起停用。");
        oppoStepSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (oppoStepWriting) return;
            applyOppoStep(button, checked);
        });
        oppoStepLine = screen.caption(card, "");
        screen.caption(card, "改动只影响手机这一步计数，OPPO 健康里已有的历史保持原样；"
                + "它下次弹窗要回权限时，桥接再打开会重新关掉。");
        refreshOppoStep();
    }

    private void refreshOppoStep() {
        if (oppoStepSwitch == null || isDestroyed()) return;
        boolean installed = PhoneStepControl.installed(this);
        boolean suspended = PhoneStepControl.suspended(this);
        setOppoStepChecked(oppoStepSwitch, suspended);
        oppoStepSwitch.setEnabled(installed && rootGranted);
        oppoStepLine.setText(!installed ? "手机上装着 OPPO 健康时才有这一项，现在可以跳过。"
                : suspended ? "现在：已关闭。手机这一路停止计数，首页新增的步数全部来自手环。"
                : "现在：开启。OPPO 首页会把手机走的步也算进去，通常比手环大。");
    }

    private void applyOppoStep(android.widget.CompoundButton toggle, boolean suspend) {
        if (!rootGranted) {
            setOppoStepChecked(toggle, !suspend);
            requestRoot();
            return;
        }
        setOppoStepChecked(toggle, suspend);
        oppoStepSwitch.setEnabled(false);
        oppoStepLine.setText(suspend ? "正在关闭…OPPO 健康会被系统重启一次。" : "正在恢复…");
        worker.execute(() -> {
            String failure = null;
            try {
                PhoneStepControl.setSuspended(this, suspend);
            } catch (OwnershipController.Failure error) {
                failure = error.code;
            }
            final String code = failure;
            main.post(() -> {
                if (isDestroyed()) return;
                oppoStepSwitch.setEnabled(true);
                refreshOppoStep();
                if (code != null) oppoStepLine.setText(describeOppoStep(code));
            });
        });
    }

    private void setOppoStepChecked(android.widget.CompoundButton toggle, boolean checked) {
        oppoStepWriting = true;
        toggle.setChecked(checked);
        oppoStepWriting = false;
    }

    private static String describeOppoStep(String code) {
        return switch (code) {
            case "ROOT_REQUIRED", "ROOT_ACTION_FAILED", "ROOT_ACTION_TIMEOUT" ->
                    "需要 Root 权限：先在 Root 管理器里允许本应用，再拨一次。";
            case "HEALTH_APP_MISSING" -> "手机上装着 OPPO 健康时才能用这一项。";
            case "PHONE_STEPS_REVOKE_UNCONFIRMED", "PHONE_STEPS_GRANT_UNCONFIRMED" ->
                    "系统把这次改动挡下了。OPPO 健康可能正在弹权限框，先把它划掉再试。";
            default -> "稍后再拨一次，改动会重新下发。";
        };
    }

    /**
     * Sync into Mi Health. The bridge is the only BLE client while it owns the band, so Mi Health
     * never receives the band's files for those days; this hands it the rows it is missing and lets
     * its own pipeline aggregate, score and upload them.
     *
     * <p>The card carries both paths the user asked for: the button for a deliberate run, and a
     * switch for the unattended one. The switch is honest about when it fires — screen off only —
     * because a run restarts Mi Health and doing that under the user's hands is the one thing that
     * would make the feature worse than the gap it fixes.
     */
    private void buildMiHealthCard() {
        LinearLayout card = screen.card();
        screen.overline(card, "小米健康");
        miHealthLine = screen.caption(card, "");
        miHealthSyncButton = screen.outlined(card, "立即同步到小米健康", this::runMiHealthSync);
        MaterialSwitch auto = screen.switchRow(card, "息屏时自动同步",
                "桥接同步完手环后，在屏幕关闭时自动补写一次；亮屏期间先停一停，避免小米健康被"
                        + "强制重启打断。");
        auto.setChecked(MiHealthSync.autoEnabled(this));
        auto.setOnCheckedChangeListener((button, checked) -> MiHealthSync.setAutoEnabled(this, checked));
        screen.navRow(card, "查看同步日志", () -> pager.pick(PAGE_LOG));
        screen.caption(card, "把桥接已采集的睡眠、步数、心率、血氧、压力和卡路里补写进小米健康，"
                + "由它自己汇总、评分并上传云端。已存在的数据会跳过；同步期间小米健康会短暂重启一次。");
        refreshMiHealthCard();
    }

    private void refreshMiHealthCard() {
        if (miHealthLine == null) return;
        String last = MiHealthSync.lastText(this);
        miHealthLine.setText(last.isEmpty() ? "点「立即同步到小米健康」跑第一次。" : last);
        if (miHealthSyncButton != null) miHealthSyncButton.setEnabled(!miHealthSyncRunning);
    }

    private void runMiHealthSync() {
        if (miHealthSyncRunning || worker.isShutdown()) return;
        if (!rootGranted) {
            requestRoot();
            return;
        }
        if (!isUnlocked()) {
            miHealthLine.setText("解锁手机后再同步。");
            return;
        }
        miHealthSyncRunning = true;
        refreshMiHealthCard();
        miHealthLine.setText("正在同步…小米健康会短暂重启。");
        worker.execute(() -> {
            MiHealthSync.Report report = MiHealthSync.run(this, 7);
            main.post(() -> {
                if (isDestroyed()) return;
                miHealthSyncRunning = false;
                refreshMiHealthCard();
                miHealthLine.setText(report.summary());
                renderLogPage();
                refreshToday(true);
                if (!report.ok()) {
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("同步停在半路")
                            .setMessage(report.summary())
                            .setPositiveButton("知道了", null)
                            .show();
                }
            });
        });
    }

    /** The journal as a page of its own: one run per row, newest first. */
    private void buildLogPage() {
        screen.heading("同步记录");
        logSummary = screen.bodyText(screen.glassStrip(screen.body), "");

        LinearLayout actions = screen.card();
        screen.outlined(actions, "刷新", this::renderLogPage);
        screen.dangerOutlined(actions, "清空记录", this::confirmClearLog);
        screen.caption(actions, "每次同步都会留一条记录，最多保留 500 条；这里显示最近 "
                + VISIBLE_LOGS + " 条。");

        logList = screen.card();
        renderLogPage();
    }

    private void renderLogPage() {
        if (logList == null || isDestroyed()) return;
        logList.removeAllViews();
        java.util.List<MiHealthJournal.Entry> entries = MiHealthJournal.read(this, VISIBLE_LOGS);
        logSummary.setText(MiHealthLogText.summary(this, entries));
        if (entries.isEmpty()) {
            screen.caption(logList, "开启自动同步后，息屏期间每次补写也会记在这里。");
            return;
        }
        for (int index = 0; index < entries.size(); index++) {
            if (index > 0) screen.hairline(logList);
            MiHealthJournal.Entry entry = entries.get(index);
            TextView row = screen.bodyText(logList, MiHealthLogText.describe(this, entry));
            row.setTextSize(14);
            screen.setTone(row, entry.ok() ? Tone.NEUTRAL : Tone.ATTENTION);
        }
        if (entries.size() >= VISIBLE_LOGS) {
            screen.caption(logList, "只显示最近 " + VISIBLE_LOGS + " 条。");
        }
    }

    private void confirmClearLog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("清空同步记录？")
                .setMessage("只删除这份日志，小米健康里已写入的数据原样保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> {
                    MiHealthJournal.clear(this);
                    renderLogPage();
                })
                .show();
    }

    private void applySleepPause(android.widget.CompoundButton toggle, boolean enabled) {        if (SleepMusic.setEnabled(this, enabled)) {
            setSleepPauseChecked(toggle, enabled);
            BandLiveService.sleepPauseChanged(this);
            return;
        }
        setSleepPauseChecked(toggle, !enabled);
        ownershipStatus.setText("开关状态保存中断，再拨一次即可。");
    }

    private void setSleepPauseChecked(android.widget.CompoundButton toggle, boolean checked) {
        sleepPauseWriting = true;
        toggle.setChecked(checked);
        sleepPauseWriting = false;
    }

    private void buildHelp() {
        screen.heading("帮助与支持");
        LinearLayout help = screen.card();
        screen.navRow(help, "使用说明", this::showSetupGuide);
        screen.navRow(help, "系统权限设置", () -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        screen.navRow(help, "发送调试日志", this::shareDebugLog);
    }

    private void buildManagement() {
        manageHeading = screen.heading("管理");
        manageCard = screen.card();
        restore = screen.outlined(manageCard, "恢复小米运动健康", () -> changeOwnership(Change.RESTORE));
        remove = screen.dangerOutlined(manageCard, "从健康移除", () -> new MaterialAlertDialogBuilder(this)
                .setTitle("从健康移除这只手环？")
                .setMessage("桥接会断开连接，把手环交还小米运动健康。健康历史和加密绑定都会保留，之后可随时重新添加。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (dialog, which) -> changeOwnership(Change.REMOVE))
                .show());
        screen.caption(manageCard, "绑定和健康历史始终保存在本机。");
    }

    private void buildAdvanced() {
        LinearLayout advanced = screen.expander(screen.body, "高级设置", this::refreshBonded);
        LinearLayout importCard = screen.card(advanced);
        screen.overline(importCard, "绑定导入");
        screen.caption(importCard, "首页的「导入绑定」直接读取小米运动健康的本地数据库。"
                + "这里保留旧式导入窗口：选择已配对设备，在 120 秒内由官方应用重新读取绑定。");
        bonded = new LinearLayout(this);
        bonded.setOrientation(LinearLayout.VERTICAL);
        importCard.addView(bonded);
        String preselected = getIntent().getStringExtra("deviceAddress");
        if (preselected != null && preselected.matches("[0-9A-F]{2}(:[0-9A-F]{2}){5}")) {
            selectedAddress = preselected;
        }
        open = screen.filled(importCard, "开启导入窗口（120 秒）", () -> {
            if (!hostSupported) return;
            if (selectedAddress.isBlank()) {
                status.setText("先在上方选择一只已配对设备。");
                return;
            }
            Bundle selected = new Bundle();
            selected.putString("address", selectedAddress);
            action("openWindow", selected);
        });
        open.setEnabled(false);
        screen.outlined(importCard, "关闭导入和采集窗口", () -> action("closeWindow", null));
        screen.outlined(importCard, "打开小米运动健康", this::launchMiHealth);
        advancedRestore = screen.outlined(importCard, "恢复官方管理", () -> changeOwnership(Change.RESTORE));
        status = screen.bodyText(importCard, "正在检查小米运动健康…");
        status.setTextSize(14);

        LinearLayout debug = screen.card(advanced);
        screen.overline(debug, "调试");
        screen.navRow(debug, "拉取设备日志", () -> startActivity(new Intent(this, DeviceLogActivity.class)));
        screen.navRow(debug, "协议测试", () -> startActivity(new Intent(this, LabActivity.class)));
        if (BuildConfig.DEBUG) {
            screen.navRow(debug, "独立连接诊断", () -> startActivity(new Intent(this, DiagnosticActivity.class)));
            screen.outlined(debug, "采集连接参数（120 秒）", this::startProfileCapture);
            screen.outlined(debug, "采集协议消息（120 秒）", () -> new MaterialAlertDialogBuilder(this)
                    .setTitle("采集所选手环的协议消息")
                    .setMessage("消息可能包含通知和健康数据。原始内容保存在本机私有目录，用于本次协议分析。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("开始采集", (dialog, which) -> action("openCapture", null))
                    .show());
        }
    }

    private static void setShown(LinearLayout cardInner, boolean shown) {
        View frame = (View) cardInner.getParent();
        (frame == null ? cardInner : frame).setVisibility(shown ? View.VISIBLE : View.GONE);
    }

    // ---- lifecycle ----

    @Override protected void onResume() {
        super.onResume();
        refreshNotices();
        refreshMiHealthCard();
        // The journal grows while the app is in the background (the automatic path runs on screen
        // off), so the log page has to reread it rather than show the rows it was built with.
        renderLogPage();
        // Forced: the app may have been in the background for a whole night, which is exactly when
        // the numbers and the "待补写" state change without any observer firing.
        refreshToday(true);
        promptHostVersions();
        // Health asks for the sensor again from its own dialog, and a tap there restores the grant
        // without this app hearing about it. The stored choice is the one that wins.
        if (rootGranted) worker.execute(() -> {
            if (PhoneStepControl.reconcile(this)) main.post(this::refreshOppoStep);
        });
        if (!rootGranted) requestRoot();
        else continueInit();
    }

    private void continueInit() {
        refreshOwnership();
        refreshDevice();
        refreshBonded();
        refreshOppoStep();
        if (hostSupported) refresh();
        else worker.execute(() -> {
            boolean supported = HostIdentity.installed(this, HostIdentity.MI_PACKAGE);
            main.post(() -> {
                if (isDestroyed()) return;
                hostSupported = supported;
                open.setEnabled(supported && rootGranted);
                if (supported) refresh();
                else status.setText("安装小米运动健康并配对手环后，即可使用导入窗口。");
            });
        });
        ensureCallPermissions();
    }

    @Override protected void onDestroy() {
        getContentResolver().unregisterContentObserver(observer);
        getContentResolver().unregisterContentObserver(deviceObserver);
        main.removeCallbacks(expireRefresh);
        main.removeCallbacks(todayTrailing);
        worker.shutdown();
        super.onDestroy();
    }

    // ---- notices ----

    private void refreshNotices() {
        if (nearbyNotice == null) return;
        setShown(nearbyNotice, !hasBluetoothPermission());
        setShown(notifyNotice, !getSystemService(NotificationManager.class).areNotificationsEnabled());
        LocalPrefs prefs = LocalPrefs.open(this, BandNotificationListener.SETTINGS);
        boolean healthAccess = prefs.getBoolean("healthSecure", false)
                && !prefs.getBoolean("healthApproved", true)
                && prefs.getBoolean("healthGrantFailed", false);
        setShown(healthAccessNotice, healthAccess);
    }

    private void openAppNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        try {
            startActivity(intent);
        } catch (RuntimeException fallback) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private void openHealthListenerSettings() {
        Intent detail = new Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS");
        detail.putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                "com.heytap.health/com.heytap.health.watch.commonnotification.HeytapNotificationListenerService");
        try {
            startActivity(detail);
        } catch (RuntimeException failure) {
            try {
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            } catch (RuntimeException ignored) {
                ownershipStatus.setText("在系统设置 → 通知 → 通知使用权中找到 OPPO 健康。");
            }
        }
    }

    // ---- help ----

    private void showSetupGuide() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("使用说明")
                .setMessage(Copy.GUIDE)
                .setPositiveButton("好的", null)
                .show();
    }

    /** Longest wait for the band log inside the debug export; the device-log screen has no cap. */
    private static final long DEBUG_DEVICE_LOG_WAIT_MINUTES = 5;
    private boolean debugExporting;

    /** Pulls the band's own log once, then zips it with the session trace and shares the bundle. */
    private void shareDebugLog() {
        if (debugExporting) return;
        debugExporting = true;
        View content = getLayoutInflater().inflate(R.layout.dialog_device_log_progress, null, false);
        TextView progressText = content.findViewById(R.id.device_log_progress_status);
        progressText.setText("正在拉取手环日志，稍后一并放进调试包…");
        java.util.concurrent.CompletableFuture<DebugDeviceLog> pulled =
                new java.util.concurrent.CompletableFuture<>();
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("正在准备调试日志")
                .setView(content)
                .setCancelable(false)
                .setNegativeButton("跳过手环日志",
                        (ignored, which) -> pulled.complete(new DebugDeviceLog(null, "USER_SKIPPED")))
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this, "DEBUG_EXPORT device-log pull");
        BandLiveService.requestDeviceLogExport(this, progress -> main.post(() -> {
            if (isDestroyed() || pulled.isDone() || progress.totalParts() <= 0) return;
            int percent = Math.min(100, progress.currentPart() * 100 / progress.totalParts());
            progressText.setText("正在接收手环日志 " + progress.currentPart() + "/"
                    + progress.totalParts() + " 片 · " + percent + "%");
        })).whenComplete((received, failure) -> {
            boolean used = pulled.complete(failure == null
                    ? new DebugDeviceLog(received.file(), "OK")
                    : new DebugDeviceLog(null, debugPullCode(failure)));
            // Skipped or past the wait: the bundle already left, so drop the late copy.
            if (!used && received != null) worker.execute(() -> deleteDeviceLogCopy(received.file()));
        });
        pulled.completeOnTimeout(new DebugDeviceLog(null, "WAIT_LIMIT"),
                DEBUG_DEVICE_LOG_WAIT_MINUTES, java.util.concurrent.TimeUnit.MINUTES);
        pulled.thenAcceptAsync(part -> {
            main.post(() -> {
                if (!isDestroyed() && dialog.isShowing()) progressText.setText("正在打包调试日志…");
            });
            io.github.miam1ku.mibandoplusbridge.data.SessionLog.line(this,
                    "DEBUG_EXPORT device-log " + part.code());
            exportDebugBundle(part, dialog);
        }, worker);
    }

    private record DebugDeviceLog(java.io.File file, String code) {}

    /** The service fails with a short code such as DEVICE_LOG_DEVICE_BUSY or LIVE_SESSION_REQUIRED. */
    private static String debugPullCode(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message != null && message.matches("[A-Z0-9_]{3,64}")) return message;
        return cause.getClass().getSimpleName();
    }

    private static String debugDeviceLogHint(String code) {
        return switch (code) {
            case "OK", "USER_SKIPPED" -> null;
            case "LIVE_SESSION_REQUIRED" -> "调试包已生成。手环连上后再导出，可附带手环日志。";
            case "DEVICE_LOG_RECEIVE_ACTIVE" -> "调试包已生成。设备日志页正在拉取，完成后再导出即可附带。";
            case "WAIT_LIMIT" -> "调试包已生成。手环日志较大，可在「拉取设备日志」页单独拉取。";
            default -> "调试包已生成，手环日志这次留空（" + code + "）。";
        };
    }

    private void exportDebugBundle(DebugDeviceLog part, android.app.Dialog dialog) {
        try {
            java.io.File file = io.github.miam1ku.mibandoplusbridge.data.SessionLog.export(
                    this, part.file(), part.code());
            deleteDeviceLogCopy(part.file());
            Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
                    "io.github.miam1ku.mibandoplusbridge.debuglog", file);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/zip");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "小米手环桥接调试日志");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent qq = new Intent(send);
            qq.setPackage("com.tencent.mobileqq");
            main.post(() -> {
                debugExporting = false;
                if (dialog.isShowing()) dialog.dismiss();
                if (isDestroyed()) return;
                String hint = debugDeviceLogHint(part.code());
                if (hint != null) android.widget.Toast.makeText(this, hint,
                        android.widget.Toast.LENGTH_LONG).show();
                if (qq.resolveActivity(getPackageManager()) != null) startActivity(qq);
                else startActivity(Intent.createChooser(send, "发送调试日志"));
            });
        } catch (Exception failure) {
            deleteDeviceLogCopy(part.file());
            main.post(() -> {
                debugExporting = false;
                if (dialog.isShowing()) dialog.dismiss();
                if (!isDestroyed()) android.widget.Toast.makeText(this,
                        "调试日志生成中断，稍后再试一次", android.widget.Toast.LENGTH_SHORT).show();
            });
        }
    }

    /** The pull lands in cache/device-logs/<stamp>/; the zip now holds the only copy we need. */
    private void deleteDeviceLogCopy(java.io.File received) {
        if (received == null) return;
        java.io.File directory = received.getParentFile();
        java.io.File root = new java.io.File(getCacheDir(), "device-logs");
        if (directory == null || !root.equals(directory.getParentFile())) return;
        java.io.File[] files = directory.listFiles();
        if (files != null) {
            for (java.io.File file : files) file.delete();
        }
        directory.delete();
    }

    private void promptHostVersions() {
        if (isDestroyed() || versionPromptShown) return;
        ArrayList<HostVersionNotice.Installed> installed = new ArrayList<>();
        for (HostVersionNotice.Expectation expect : HostVersionNotice.EXPECTATIONS) {
            try {
                var info = getPackageManager().getPackageInfo(expect.packageName(), 0);
                installed.add(new HostVersionNotice.Installed(expect.packageName(), info.versionName,
                        info.getLongVersionCode(), true));
            } catch (PackageManager.NameNotFoundException missing) {
                installed.add(new HostVersionNotice.Installed(expect.packageName(), "", 0, false));
            }
        }
        String finger = HostVersionNotice.fingerprint(installed);
        if (finger.isEmpty()) return;
        SharedPreferences prefs = getSharedPreferences("host-version-notice", MODE_PRIVATE);
        if (finger.equals(prefs.getString("dismissed", ""))) return;
        versionPromptShown = true;
        new MaterialAlertDialogBuilder(this)
                .setTitle("建议使用实测版本")
                .setMessage(HostVersionNotice.message(installed))
                .setCancelable(true)
                .setPositiveButton("好的", (dialog, which) -> prefs.edit().putString("dismissed", finger).apply())
                .setOnCancelListener(dialog -> prefs.edit().putString("dismissed", finger).apply())
                .show();
    }

    // ---- root ----

    private void requestRoot() {
        if (worker.isShutdown() || probingRoot || changingOwnership) return;
        probingRoot = true;
        ownershipStatus.setText("正在检查 Root 授权…");
        worker.execute(() -> {
            boolean granted = new OwnershipController(this).probeRoot();
            main.post(() -> {
                if (isDestroyed()) return;
                probingRoot = false;
                rootGranted = granted;
                ownershipStatus.setText(granted ? "Root 权限已授权。" : Copy.setupHint(SetupProgress.Step.ROOT));
                updateControls();
                refreshDevice();
                if (granted) continueInit();
            });
        });
    }

    private void openKernelSu() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("me.weishu.kernelsu");
        if (launch == null) {
            ownershipStatus.setText("安装 Root 管理器后，为本应用打开超级用户。");
            return;
        }
        startActivity(launch);
    }

    // ---- ownership ----

    private void refreshOwnership() {
        if (worker.isShutdown() || changingOwnership || !rootGranted) return;
        worker.execute(() -> {
            if (!isUnlocked()) return;
            OwnershipController owner = new OwnershipController(this);
            BandStateRepository repository = new BandStateRepository(this);
            String message;
            try {
                boolean registered = repository.isRegistered();
                boolean ready = owner.nativeReady();
                if (!registered) {
                    BandLiveService.stop(this);
                    if (!SppDiagnosticClient.stopAllAndWait()) {
                        throw new OwnershipController.Failure("DIAGNOSTIC_SOCKET_STILL_ACTIVE");
                    }
                    repository.disconnected();
                }
                if (registered && ready && hasBluetoothPermission()) {
                    BandLiveService.start(this);
                }
                message = !registered
                        ? (needsOfficialRestore()
                                ? "小米运动健康仍在暂停。可以添加设备，或在「管理」中恢复小米运动健康。"
                                : "")
                        : "NATIVE".equals(owner.mode())
                                ? "由桥接管理，连接状态以手环实际响应为准。"
                                : "当前由小米运动健康管理，点「立即同步」重新接管。";
            } catch (Exception failure) {
                message = repository.isRegistered()
                        ? "设备登记已保留。点「立即同步」再连接一次，或恢复官方管理。"
                        : "点「恢复小米运动健康」再试一次。";
            }
            String idle = message;
            main.post(() -> {
                if (isDestroyed()) return;
                ownershipStatus.setText(databaseImportNote.isEmpty() ? idle : databaseImportNote);
                updateControls();
            });
        });
    }

    private void changeOwnership(Change change) {
        changeOwnership(change, false, false);
    }

    private void changeOwnership(Change change, boolean manualSync) {
        changeOwnership(change, manualSync, false);
    }

    private void changeOwnership(Change change, boolean manualSync, boolean queuedSyncNotice) {
        if (worker.isShutdown() || changingOwnership) return;
        if (!rootGranted) {
            requestRoot();
            return;
        }
        if (!isUnlocked()) {
            ownershipStatus.setText("解锁手机后再管理设备。");
            return;
        }
        changingOwnership = true;
        updateControls();
        ownershipStatus.setText(manualSync ? "正在连接并立即同步…" : "正在处理，请稍候…");
        worker.execute(() -> {
            OwnershipController owner = new OwnershipController(this);
            BandStateRepository repository = new BandStateRepository(this);
            String message;
            boolean registrationSaved = false;
            boolean removalSaved = false;
            boolean officialRestored = false;
            boolean[] syncAccepted = {false};
            try {
                if (change == Change.ADD || change == Change.RECONNECT) {
                    if (!isUnlocked()) throw new IllegalStateException("USER_LOCKED");
                    if (!owner.probeRoot()) throw new OwnershipController.Failure("ROOT_REQUIRED");
                    if (!hasBluetoothPermission()) throw new IllegalStateException("NEARBY_PERMISSION_REQUIRED");
                    if (change == Change.ADD) {
                        repository.registerDevice();
                        registrationSaved = true;
                    }
                    if (!repository.isRegistered()) throw new IllegalStateException("DEVICE_NOT_REGISTERED");
                    if (!owner.nativeReady()) owner.takeOver();
                    if (!repository.isRegistered() || !owner.nativeReady()) {
                        throw new IllegalStateException("NATIVE_OWNERSHIP_REQUIRED");
                    }
                    BandLiveService.start(this);
                    String accepted = manualSync
                            ? BandLiveService.requestManualSync(this)
                            : BandLiveService.requestSync(this);
                    if (!"ACCEPTED".equals(accepted)) throw new IllegalStateException(accepted);
                    syncAccepted[0] = true;
                    message = manualSync ? "正在连接并立即同步。" : "设备已登记，正在连接。";
                } else {
                    if (change == Change.REMOVE) {
                        repository.unregisterDevice();
                        removalSaved = true;
                        main.post(this::refreshDevice);
                    }
                    BandLiveService.stop(this);
                    if (!SppDiagnosticClient.stopAllAndWait()) {
                        throw new OwnershipController.Failure("DIAGNOSTIC_SOCKET_STILL_ACTIVE");
                    }
                    repository.disconnected();
                    owner.restoreOfficial();
                    officialRestored = change == Change.RESTORE;
                    message = change == Change.REMOVE
                            ? "已移除，小米运动健康已恢复。绑定和健康历史都已保留。"
                            : "小米运动健康已恢复。绑定和健康历史都已保留。";
                }
            } catch (Exception failure) {
                String hint = Copy.ownershipFailure(failureCode(failure));
                if (removalSaved || (change == Change.RESTORE && !repository.isRegistered())) {
                    message = "已移除，小米运动健康待恢复。" + hint;
                } else if (change == Change.REMOVE) {
                    message = "设备仍在登记中，再点一次「从健康移除」。";
                } else if (registrationSaved || (change == Change.RECONNECT && repository.isRegistered())) {
                    message = "设备登记已保留，连接稍后继续。" + hint;
                } else {
                    message = hint;
                }
            }
            String visible = queuedSyncNotice && syncAccepted[0]
                    ? "当前同步已排队，睡眠保护解除后继续。" : message;
            boolean followProfile = openProfileAfterRestore(change, officialRestored);
            main.post(() -> {
                if (isDestroyed()) return;
                changingOwnership = false;
                ownershipStatus.setText(visible);
                updateControls();
                refreshDevice();
                if (profileAfterRestore && followProfile) {
                    profileAfterRestore = false;
                    startProfileWindow();
                }
            });
        });
    }

    private static String failureCode(Exception failure) {
        return failure instanceof OwnershipController.Failure
                ? ((OwnershipController.Failure) failure).code : failure.getMessage();
    }

    private void requestNative(Change change) {
        if (changingOwnership) return;
        if (!rootGranted) {
            requestRoot();
            return;
        }
        if (!isUnlocked()) {
            ownershipStatus.setText("解锁手机后再管理设备。");
            return;
        }
        if (!hasBluetoothPermission()) {
            pendingPermission = change;
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, BLUETOOTH_PERMISSION);
            return;
        }
        if (change == Change.RECONNECT) {
            confirmManualSync();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("添加到健康并接管？")
                .setMessage("小米运动健康会暂停，由桥接接管手环。开始前请结束表盘安装、OTA 升级和 NFC 操作。"
                        + "绑定和健康历史都会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("添加并接管", (dialog, which) -> changeOwnership(change))
                .show();
    }

    private void confirmManualSync() {
        if (!BandLiveService.isSleepProtectionWindow(java.time.LocalTime.now().getHour())) {
            changeOwnership(Change.RECONNECT, true);
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("立即同步健康记录？")
                .setMessage("当前处于睡眠保护时段。立即同步可能结束手环当前的睡眠文件，"
                        + "导致睡眠图出现短暂清醒。是否仍要同步？")
                .setNegativeButton("等待自动同步", (dialog, which) ->
                        changeOwnership(Change.RECONNECT, false, true))
                .setPositiveButton("仍然同步", (dialog, which) ->
                        changeOwnership(Change.RECONNECT, true))
                .show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CALL_PERMISSIONS) {
            BandLiveService.refreshNotificationSettings(this);
            return;
        }
        if (requestCode != BLUETOOTH_PERMISSION) return;
        Change requested = pendingPermission;
        pendingPermission = null;
        refreshNotices();
        refreshDevice();
        refreshBonded();
        if (hasBluetoothPermission()) {
            if (requested != null) requestNative(requested);
            else refreshOwnership();
        } else {
            ownershipStatus.setText("授权附近设备后即可添加和连接，已有登记保持原样。");
        }
    }

    private void ensureCallPermissions() {
        if (callPermissionsAsked || !isUnlocked() || !new BandStateRepository(this).isRegistered()) return;
        boolean phone = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
        boolean hangup = checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED;
        if (phone && hangup) return;
        callPermissionsAsked = true;
        if (!phone && !hangup) {
            requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS},
                    CALL_PERMISSIONS);
        } else if (!phone) {
            requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, CALL_PERMISSIONS);
        } else {
            requestPermissions(new String[]{Manifest.permission.ANSWER_PHONE_CALLS}, CALL_PERMISSIONS);
        }
    }

    private boolean isUnlocked() {
        return getSystemService(UserManager.class).isUserUnlocked()
                && !getSystemService(KeyguardManager.class).isDeviceLocked();
    }

    private boolean hasBluetoothPermission() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean needsOfficialRestore() {
        var ownership = LocalPrefs.open(this, "ownership");
        return ownership.getBoolean("ownsDisable", false) || ownership.getBoolean("transitionPending", false);
    }

    // ---- controls ----

    private void updateControls() {
        boolean registered = new BandStateRepository(this).isRegistered();
        SetupProgress progress = readProgress();
        if (setupCard != null) {
            setShown(setupCard, progress.showChecklist());
            if (progress.showChecklist()) bindChecklist(progress);
        }
        if (homePrimary != null) {
            homePrimary.setText(progress.primaryLabel());
            homePrimary.setEnabled(!changingOwnership && !probingRoot);
        }
        if (openKsu != null) {
            openKsu.setVisibility(rootGranted ? View.GONE : View.VISIBLE);
            openKsu.setEnabled(!probingRoot);
        }
        boolean officialPaused = needsOfficialRestore();
        boolean manage = rootGranted && (registered || officialPaused);
        manageHeading.setVisibility(manage ? View.VISIBLE : View.GONE);
        setShown(manageCard, manage);
        remove.setVisibility(registered ? View.VISIBLE : View.GONE);
        remove.setEnabled(!changingOwnership);
        restore.setVisibility(officialPaused ? View.VISIBLE : View.GONE);
        restore.setEnabled(!changingOwnership);
        advancedRestore.setEnabled(!changingOwnership && rootGranted);
    }

    private SetupProgress readProgress() {
        boolean registered = new BandStateRepository(this).isRegistered();
        boolean hasBinding = false;
        String model = "";
        try {
            JSONObject binding = new BindingStore(this).read();
            hasBinding = binding != null;
            if (binding != null) model = binding.optString("model", "");
        } catch (Exception ignored) { }
        boolean hasProfile = hasBinding && TransportObservation.supportsLive(
                TransportObservation.liveProfile(this, model), model);
        boolean nativeOwned = false;
        try {
            nativeOwned = new OwnershipController(this).nativeReady();
        } catch (RuntimeException ignored) { }
        boolean accountConfirmed = false;
        try {
            accountConfirmed = new HealthRecordStore(this).confirmedAccountHash() != null;
        } catch (Exception ignored) { }
        return new SetupProgress(rootGranted, hasBinding, hasProfile, registered, nativeOwned, accountConfirmed);
    }

    private void bindChecklist(SetupProgress progress) {
        setupTitle.setText("首次设置 · 第 " + progress.visibleIndex() + "/3 步");
        int current = progress.visibleIndex();
        screen.setStep(stepRoot, 1, "授权 Root", stepState(1, current));
        screen.setStep(stepImport, 2, "导入绑定", stepState(2, current));
        screen.setStep(stepAdd, 3, "添加到健康", stepState(3, current));
        String hint = progress.primaryHint();
        primaryHint.setText(hint);
        primaryHint.setVisibility(hint.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static StepState stepState(int index, int current) {
        if (index < current) return StepState.DONE;
        return index == current ? StepState.CURRENT : StepState.LATER;
    }

    private void runHomePrimary() {
        if (changingOwnership || probingRoot) return;
        switch (readProgress().current()) {
            case ROOT -> requestRoot();
            case IMPORT -> startBindingImport();
            case PROFILE -> startProfileCapture();
            case ADD -> requestNative(Change.ADD);
            case DONE -> requestNative(Change.RECONNECT);
        }
    }

    // ---- status card ----

    private void refreshDevice() {
        if (isDestroyed() || worker.isShutdown()) return;
        updateControls();
        refreshNotices();
        if (!isUnlocked()) {
            deviceName.setText("小米手环");
            screen.setChip(statusChip, "解锁后显示", Tone.NEUTRAL);
            setOptionalCaption(connectionHint, "解锁手机后显示手环信息。");
            setDeviceLogHint("解锁后查看设备日志");
            metricsLine.setVisibility(View.GONE);
            healthLine.setVisibility(View.GONE);
            return;
        }
        var state = LocalPrefs.open(this, "band-state");
        if (new BandStateRepository(this).isRegistered()) {
            deviceName.setText(safeIdentity(
                    BandCatalog.displayName(state.getString("modelId", ""), state.getString("name", "")),
                    "已登记手环"));
            boolean connected = state.getBoolean("connected", false);
            if (connected) {
                screen.setChip(statusChip, "已连接", Tone.POSITIVE);
                setOptionalCaption(connectionHint, "");
            } else {
                screen.setChip(statusChip, "等待连接", Tone.NEUTRAL);
                setOptionalCaption(connectionHint, Copy.offlineReason(liveStatus()));
            }
            setBattery(state.getInt("battery", -1));
            // The sync time moved to the pipeline block: two "last synced at" stamps on one page,
            // one of them unlabelled, is how a page starts contradicting itself.
            setOptionalCaption(metricsLine, "");
            setOptionalCaption(healthLine, healthExtras());
            refreshToday(false);
            return;
        }
        deviceName.setText("小米手环");
        setBattery(-1);
        screen.setChip(statusChip, "待添加", Tone.NEUTRAL);
        setOptionalCaption(connectionHint, "");
        healthLine.setVisibility(View.GONE);
        try {
            JSONObject binding = new BindingStore(this).read();
            setOptionalCaption(metricsLine, binding == null
                    ? "导入绑定后即可添加到健康。" : "绑定已导入，点「添加到健康」即可连接。");
        } catch (Exception unavailable) {
            setOptionalCaption(metricsLine, "解锁手机后显示绑定信息，已有数据都在。");
        }
    }

    private void setBattery(int percent) {
        boolean known = percent >= 0 && percent <= 100;
        battery.setVisibility(known ? View.VISIBLE : View.GONE);
        battery.setText(known ? percent + "%" : "");
        if (known && percent < 20) {
            screen.setTone(battery, Tone.ATTENTION);
        } else {
            screen.muted(battery);
        }
    }

    // ---- today's numbers ----

    /**
     * Reads the numbers off the main thread and hands them back once.
     *
     * <p>Throttled because this is called from the device-card observer, which fires on every band
     * update — a sync round produces several. Without the throttle each one would open the store
     * and re-render four views that changed by nothing.
     */
    private void refreshToday(boolean force) {
        if (todayGrid == null || todayLoading) return;
        long since = System.currentTimeMillis() - todayReadAtMs;
        if (!force && since < TODAY_MIN_INTERVAL_MS) {
            // A sync round writes band-state several times inside a second and the last write is
            // the one carrying the final total — so the update worth showing is exactly the one
            // this window drops. Nothing else fires afterwards, so the card used to keep the older
            // number until the next resume (measured 2026-11-26: card 5878 as of 19:01 while the
            // state file already held 6564 as of 20:47). Re-read once the window has passed.
            main.removeCallbacks(todayTrailing);
            main.postDelayed(todayTrailing, TODAY_MIN_INTERVAL_MS - since);
            return;
        }
        main.removeCallbacks(todayTrailing);
        todayLoading = true;
        worker.execute(() -> {
            TodaySummary summary = TodaySummary.read(this);
            java.util.List<MiHealthJournal.Entry> journal = MiHealthJournal.read(this, 1);
            main.post(() -> {
                todayLoading = false;
                if (isDestroyed()) return;
                todayReadAtMs = System.currentTimeMillis();
                renderToday(summary, journal.isEmpty() ? null : journal.get(0));
            });
        });
    }

    private void renderToday(TodaySummary today, MiHealthJournal.Entry last) {
        if (todayGrid == null) return;
        boolean registered = today.registered;
        todayHeading.setVisibility(registered ? View.VISIBLE : View.GONE);
        pipelineHeading.setVisibility(registered ? View.VISIBLE : View.GONE);
        setShown(todayCard, registered);
        setShown(pipelineCard, registered);
        if (!registered) return;

        long now = System.currentTimeMillis();
        boolean fromToday = today.stepsAtMs >= TodaySummary.dayStartMs();

        // Steps: the value the band reports for the day, which is the number its own screen shows.
        String stepsValue = today.steps < 0 ? ""
                : today.steps >= 10_000
                        ? String.format(java.util.Locale.CHINA, "%.1f万", today.steps / 10_000f)
                        : String.valueOf(today.steps);
        String stepsNote = !fromToday || today.stepsAtMs <= 0
                ? "今天的数待同步" : "截至 " + clock(today.stepsAtMs);
        boolean stale = fromToday && now - today.stepsAtMs > 3 * 3600_000L;
        todayGrid.set(0, "步数", new String[]{stepsValue}, "步", stale ? stepsNote + " · 已过时" : stepsNote);
        todayGrid.warnNote(0, stale);

        todayGrid.set(1, "心率",
                new String[]{today.heartRate > 0 ? String.valueOf(today.heartRate) : ""}, "bpm",
                today.heartRateAtMs > 0 ? clock(today.heartRateAtMs) : "待采集");

        // Sleep is written as text, so its width depends on the font: "9时25分" is the readable form
        // and the grid falls back to "9h25m" only when that one does not fit. Picking by measurement
        // beats a font-scale threshold — the threshold is a guess about a font that may change.
        String[] sleepValues = today.sleepMinutes > 0
                ? new String[]{wordedSleep(today.sleepMinutes), compactSleep(today.sleepMinutes)}
                : new String[]{""};
        todayGrid.set(2, "昨夜睡眠", sleepValues, "",
                today.sleepEndMs > 0
                        ? clock(today.sleepStartMs) + " – " + clock(today.sleepEndMs)
                        : "昨夜无记录");

        // Vitals need a confirmed OPPO Health account; sleep does not. Saying which is missing is the
        // whole point of the note — "—" on its own sends the user looking for a hardware fault.
        String oxygenNote;
        if (!today.vitalsReadable) {
            oxygenNote = "需确认账号";
        } else {
            oxygenNote = today.spo2 > 0 ? clock(today.spo2AtMs) : "待采集";
        }
        todayGrid.set(3, "血氧",
                new String[]{today.spo2 > 0 && today.vitalsReadable ? String.valueOf(today.spo2) : ""},
                "%", oxygenNote);

        boolean empty = today.steps <= 0 && today.heartRate == 0 && today.sleepMinutes == 0
                && today.spo2 == 0;
        todayNote.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) todayNote.setText("今天的数据待拉取，点「立即同步」跑一次手环记录。");

        renderPipeline(last);
    }

    /**
     * The two hand-offs, as they stand. "待补写" is shown when the band was read after Mi Health was
     * last written to: the automatic path only fires with the screen off, so this state is normal
     * and silent — and a bridge that stays silent about it looks like it stopped working.
     */
    private void renderPipeline(MiHealthJournal.Entry last) {
        long fetchedAt = LocalPrefs.open(this, "band-state").getLong("lastSyncAtMs", 0);
        long writtenAt = MiHealthSync.lastAtMs(this);
        pipeLines.title.setText(fetchedAt > 0 ? "手环拉取 " + clock(fetchedAt) : "手环拉取");

        String outcome;
        if (last == null) {
            outcome = "待补写";
        } else if (last.inserted() > 0) {
            outcome = last.inserted() + " 条";
        } else {
            outcome = MiHealthLogText.status(last);
        }
        String when = writtenAt > 0 ? stamp(writtenAt) : "";
        boolean pending = fetchedAt > 0 && fetchedAt - Math.max(writtenAt, 0) > 10 * 60_000L;
        pipeLines.caption.setText("小米健康补写 " + when + (when.isEmpty() ? "" : " · ") + outcome
                + (pending ? " · 待补写" : ""));
    }

    private float fontScale() {
        return getResources().getConfiguration().fontScale;
    }

    private String clock(long atMs) {
        return android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(atMs));
    }

    /** "今天 11:22" / "昨天 23:10" / "11 月 23 日 23:10" — the date only when the day differs. */
    private String stamp(long atMs) {
        long todayStart = TodaySummary.dayStartMs();
        if (atMs >= todayStart) return "今天 " + clock(atMs);
        if (atMs >= todayStart - 86_400_000L) return "昨天 " + clock(atMs);
        java.time.LocalDate day = java.time.Instant.ofEpochMilli(atMs)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        return day.getMonthValue() + " 月 " + day.getDayOfMonth() + " 日 " + clock(atMs);
    }

    private static String wordedSleep(int minutes) {
        int hours = minutes / 60;
        int rest = minutes % 60;
        return rest == 0 ? hours + "时" : hours + "时" + rest + "分";
    }

    private static String compactSleep(int minutes) {
        int hours = minutes / 60;
        int rest = minutes % 60;
        return rest == 0 ? hours + "h" : hours + "h" + rest + "m";
    }

    private String liveStatus() {
        java.io.File file = new java.io.File(getNoBackupFilesDir(), "live-status.txt");
        if (file.isFile() && file.length() > 0 && file.length() < 256) {
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                byte[] bytes = new byte[(int) file.length()];
                int n = in.read(bytes);
                if (n > 0) return new String(bytes, 0, n, java.nio.charset.StandardCharsets.UTF_8).trim();
            } catch (Exception ignored) { }
        }
        return "";
    }

    private String healthExtras() {
        var live = getSharedPreferences("live-service", MODE_PRIVATE);
        String host = Copy.healthHost(live.getString("healthHostStatus", ""));
        String collection = Copy.healthCollection(live.getString("healthCollectionStatus", ""));
        if (collection.isEmpty()) return host;
        return host.isEmpty() ? collection : host + "\n" + collection;
    }

    private static void setOptionalCaption(TextView view, String value) {
        boolean show = value != null && !value.isBlank();
        view.setVisibility(show ? View.VISIBLE : View.GONE);
        view.setText(show ? value : "");
    }

    private void setDeviceLogHint(String value) {
        if (deviceLogHint == null) return;
        deviceLogHint.setText(value);
        deviceLogHint.setVisibility(View.VISIBLE);
    }

    private static String safeIdentity(String value, String fallback) {
        String clean = value == null ? "" : value.replaceAll("[\\p{Cntrl}\\p{Cf}]", "").trim();
        return clean.isEmpty() || clean.length() > 80 ? fallback : clean;
    }

    // ---- credential provider ----

    private void action(String method, Bundle arguments) {
        if (isDestroyed() || worker.isShutdown()) return;
        worker.execute(() -> {
            try {
                Bundle response = getContentResolver().call(CredentialProvider.URI, method, null, arguments);
                main.post(() -> {
                    if (isDestroyed()) return;
                    show(response);
                    if ("openWindow".equals(method) || "openDiagnostics".equals(method) || "openCapture".equals(method)) {
                        main.removeCallbacks(expireRefresh);
                        main.postDelayed(expireRefresh, 120_100);
                    }
                });
            } catch (RuntimeException rejected) {
                main.post(() -> {
                    if (!isDestroyed()) status.setText("解锁手机、核对所选设备后再点一次。");
                });
            }
        });
    }

    private void refresh() { action("status", null); }

    private void show(Bundle response) {
        if (response == null) {
            status.setText("导入服务正在启动，稍后再点一次。");
            return;
        }
        String code = response.getString("status", "CREDENTIAL_STORAGE_FAILED");
        String text = Copy.importResult(code, response.getString("model", ""),
                response.getString("productId", ""), response.getString("firmware", ""));
        String observation = response.getString("transportObservation", "");
        if (!observation.isBlank()) text += "\n连接参数（仅观察）：\n" + observation;
        if (response.getBoolean("diagnosticOpen", false)) text += "\n连接参数采集窗口开启中。";
        if (response.getBoolean("captureOpen", false)) text += "\n协议消息采集窗口开启中。";
        String captureFile = response.getString("captureFile", "");
        if (!captureFile.isBlank()) text += "\n采集记录：" + response.getInt("captureRecords")
                + "，字节：" + response.getLong("captureBytes") + "\n文件：" + captureFile;
        String captureError = response.getString("captureError", "");
        if (!captureError.isBlank()) text += "\n采集中断：" + captureError;
        status.setText(text);
        if ("IMPORT_WINDOW_OPEN".equals(code) || "DIAGNOSTIC_WINDOW_OPEN".equals(code)) {
            ownershipStatus.setText(text);
            if (launchHostAfterOpen) {
                launchHostAfterOpen = false;
                if (hostSupported) launchMiHealth();
            }
        }
    }

    private void launchMiHealth() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(HostIdentity.MI_PACKAGE);
        if (launch == null) {
            String text = "在桌面打开小米运动健康，确认它已安装并启用。";
            status.setText(text);
            ownershipStatus.setText(text);
            return;
        }
        startActivity(launch);
    }

    // ---- bonded devices (advanced) ----

    private void refreshBonded() {
        if (bonded == null) return;
        bonded.removeAllViews();
        if (!hasBluetoothPermission()) {
            screen.caption(bonded, "授权附近设备后，这里会列出已配对设备。");
            return;
        }
        BluetoothAdapter adapter = getSystemService(BluetoothManager.class).getAdapter();
        if (adapter == null) {
            screen.caption(bonded, "这台手机需要蓝牙才能连接手环。");
            return;
        }
        java.util.Set<BluetoothDevice> devices;
        try {
            devices = adapter.getBondedDevices();
        } catch (SecurityException denied) {
            screen.caption(bonded, "授权附近设备后，这里会列出已配对设备。");
            return;
        }
        if (devices == null || devices.isEmpty()) {
            screen.caption(bonded, "在系统蓝牙设置中配对手环后，它会出现在这里。");
            return;
        }
        screen.caption(bonded, "选择已配对设备");
        java.util.List<BluetoothDevice> listed = sortedBonded(devices);
        preferXiaomiBand(listed);
        for (BluetoothDevice device : listed) {
            String mac = device.getAddress() == null ? "" : device.getAddress().toUpperCase(java.util.Locale.ROOT);
            String name = BandCatalog.displayName(null, device.getName());
            boolean chosen = !mac.isBlank() && mac.equals(selectedAddress);
            Button choice = screen.outlined(bonded, (chosen ? "✓ " : "") + name, () -> {
                selectedAddress = mac;
                refreshBonded();
            });
            choice.setEnabled(!chosen);
        }
    }

    private static java.util.List<BluetoothDevice> sortedBonded(java.util.Set<BluetoothDevice> devices) {
        ArrayList<BluetoothDevice> listed = new ArrayList<>(devices);
        listed.sort(Comparator.comparing((BluetoothDevice device) -> !BandCatalog.looksLikeBand(device.getName()))
                .thenComparing(device -> BandCatalog.displayName(null, device.getName()),
                        String.CASE_INSENSITIVE_ORDER));
        return listed;
    }

    private void preferXiaomiBand(java.util.List<BluetoothDevice> devices) {
        if (!selectedAddress.isBlank()) return;
        String onlyBand = null;
        int bands = 0;
        for (BluetoothDevice device : devices) {
            if (!BandCatalog.looksLikeBand(device.getName())) continue;
            String mac = device.getAddress();
            if (mac == null || mac.isBlank()) continue;
            bands++;
            onlyBand = mac.toUpperCase(java.util.Locale.ROOT);
        }
        if (bands == 1) {
            selectedAddress = onlyBand;
            return;
        }
        if (devices.size() != 1) return;
        String only = devices.get(0).getAddress();
        if (only != null) selectedAddress = only.toUpperCase(java.util.Locale.ROOT);
    }

    // ---- database import ----

    private void startBindingImport() {
        if (changingOwnership) return;
        if (!rootGranted) {
            requestRoot();
            return;
        }
        if (!isUnlocked()) {
            ownershipStatus.setText("解锁手机后再导入绑定。");
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("导入绑定？")
                .setMessage("用 root 读取小米运动健康本地数据库中的手环密钥。全程在本机完成，密钥保存在本应用的私有目录里。")
                .setNegativeButton("取消", null)
                .setPositiveButton("读取", (dialog, which) -> importFromDatabase(selectedAddress))
                .show();
    }

    private void importFromDatabase(String address) {
        if (worker.isShutdown()) return;
        databaseImportNote = "";
        ownershipStatus.setText("正在读取本地数据库…");
        worker.execute(() -> {
            Bundle selected = new Bundle();
            if (address != null && !address.isBlank()) selected.putString("address", address);
            Bundle response;
            try {
                response = getContentResolver().call(CredentialProvider.URI, "importFromDatabase", null, selected);
            } catch (RuntimeException rejected) {
                response = null;
            }
            Bundle shown = response;
            main.post(() -> {
                if (isDestroyed()) return;
                if (shown != null && "MULTIPLE_BANDS".equals(shown.getString("status"))) {
                    chooseDatabaseBand(shown.getStringArray("choices"));
                    return;
                }
                show(shown);
                databaseImportNote = status.getText().toString();
                ownershipStatus.setText(databaseImportNote);
                updateControls();
                refreshDevice();
            });
        });
    }

    private void chooseDatabaseBand(String[] choices) {
        if (choices == null || choices.length == 0) {
            ownershipStatus.setText(Copy.importResult("NO_BAND_KEY", "", "", ""));
            return;
        }
        String[] labels = new String[choices.length];
        String[] addresses = new String[choices.length];
        for (int index = 0; index < choices.length; index++) {
            String[] parts = choices[index].split("\t", 3);
            addresses[index] = parts.length > 0 ? parts[0] : "";
            String model = parts.length > 1 ? parts[1] : "";
            String name = parts.length > 2 ? parts[2] : "";
            labels[index] = BandCatalog.displayName(model, name);
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("选择要导入的手环")
                .setItems(labels, (dialog, which) -> importFromDatabase(addresses[which]))
                .show();
    }

    // ---- connection profile capture (debug) ----

    private void startProfileCapture() {
        if (changingOwnership) return;
        if (!rootGranted) {
            requestRoot();
            return;
        }
        if (!isUnlocked()) {
            ownershipStatus.setText("解锁手机后再采集连接参数。");
            return;
        }
        if (!hostSupported) {
            ownershipStatus.setText("安装小米运动健康并配对手环后，即可采集连接参数。");
            return;
        }
        if (needsOfficialRestore()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("先恢复官方管理")
                    .setMessage("采集连接参数时要用到小米运动健康。先恢复官方管理，随后会打开采集窗口。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("恢复", (dialog, which) -> {
                        profileAfterRestore = true;
                        changeOwnership(Change.RESTORE);
                    })
                    .show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("采集连接参数？")
                .setMessage("将开启 120 秒采集窗口，随后打开小米运动健康。点开这只手环，让它重新连上。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始采集", (dialog, which) -> startProfileWindow())
                .show();
    }

    private void startProfileWindow() {
        launchHostAfterOpen = true;
        action("openDiagnostics", null);
    }
}

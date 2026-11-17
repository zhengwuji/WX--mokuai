# WeChat Hook & Enhancement Module (WeXposed / WxEntry)

This repository contains the complete source code and build system for an advanced Xposed / LSPosed enhancement module targeting **Tencent WeChat (Android)**.

The project is structured and documented below in English so that any developer or AI assistant can quickly understand its architecture, internal design, feature implementations, and exact maintenance workflows (adding features, fixing bugs, and adapting to new WeChat versions).

---

## 1. Project Overview & Environment

- **Target App**: Tencent WeChat (`com.tencent.mm`)
- **Primary Tested Version**: WeChat `8.0.80` (VersionCode: `3220`, Build: `0x28005032`)
- **Hook Framework**: Xposed / LSPosed (supports both Legacy `IXposedHookLoadPackage` and Modern `LibXposed`)
- **License / Paywall Status**: **Completely removed**. All features are unlocked in permanent free VIP mode. All remote activation, token verification, and license checks have been eliminated.
- **Root Directory Layout**:
  - `核心源码/`: The complete Android Studio / Gradle project.
    - `app/`: Main module application (contains hook logic, UI, and Dex analysis engine).
    - `shared/`: Shared interfaces, data structures, and hook wrappers between legacy and modern runtimes.
  - `WeXposedHook-release.apk`: Pre-compiled release APK ready for device installation.

---

## 2. Directory & Architecture Map

### 2.1 Core Source Trees
All source code lives under `核心源码/app/src/`:

```text
核心源码/app/src/
├── legacy/java/io/github/shadowbaby/wechatxposed/hook/
│   ├── XposedInit.java              <-- Xposed entry point (IXposedHookLoadPackage)
│   └── framework/LegacyFramework.java
├── modern/java/                     <-- LibXposed entry point and modern bindings
└── main/java/
    ├── com/zafiro/wxentry/
    │   ├── Main.java                <-- Module wrapper
    │   └── kernel/                  <-- CORE LOGIC & HOOK ENGINE
    │       ├── Kernel.java          <-- Master orchestrator, hook dispatcher, & menu controller
    │       ├── KernelLog.java       <-- Centralized logger with 512KB rotation & debug gating
    │       ├── MessageInsertBus.java <-- Single unified DB insert & update event bus
    │       ├── DexResolver.java     <-- Version-cached dynamic signature resolver + diagnostics
    │       ├── FeatureMenuUi.java   <-- Floating modal dialog UI (custom styled dark/light)
    │       ├── SettingsUi.java      <-- Preference layout utilities and UI metrics
    │       ├── MenuPalette.java     <-- Dynamic color theming matching WeChat dark/light mode
    │       │
    │       │-- [Feature Runtimes]
    │       ├── UnreadReadShortcut.java   <-- One-click mark all chats as read
    │       ├── GameEmojiRuntime.java     <-- Controlled Dice & Mora/RPS (+ ask-before-send & per-chat rules)
    │       ├── GameEmojiStore.java       <-- Persistence store for dice/mora points
    │       ├── GameEmojiUi.java          <-- UI picker for dice/mora custom values
    │       ├── GameEmojiData.java        <-- Data model for game emoji hooks (v2)
    │       ├── TransferRuntime.java      <-- Auto-receive WeChat transfers (RemittanceDetailUI)
    │       ├── RecallArchiveRuntime.java <-- Persistent anti-recall archive, gallery export & alerts
    │       ├── AutoReplyRuntime.java     <-- Keyword alerts, rule auto-reply & auto-accept friend
    │       ├── GroupGuardRuntime.java    <-- Group member change tracker, pat blocker & @all
    │       ├── BackupManager.java        <-- Settings backup/restore JSON & Binder IPC bridge
    │       ├── OriginalImageRuntime.java <-- Auto HD/original photo download & default raw send
    │       ├── WechatAppearance.java     <-- Tablet mode & dual-pane layout injection
    │       ├── ScanCameraRuntime.java    <-- Virtual camera simulation for scanning QR/barcodes
    │       ├── ForegroundRedPacket.java  <-- Auto lucky money / red packet receiver
    │       ├── RedPacketMessage.java     <-- Red packet parser & payload analysis
    │       ├── RedPacketUi.java          <-- Red packet settings UI
    │       ├── MediaRecallRuntime.java   <-- Anti-recall engine for chat messages
    │       ├── RecallTipRuntime.java     <-- Inline prompt injection for recalled messages
    │       ├── RecallMarkStore.java      <-- Storage for recalled message flags
    │       ├── RecallMediaCache.java     <-- Cache for recalled voice, images, and videos
    │       ├── VoiceForwardRuntime.java  <-- High-fidelity voice message forwarding
    │       ├── VoiceTextRuntime.java     <-- Automatic speech-to-text conversion
    │       ├── MessageRepeatRuntime.java <-- Message repeat / echo button in chat
    │       ├── MomentsConfig.java        <-- Moments (SNS) configuration
    │       ├── MomentsSettings.java      <-- Moments settings UI
    │       ├── SnsLocalStore.java        <-- Moments fake likes, comments, and anti-revoke
    │       ├── SportStepRuntime.java     <-- WeChat Sport step counter customizer
    │       ├── PrivacyEnhanceRuntime.java <-- Block typing, wallet balance mask & URL check bypass
    │       ├── UiCleanRuntime.java       <-- Discovery tab item cleaner & badge suppressor
    │       ├── VoiceSpeedRuntime.java    <-- Voice playback speed (1.25x-2.0x) & sticker export
    │       ├── CardFilterRuntime.java    <-- AppMsg/miniprogram card filter & collapse
    │       ├── ForwardLimitRuntime.java  <-- Unlock 9-recipient limit in SelectConversationUI
    │       ├── ScheduledMessageRuntime.java <-- One-shot & daily recurring message queue
    │       ├── GhostFriendRuntime.java   <-- Zero-risk local zombie/deleted friend scanner
    │       ├── ChatExportRuntime.java    <-- Export chat history to Download/WechatXposed/
    │       ├── MomentsAdvancedRuntime.java <-- Moments anti-fold & direct photo/video export
    │       ├── FinderDownloadRuntime.java <-- WeChat Channels (Finder) raw MP4 downloader
    │       └── MediaExportHelper.java    <-- Scoped Storage / MediaStore export utility
    │
    └── io/github/shadowbaby/wechatxposed/hook/
        ├── deploy/                  <-- Module self-check and asset unpacker
        ├── framework/               <-- Hook framework bridge
        ├── license/                 <-- Dummy / stubbed activation interfaces (clean)
        └── ui/MainActivity.java     <-- Standalone launcher settings activity
```

---

## 3. Core Principles & How the Engine Works

### 3.1 Hook Injection Lifecycle
1. **LSPosed Ingestion**: When `com.tencent.mm` starts, LSPosed loads `io.github.shadowbaby.wechatxposed.hook.XposedInit`.
2. **Dual-Hook Bootstrap**:
   - Hooks `android.app.Application.onCreate` to detect `com.tencent.mm` initialization.
   - Hooks `com.tencent.mm.ui.LauncherUI.onCreate` as a secondary safety hook.
   - Calls `com.zafiro.wxentry.kernel.Kernel.boot(appClassLoader, contextOrActivity)`.
3. **Execution Guard & Version Gate**:
   - `Kernel.wxVersionOk(Context)` validates that the host WeChat matches the supported version (`WX_OK_CODE = 3220`, `WX_OK_NAME = "8.0.80"`).
   - *Note*: CRC verification has been disabled to prevent false-alarm exits on minor revision builds.
4. **DexIdx Asynchronous Pre-warming**:
   - `Kernel.startDexPrewarm()` spawns a background worker thread to parse the WeChat APK's `classes*.dex`.
   - Instead of hardcoding obfuscated class names (which break with every release), the **`DexIdx` engine** scans bytecode instructions, string constant pools, and method signatures to dynamically locate the obfuscated class names for database managers, storage components, and network dispatchers.
   - Discovered class indices are cached on disk at `/data/data/com.tencent.mm/files/WechatXposed/dex.idx`.

### 3.2 UI Interaction Hooks
- **Entry Method 1 (Plus Button Long-Press)**:
  `Kernel.attachPlusLongClick(Activity)` locates the `+` action button in the top-right corner of WeChat's main `LauncherUI` and attaches an `OnLongClickListener`. Long-pressing opens `FeatureMenuUi`.
- **Entry Method 2 (Launcher Broadcast Intent)**:
  A broadcast receiver listens for `ACTION_OPEN_FEATURE_MENU` (`com.zafiro.wxentry.action.OPEN_FEATURE_MENU`) allowing programmatic invocation via `adb shell am broadcast -a com.zafiro.wxentry.action.OPEN_FEATURE_MENU`.
- **Entry Method 3 (Chat Unread Shortcut)**:
  `UnreadReadShortcut` attaches an `OnLongClickListener` to the bottom navigation "Chats" tab. Long-pressing triggers the one-click mark-all-read dialog.

---

## 4. Key Feature Implementation Details

### 4.1 One-Click Mark All Read (`UnreadReadShortcut.java`)
- **Mechanism**: Long-pressing the "Chats" (微信) bottom navigation tab queries the local conversation database via WeChat's storage engine (`com.tencent.mm.storage.p` / `by` table).
- Updates all unread counts (`unReadCount = 0`, `marked = 0`) and triggers the conversation list adapter notification so the badge counters vanish instantly.

### 4.2 Dice & Mora (Finger-Guessing) Controller (`GameEmojiRuntime.java`)
- **Mechanism**: WeChat emojis for Dice (1~6) and Finger-Guessing (Rock/Paper/Scissors) determine their outcome through a pseudo-random integer generator method inside the emoji calculation pipeline.
- `GameEmojiRuntime` hooks this calculation method. When enabled, it overrides the generated index with the user's desired value configured in `GameEmojiStore` (1 to 6 for dice, 1 to 3 for mora).
- **Control UI**: Long-pressing the dice or mora icon in the emoji keyboard panel opens `GameEmojiUi` to choose the fixed point.

### 4.3 High-Res / Original Image Automation (`OriginalImageRuntime.java`)
- **Auto Download**: Hooks the media download manager. When an image message is received, it intercepts the preview thumbnail request and requests the full raw CDN stream (`RAW_HD`).
- **Default Send Raw**: Hooks the image gallery / album picker Activity. When selecting photos to send, automatically sets the "Full Image / 原图" checkbox state to `true`.

### 4.4 Tablet Multi-Window Mode (`WechatAppearance.java`)
- **Mechanism**: Modifies WeChat's runtime device type detection (`Util.isTablet(Context)` and `Configuration.smallestScreenWidthDp`).
- Triggers WeChat's dual-pane tablet UI (chat list on the left, active chat on the right) and allows simultaneous login alongside a primary mobile phone.

### 4.5 Anti-Recall Engine (`MediaRecallRuntime.java`, `RecallTipRuntime.java`)
- **Mechanism**: Hooks WeChat's SQLite message processor where revocation commands (`type = 10002`, `sysmsg` with `revokemsg`) are handled.
- Prevents message deletion, keeps the original message record, and injects an inline grey system tip: `"Friend attempted to revoke: [Original Content]"`.
- Voice, video, and image files are retained by `RecallMediaCache` to avoid cache eviction.

---

## 5. Guide for Developers & Future AIs

### 5.1 How to Add a New Feature
Follow this standardized 4-step workflow:

1. **Create the Runtime Class**:
   Create a new file `NewFeatureRuntime.java` in package `com.zafiro.wxentry.kernel`.
   Implement static methods:
   ```java
   public final class NewFeatureRuntime {
       private static volatile boolean sEnabled = false;
       public static void hook(ClassLoader cl) {
           // Find target class via reflection or DexIdx
           // XposedHelpers.findAndHookMethod(...)
       }
       public static void setEnabled(boolean enabled) { sEnabled = enabled; }
       public static boolean isEnabled() { return sEnabled; }
   }
   ```
2. **Add Menu Enumeration in `Kernel.java`**:
   - Add a new action in `Kernel.MenuAction`:
     ```java
     enum MenuAction { ..., MY_NEW_FEATURE }
     ```
   - Add the entry into `Kernel.menuPageEntries(MenuCategory category)` under the desired tab (e.g. `TOOLS` or `CHAT`).
   - Add label and click handler in `Kernel.refreshMenuLabel` and `Kernel.performMenuAction`.
3. **Register Boot Hook in `Kernel.java`**:
   In `Kernel.boot(ClassLoader cl, Object actObj)`:
   ```java
   try { NewFeatureRuntime.hook(cl); } catch (Throwable t) { T("my feature hook err: " + t); }
   ```
4. **Compile & Verify**:
   Run the Gradle assemble task and test on the target device.

---

### 5.2 How to Adapt to a Newer WeChat Version (e.g. 8.0.81+)
When WeChat releases an update, perform the following steps:

1. **Update Version Gate in `Kernel.java`**:
   Locate `wxVersionOk()` in `核心源码/app/src/main/java/com/zafiro/wxentry/kernel/Kernel.java`:
   ```java
   static final int WX_OK_CODE = 3220; // Update to new version code (e.g., 3240)
   static final String WX_OK_NAME = "8.0.80"; // Update to new version name (e.g., "8.0.81")
   ```
   *Alternative*: Change the check to `vcode >= 3220` if backwards compatibility is desired.

2. **Allow DexIdx Auto-Scan**:
   The module will automatically detect the new APK file signature, discard the outdated `dex.idx` cache, and re-scan the new DEX files. This automatically recovers 85%+ of obfuscated classes.

3. **Check Diagnostic Logs for Broken Signatures**:
   Inspect the debug trace log on the device:
   ```bash
   adb shell cat /data/data/com.tencent.mm/files/WechatXposed/trace.log
   # or filter logcat:
   adb logcat -s WXT
   ```
   If any hook failed due to method signature changes (e.g., an extra parameter added by WeChat), locate the specific runtime file and update the argument list in `findAndHookMethod`.

---

## 6. Build Instructions

### Prerequisites
- JDK 17
- Android SDK (compileSdk 37)
- Gradle (use the bundled wrapper `gradlew.bat` in `核心源码`)

### Build Commands
Open a terminal in `核心源码/`:

```powershell
# Build release APK for Legacy Xposed (LSPosed) with wx3.0 pack:
.\gradlew.bat assembleLegacyWx30Release

# Generated output APK:
# app/build/outputs/apk/legacyWx30/release/WeXposedHook-legacy-wx3.0-v2.1.apk
```

### Install to Connected Device
```powershell
adb install -r -d WeXposedHook-release.apk
adb shell am force-stop com.tencent.mm
adb shell am start -n com.tencent.mm/.ui.LauncherUI
```

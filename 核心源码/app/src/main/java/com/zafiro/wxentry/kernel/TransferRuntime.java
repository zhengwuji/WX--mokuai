package com.zafiro.wxentry.kernel;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automatic WeChat transfer confirmation for incoming transfer bubbles (`paysubtype=1`).
 * Reuses ReceiptConfig.Feature.TRANSFER rules (personal/group lists, delay, memo keywords).
 */
final class TransferRuntime {
    private static final String EXTRA_AUTO = "wxentry_auto_receive";
    private static final String REMITTANCE_UI = "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<String> SEEN = Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> CLICKED_UI = Collections.synchronizedSet(new HashSet<>());
    private static volatile boolean hooked;

    private TransferRuntime() {}

    static boolean ready() {
        return hooked;
    }

    static synchronized void start(ClassLoader cl) {
        if (hooked || cl == null) return;
        MessageInsertBus.addInsertListener((db, table, cv, rowId) -> {
            if ("message".equals(table)) onMessageInserted(db, cv);
        });
        try {
            final Class<?> uiCls = Class.forName(REMITTANCE_UI, false, cl);
            XposedBridge.hookAllMethods(uiCls, "onSceneEnd", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || Kernel.MODULE_OFF) return;
                    if (!(p.thisObject instanceof Activity)) return;
                    final Activity act = (Activity) p.thisObject;
                    MAIN.postDelayed(() -> handleRemittanceUi(act, uiCls), 180L);
                }
            });
            // Backup hook on UI display methods (X7 / Y7) in case state is populated without standard onSceneEnd timing
            for (String mName : new String[]{"X7", "Y7"}) {
                try {
                    XposedBridge.hookAllMethods(uiCls, mName, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (p.hasThrowable() || Kernel.MODULE_OFF) return;
                            if (!(p.thisObject instanceof Activity)) return;
                            final Activity act = (Activity) p.thisObject;
                            MAIN.postDelayed(() -> handleRemittanceUi(act, uiCls), 220L);
                        }
                    });
                } catch (Throwable ignored) {}
            }
            hooked = true;
            Kernel.T("ARTRANSFER ready ui=" + REMITTANCE_UI);
        } catch (Throwable t) {
            Kernel.T("ARTRANSFER ui hook err " + t.getClass().getSimpleName());
        }
    }

    static boolean enabled() {
        return !Kernel.MODULE_OFF && ReceiptStore.enabled(ReceiptConfig.Feature.TRANSFER);
    }

    private static void onMessageInserted(Object db, ContentValues cv) {
        if (!enabled() || cv == null) return;
        Integer typeObj = cv.getAsInteger("type");
        Long isSend = cv.getAsLong("isSend");
        if (typeObj == null || isSend == null || isSend.longValue() != 0L) return;
        int t = typeObj.intValue();
        int low = t & 0xffff;
        if (low != 49 && t != 419430449) return;
        String content = cv.getAsString("content");
        if (content == null || (!content.contains("<wcpayinfo>") && !content.contains("transferid"))) return;

        // Freshness guard: never auto-receive historical synced transfers older than 5 minutes
        Long created = cv.getAsLong("createTime");
        if (created != null && created.longValue() > 0) {
            long sentMs = created.longValue() < 100000000000L ? created.longValue() * 1000L : created.longValue();
            long age = System.currentTimeMillis() - sentMs;
            if (age > 300_000L || age < -60_000L) return;
        }

        String paySubType = xmlTag(content, "paysubtype");
        if (!"1".equals(paySubType)) return; // 1 = transfer sent awaiting confirmation

        final String transferId = xmlTag(content, "transferid");
        final String transactionId = xmlTag(content, "transcationid");
        if (transferId.isEmpty() && transactionId.isEmpty()) return;

        final String talker = cv.getAsString("talker");
        final String memo = xmlTag(content, "pay_memo");
        final String feeDesc = xmlTag(content, "feedesc");
        final String account = Kernel.receiptAccount();
        if (!ReceiptConfig.validId(account) || !ReceiptStore.permitsMessage(ReceiptConfig.Feature.TRANSFER, account, talker, memo + " " + feeDesc)) {
            return;
        }

        String dedupKey = account + "|" + (!transferId.isEmpty() ? transferId : transactionId);
        if (!SEEN.add(dedupKey)) return;
        if (SEEN.size() > 512) SEEN.clear();

        String senderName = xmlTag(content, "sender_username");
        if (senderName.isEmpty()) senderName = extractGroupSender(talker, content);
        if (senderName.isEmpty()) senderName = talker == null ? "" : talker;
        String receiverName = xmlTag(content, "receiver_username");
        if (receiverName.isEmpty()) {
            String self = Kernel.selfWxid();
            receiverName = self == null ? "" : self;
        }
        int invalidTime = parseInt(xmlTag(content, "invalidtime"), 0);
        int effectiveDate = parseInt(xmlTag(content, "effective_date"), 3);
        Long msgIdObj = cv.getAsLong("msgId");
        long msgId = msgIdObj != null ? msgIdObj.longValue() : lookupMsgId(db, talker, cv.getAsLong("msgSvrId"));

        final String fSender = senderName;
        final String fReceiver = receiverName;
        final int fInvalid = invalidTime;
        final int fEffective = effectiveDate;
        final long fMsgId = msgId;
        long delay = ReceiptStore.delay(ReceiptConfig.Feature.TRANSFER, account);
        Kernel.T("TRANSFER detected id=" + transferId + " talker=" + talker + " fee=" + feeDesc + " delay=" + delay);

        MAIN.postDelayed(() -> {
            if (!enabled() || !ReceiptStore.permitsMessage(ReceiptConfig.Feature.TRANSFER, account, talker, memo + " " + feeDesc)) {
                return;
            }
            launchRemittanceUi(transactionId, transferId, fInvalid, fEffective, fSender, fReceiver, fMsgId);
        }, delay);
    }

    private static void launchRemittanceUi(String txId, String transferId, int invalidTime,
                                           int effectiveDate, String senderName, String receiverName, long msgId) {
        try {
            Activity current = Kernel.currentActivityNow();
            Context ctx = current != null && !current.isFinishing() && !current.isDestroyed() ? current : Kernel.APP_CTX;
            if (ctx == null) return;
            Intent intent = new Intent();
            intent.setClassName("com.tencent.mm", REMITTANCE_UI);
            intent.putExtra("transaction_id", txId);
            intent.putExtra("transfer_id", transferId);
            intent.putExtra("invalid_time", invalidTime);
            intent.putExtra("effective_date", effectiveDate);
            intent.putExtra("sender_name", senderName);
            intent.putExtra("payer_name", senderName);
            intent.putExtra("receiver_name", receiverName);
            intent.putExtra("key_msg_id", msgId);
            intent.putExtra("appmsg_type", 2000);
            intent.putExtra(EXTRA_AUTO, true);
            if (!(ctx instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(intent);
            Kernel.T("TRANSFER launch RemittanceDetailUI transferId=" + transferId);
        } catch (Throwable t) {
            KernelLog.stack("TransferRuntime.launch", t);
        }
    }

    private static void handleRemittanceUi(Activity act, Class<?> uiCls) {
        if (act == null || act.isFinishing() || act.isDestroyed() || !enabled()) return;
        try {
            Intent intent = act.getIntent();
            boolean autoOpened = intent != null && intent.getBooleanExtra(EXTRA_AUTO, false);
            String tid = intent != null ? intent.getStringExtra("transfer_id") : null;
            String key = System.identityHashCode(act) + "|" + (tid == null ? "" : tid);

            Button btn = findReceiveButton(act, uiCls);
            if (btn != null && btn.getVisibility() == View.VISIBLE && btn.isEnabled()) {
                CharSequence txt = btn.getText();
                String label = txt == null ? "" : txt.toString();
                if (label.isEmpty() || label.contains("收款") || label.contains("确认")) {
                    if (CLICKED_UI.add(key)) {
                        if (CLICKED_UI.size() > 256) CLICKED_UI.clear();
                        boolean ok = btn.performClick();
                        Kernel.T("TRANSFER auto click confirm=" + ok + " tid=" + tid);
                        if (ok) {
                            Toast.makeText(act, "已自动确认收款", Toast.LENGTH_SHORT).show();
                        }
                    }
                    return;
                }
            }

            // If opened automatically in background and already confirmed (receive button gone after confirm), auto close
            if (autoOpened && CLICKED_UI.contains(key)) {
                int status = currentRemittanceStatus(act);
                if (status == 2001) {
                    Kernel.T("TRANSFER auto-opened confirmed, finishing UI tid=" + tid);
                    MAIN.postDelayed(() -> {
                        if (!act.isFinishing() && !act.isDestroyed()) act.finish();
                    }, 350L);
                }
            }
        } catch (Throwable t) {
            KernelLog.debug("TransferRuntime ui err " + t);
        }
    }

    private static Button findReceiveButton(Activity act, Class<?> uiCls) {
        try {
            Field f = uiCls.getDeclaredField("M");
            f.setAccessible(true);
            Object v = f.get(act);
            if (v instanceof Button) return (Button) v;
        } catch (Throwable ignored) {}
        try {
            int id = act.getResources().getIdentifier("remittance_receive_btn", "id", "com.tencent.mm");
            if (id != 0) {
                View v = act.findViewById(id);
                if (v instanceof Button) return (Button) v;
            }
        } catch (Throwable ignored) {}
        return findButtonByKeyword(act.getWindow() != null ? act.getWindow().getDecorView() : null);
    }

    private static Button findButtonByKeyword(View v) {
        if (v == null) return null;
        if (v instanceof Button && v.getVisibility() == View.VISIBLE && v.isEnabled()) {
            CharSequence t = ((Button) v).getText();
            if (t != null) {
                String s = t.toString().trim();
                if (s.equals("确认收款") || s.equals("收款")) return (Button) v;
            }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                Button hit = findButtonByKeyword(vg.getChildAt(i));
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static int currentRemittanceStatus(Activity act) {
        try {
            Object h1 = XposedHelpers.getObjectField(act, "s2");
            if (h1 != null) {
                Object status = XposedHelpers.getObjectField(h1, "h");
                if (status instanceof Number) return ((Number) status).intValue();
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static long lookupMsgId(Object db, String talker, Long svrId) {
        if (db == null || svrId == null || svrId.longValue() <= 0) return 0L;
        Cursor c = null;
        try {
            c = Kernel.rawQuery(db, "select msgId from message where msgSvrId=?",
                    new String[]{String.valueOf(svrId)});
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return 0L;
    }

    private static String extractGroupSender(String talker, String content) {
        if (talker != null && talker.endsWith("@chatroom") && content != null) {
            int colon = content.indexOf(":\n");
            if (colon > 0 && colon < 128) return content.substring(0, colon).trim();
        }
        return "";
    }

    static String xmlTag(String xml, String tag) {
        if (xml == null || tag == null) return "";
        Matcher m = Pattern.compile("<" + Pattern.quote(tag) + ">(?:<!\\[CDATA\\[)?([\\s\\S]*?)(?:\\]\\]>)?</" + Pattern.quote(tag) + ">").matcher(xml);
        return m.find() ? m.group(1).trim().replace("&amp;", "&") : "";
    }

    private static int parseInt(String s, int def) {
        try {
            return (s == null || s.isEmpty()) ? def : Integer.parseInt(s);
        } catch (Throwable t) {
            return def;
        }
    }
}

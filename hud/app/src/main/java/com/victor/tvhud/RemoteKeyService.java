package com.victor.tvhud;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 【试验代码 · 在 TCL 遥控上不可用，保留作为记录】
 *
 * <p>原本的设想：walleve 停用后语音键已无用途，把它改成「呼出状态条」。
 * 服务本身能绑定（capabilities=8，可过滤按键），但 TCL 蓝牙遥控 BT_RC802A_C1 的
 * 语音键在输入层就是 {@code KEY_UNKNOWN} —— 未映射的 HID 码，Android 在到达无障碍层
 * 之前就丢弃，{@code onKeyEvent} 从未被调用。
 *
 * <p>要让它工作只能改 {@code /system/usr/keylayout}（需要 root），本项目不做。
 * 因此该服务默认不启用；状态条仍由「前台应用切换 / 亮屏 / PEEK intent」触发。
 *
 * <p>保留这段代码的原因：换一台按键映射正常的设备时，它可以直接用。
 */
public class RemoteKeyService extends AccessibilityService {

    private static final String TAG = "TVHUDF";

    /** 语音键的几种可能编码；TCL 遥控器实测是哪一个，看 logcat 的 TVHUDF 标签。 */
    private static final int[] KEYS = {
            KeyEvent.KEYCODE_VOICE_ASSIST,   // 231，多数 Android TV 遥控
            KeyEvent.KEYCODE_SEARCH,         // 84
            KeyEvent.KEYCODE_ASSIST,         // 219
    };

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            for (int k : KEYS) {
                if (code == k) {
                    peek();
                    return true;
                }
            }
            // 只记录，不改行为：方便确认 TCL 语音键的真实 keycode
            Log.i(TAG, "key " + code + " (" + KeyEvent.keyCodeToString(code) + ")");
        }
        return super.onKeyEvent(event);
    }

    private void peek() {
        try {
            Intent i = new Intent(HudService.ACTION_PEEK);
            i.setClass(this, HudService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "peek 失败: " + e.getMessage());
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不需要窗口事件
    }

    @Override
    public void onInterrupt() {
        // 不需要
    }
}

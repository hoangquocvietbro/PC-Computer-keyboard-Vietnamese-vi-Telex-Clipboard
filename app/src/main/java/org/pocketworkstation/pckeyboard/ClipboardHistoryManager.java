package org.pocketworkstation.pckeyboard;

import android.content.ClipData;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * System clipboard history for the suggestion strip.
 *
 * <p>Listens for clipboard changes, keeps the most recent clips
 * (pinned first), persists them as JSON in shared preferences, and
 * notifies a listener on the main thread when the history changes.
 */
public class ClipboardHistoryManager {

    /** One saved clip. Identity is (text, time). */
    public static final class ClipItem {
        public final String text;
        public final long time;
        public final boolean pinned;

        public ClipItem(String text, long time, boolean pinned) {
            this.text = text;
            this.time = time;
            this.pinned = pinned;
        }

        public ClipItem withPinned(boolean pinned) {
            return new ClipItem(text, time, pinned);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ClipItem)) return false;
            ClipItem other = (ClipItem) o;
            return time == other.time && TextUtils.equals(text, other.text);
        }

        @Override
        public int hashCode() {
            return (text == null ? 0 : text.hashCode()) + (int) (time ^ (time >>> 32));
        }
    }

    public interface Listener {
        void onHistoryChanged();
    }

    private static final String PREF_KEY = "clipboard_history_json";
    private static final int MAX_ITEMS = 30;
    private static final int MAX_TEXT_LENGTH = 2000;

    private final Context mAppContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final List<ClipItem> mItems = new ArrayList<ClipItem>();
    private Listener mListener;
    private boolean mCaptureEnabled = true;
    private boolean mListening;
    private android.content.ClipboardManager mClipboardService;

    private final android.content.ClipboardManager.OnPrimaryClipChangedListener
            mClipChangedListener =
            new android.content.ClipboardManager.OnPrimaryClipChangedListener() {
                @Override
                public void onPrimaryClipChanged() {
                    captureCurrentClip();
                }
            };

    public ClipboardHistoryManager(Context context) {
        mAppContext = context.getApplicationContext();
        load();
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    /** Whether newly copied text should be recorded (off in password fields). */
    public void setCaptureEnabled(boolean enabled) {
        mCaptureEnabled = enabled;
    }

    public void startListening() {
        if (mListening) return;
        try {
            mClipboardService = (android.content.ClipboardManager)
                    mAppContext.getSystemService(Context.CLIPBOARD_SERVICE);
            if (mClipboardService == null) return;
            mClipboardService.addPrimaryClipChangedListener(mClipChangedListener);
            mListening = true;
        } catch (RuntimeException e) {
            // Clipboard service unavailable on this device/ROM.
            mListening = false;
        }
    }

    public void stopListening() {
        if (!mListening) return;
        mListening = false;
        try {
            if (mClipboardService != null) {
                mClipboardService.removePrimaryClipChangedListener(mClipChangedListener);
            }
        } catch (RuntimeException e) {
            // Ignore.
        }
        mClipboardService = null;
    }

    /** Grab the current system clipboard content into history (if enabled). */
    public void captureCurrentClip() {
        if (!mCaptureEnabled) return;
        String text = readPrimaryClipText();
        if (TextUtils.isEmpty(text)) return;
        if (text.length() > MAX_TEXT_LENGTH) {
            text = text.substring(0, MAX_TEXT_LENGTH);
        }
        addClip(text);
    }

    public List<ClipItem> getItems() {
        return new ArrayList<ClipItem>(mItems);
    }

    public void togglePin(ClipItem item) {
        for (int i = 0; i < mItems.size(); i++) {
            if (mItems.get(i).equals(item)) {
                mItems.set(i, mItems.get(i).withPinned(!mItems.get(i).pinned));
                sort();
                save();
                notifyChanged();
                return;
            }
        }
    }

    public void delete(ClipItem item) {
        // Pinned items are protected and can only be removed after unpinning.
        if (item.pinned) return;
        for (int i = 0; i < mItems.size(); i++) {
            if (mItems.get(i).equals(item)) {
                mItems.remove(i);
                save();
                notifyChanged();
                // If this was the live system clip, clear it as well so
                // reopening the panel does not resurrect it.
                if (TextUtils.equals(readPrimaryClipText(), item.text)) {
                    clearSystemClip();
                }
                return;
            }
        }
    }

    public void clearAll() {
        if (mItems.isEmpty()) return;
        String live = readPrimaryClipText();
        boolean liveRemoved = false;
        boolean removedAny = false;
        for (int i = mItems.size() - 1; i >= 0; i--) {
            ClipItem item = mItems.get(i);
            // Pinned items survive Clear All.
            if (item.pinned) continue;
            if (TextUtils.equals(item.text, live)) liveRemoved = true;
            mItems.remove(i);
            removedAny = true;
        }
        if (!removedAny) return;
        save();
        notifyChanged();
        if (liveRemoved) clearSystemClip();
    }

    /** Empty the system clipboard (fires the change listener, but empty text is ignored). */
    private void clearSystemClip() {
        try {
            android.content.ClipboardManager cm = mClipboardService;
            if (cm == null) {
                cm = (android.content.ClipboardManager)
                        mAppContext.getSystemService(Context.CLIPBOARD_SERVICE);
            }
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("", ""));
            }
        } catch (RuntimeException e) {
            // Ignore.
        }
    }

    private void addClip(String text) {
        // Move an existing identical text to the top, keeping its pin state.
        ClipItem existing = null;
        for (int i = 0; i < mItems.size(); i++) {
            if (TextUtils.equals(mItems.get(i).text, text)) {
                existing = mItems.remove(i);
                break;
            }
        }
        boolean pinned = existing != null && existing.pinned;
        mItems.add(0, new ClipItem(text, System.currentTimeMillis(), pinned));
        sort();
        // Cap the list, never dropping pinned items until unpinned ones are gone.
        for (int i = mItems.size() - 1; i >= 0 && mItems.size() > MAX_ITEMS; i--) {
            if (!mItems.get(i).pinned) mItems.remove(i);
        }
        for (int i = mItems.size() - 1; i >= 0 && mItems.size() > MAX_ITEMS; i--) {
            mItems.remove(i);
        }
        save();
        notifyChanged();
    }

    private void sort() {
        // Pinned first (stable for the rest, newest already on top).
        List<ClipItem> pinned = new ArrayList<ClipItem>();
        List<ClipItem> rest = new ArrayList<ClipItem>();
        for (ClipItem item : mItems) {
            if (item.pinned) pinned.add(item);
            else rest.add(item);
        }
        mItems.clear();
        mItems.addAll(pinned);
        mItems.addAll(rest);
    }

    private String readPrimaryClipText() {
        try {
            android.content.ClipboardManager cm = mClipboardService;
            if (cm == null) {
                cm = (android.content.ClipboardManager)
                        mAppContext.getSystemService(Context.CLIPBOARD_SERVICE);
            }
            if (cm == null || !cm.hasPrimaryClip()) return null;
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence text = clip.getItemAt(0).coerceToText(mAppContext);
            if (TextUtils.isEmpty(text)) return null;
            return text.toString().trim();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void notifyChanged() {
        if (mListener == null) return;
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mListener != null) mListener.onHistoryChanged();
            }
        });
    }

    private void load() {
        mItems.clear();
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mAppContext);
        String json = sp.getString(PREF_KEY, null);
        if (TextUtils.isEmpty(json)) return;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                String text = obj.optString("text", null);
                if (TextUtils.isEmpty(text)) continue;
                mItems.add(new ClipItem(text, obj.optLong("time", 0),
                        obj.optBoolean("pinned", false)));
            }
            sort();
        } catch (JSONException e) {
            mItems.clear();
        }
    }

    private void save() {
        JSONArray array = new JSONArray();
        for (ClipItem item : mItems) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("text", item.text);
                obj.put("time", item.time);
                obj.put("pinned", item.pinned);
                array.put(obj);
            } catch (JSONException e) {
                // Skip this item.
            }
        }
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mAppContext);
        sp.edit().putString(PREF_KEY, array.toString()).apply();
    }
}

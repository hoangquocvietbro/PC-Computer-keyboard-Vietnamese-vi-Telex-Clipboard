package org.pocketworkstation.pckeyboard;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.MeasureSpec;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Clipboard history panel shown in place of the suggestion strip.
 * Tap an item to paste it, pin to keep it, delete to remove it.
 */
public class ClipboardPanel extends LinearLayout {

    public interface ClipboardActionListener {
        void onPasteClip(String text);
        void onCloseClipboardPanel();
    }

    private final ClipboardHistoryManager mManager;
    private ClipboardActionListener mListener;
    private final ClipAdapter mAdapter = new ClipAdapter();
    private ListView mListView;
    private View mEmptyView;
    /** Fixed height cap so the panel never grows past the keyboard. */
    private int mMaxHeight = Integer.MAX_VALUE;

    /**
     * Lock the panel height (e.g. to the keyboard height). The clip list
     * scrolls inside instead of growing the panel.
     */
    public void setMaxHeight(int maxHeight) {
        mMaxHeight = maxHeight > 0 ? maxHeight : Integer.MAX_VALUE;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int mode = MeasureSpec.getMode(heightMeasureSpec);
        int size = MeasureSpec.getSize(heightMeasureSpec);
        int heightSpec;
        if (mode == MeasureSpec.UNSPECIFIED) {
            // IME measures with UNSPECIFIED: without a cap the ListView
            // would measure every row and grow forever.
            heightSpec = MeasureSpec.makeMeasureSpec(mMaxHeight, MeasureSpec.EXACTLY);
        } else {
            heightSpec = MeasureSpec.makeMeasureSpec(
                    Math.min(size, mMaxHeight), MeasureSpec.EXACTLY);
        }
        super.onMeasure(widthMeasureSpec, heightSpec);
    }

    public ClipboardPanel(Context context, ClipboardHistoryManager manager) {
        super(context);
        mManager = manager;
        setOrientation(VERTICAL);
        // Opaque background so the keyboard underneath does not show through
        // when this panel overlays the whole input view.
        setBackgroundResource(R.drawable.keyboard_suggest_strip);
        // Consume all touches so taps on empty/header areas never fall
        // through to the keyboard underneath.
        setClickable(true);
        LayoutInflater.from(context).inflate(R.layout.clipboard_panel, this, true);
        View back = findViewById(R.id.clipboard_back);
        back.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mListener != null) mListener.onCloseClipboardPanel();
            }
        });
        View clearAll = findViewById(R.id.clipboard_clear_all);
        clearAll.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mManager.clearAll();
            }
        });
        mListView = (ListView) findViewById(R.id.clipboard_list);
        mEmptyView = findViewById(R.id.clipboard_empty);
        mListView.setEmptyView(mEmptyView);
        mListView.setAdapter(mAdapter);
        mListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                ClipboardHistoryManager.ClipItem item = mAdapter.getItem(position);
                if (item != null && mListener != null) {
                    mListener.onPasteClip(item.text);
                }
            }
        });
        refresh();
    }

    public void setListener(ClipboardActionListener listener) {
        mListener = listener;
    }

    public boolean isPanelShowing() {
        return getVisibility() == VISIBLE;
    }

    public void refresh() {
        mAdapter.setItems(mManager.getItems());
    }

    private class ClipAdapter extends BaseAdapter {
        private final List<ClipboardHistoryManager.ClipItem> mClips =
                new ArrayList<ClipboardHistoryManager.ClipItem>();

        void setItems(List<ClipboardHistoryManager.ClipItem> items) {
            mClips.clear();
            mClips.addAll(items);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return mClips.size();
        }

        @Override
        public ClipboardHistoryManager.ClipItem getItem(int position) {
            return mClips.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(getContext())
                        .inflate(R.layout.clipboard_item, parent, false);
            }
            final ClipboardHistoryManager.ClipItem item = getItem(position);
            TextView text = (TextView) view.findViewById(R.id.clip_text);
            text.setText(item.text);
            ImageButton pin = (ImageButton) view.findViewById(R.id.clip_pin);
            pin.setFocusable(false);
            // Pinned items show the pin straight down; unpinned ones are
            // dimmed and tilted 30 degrees towards the bottom-right corner.
            pin.setAlpha(item.pinned ? 1.0f : 0.35f);
            pin.setRotation(item.pinned ? 0f : -30f);
            pin.setContentDescription(getResources().getString(
                    item.pinned ? R.string.clipboard_unpin : R.string.clipboard_pin));
            pin.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mManager.togglePin(item);
                }
            });
            ImageButton delete = (ImageButton) view.findViewById(R.id.clip_delete);
            delete.setFocusable(false);
            // Pinned items are protected: dim the delete button and disable it.
            delete.setEnabled(!item.pinned);
            delete.setAlpha(item.pinned ? 0.3f : 1.0f);
            delete.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mManager.delete(item);
                }
            });
            return view;
        }
    }
}

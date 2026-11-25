// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.miam1ku.mibandoplusbridge.ui;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import java.util.ArrayList;
import java.util.List;

/**
 * Paged shell for the home screen: one glass top bar, one page visible at a time, and a floating
 * {@link GlassTabBar} over the content.
 *
 * <p>Both bars are capsules that float over the page rather than strips that frame it — the content
 * scrolls underneath and past both of them. That is why the pages carry a top and a bottom padding
 * instead of being laid out between the two bars: the space is reserved for the first paint, but the
 * padding is clipped ({@code clipToPadding(false)}) so content can travel under the glass.
 *
 * <p>Pages are kept alive instead of being rebuilt on every switch. The home screen polls three
 * content providers and rewrites the status card from those callbacks, so a page that re-created its
 * views on selection would blank out until the next poll landed.
 */
public final class BridgePager {
    private static final int BAR_HEIGHT_DP = 62;
    private static final int BAR_SIDE_DP = 16;
    private static final int BAR_BOTTOM_DP = 14;

    private final AppCompatActivity activity;
    private final FrameLayout content;
    private final GlassTopBar topBar;
    private final GlassTabBar bar;
    private final List<ScrollView> pages = new ArrayList<>();
    private final List<LinearLayout> bodies = new ArrayList<>();
    private int current;

    public static BridgePager attach(AppCompatActivity activity, String title, String... tabs) {
        return new BridgePager(activity, title, tabs);
    }

    private BridgePager(AppCompatActivity activity, String title, String[] tabs) {
        this.activity = activity;
        FrameLayout root = new FrameLayout(activity);
        root.setBackground(new GlassBackdrop(activity));

        content = new FrameLayout(activity);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        int gutter = dp(16);
        for (String tab : tabs) {
            ScrollView page = new ScrollView(activity);
            page.setFillViewport(true);
            page.setClipToPadding(false);
            page.setVerticalScrollBarEnabled(false);
            LinearLayout body = new LinearLayout(activity);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(gutter, dp(4), gutter, dp(16));
            page.addView(body, new LinearLayout.LayoutParams(-1, -2));
            page.setVisibility(View.GONE);
            content.addView(page, new FrameLayout.LayoutParams(-1, -1));
            pages.add(page);
            bodies.add(body);
        }

        topBar = new GlassTopBar(activity, title, null);
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, -2);
        topParams.gravity = Gravity.TOP;
        topParams.leftMargin = dp(BAR_SIDE_DP);
        topParams.rightMargin = dp(BAR_SIDE_DP);
        topParams.topMargin = dp(GlassTopBar.TOP_GAP_DP);
        root.addView(topBar, topParams);

        bar = new GlassTabBar(activity, tabs);
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(-1, -2);
        barParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        barParams.leftMargin = dp(BAR_SIDE_DP);
        barParams.rightMargin = dp(BAR_SIDE_DP);
        barParams.bottomMargin = dp(BAR_BOTTOM_DP);
        root.addView(bar, barParams);
        bar.setOnTabSelected(this::show);

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, 0);

            FrameLayout.LayoutParams top = (FrameLayout.LayoutParams) topBar.getLayoutParams();
            top.leftMargin = bars.left + dp(BAR_SIDE_DP);
            top.rightMargin = bars.right + dp(BAR_SIDE_DP);
            topBar.setLayoutParams(top);

            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) bar.getLayoutParams();
            params.leftMargin = bars.left + dp(BAR_SIDE_DP);
            params.rightMargin = bars.right + dp(BAR_SIDE_DP);
            params.bottomMargin = bars.bottom + dp(BAR_BOTTOM_DP);
            bar.setLayoutParams(params);

            int topReserve = dp(GlassTopBar.TOP_GAP_DP) + dp(GlassTopBar.HEIGHT_DP) + dp(8);
            int bottomReserve = dp(BAR_HEIGHT_DP) + params.bottomMargin + dp(20);
            for (ScrollView page : pages) {
                page.setPadding(0, topReserve, 0, bottomReserve);
            }
            return insets;
        });

        activity.setContentView(root);
        // No fade on the first paint: the tab bar and top bar are already attached at this point,
        // but the page itself is not, and a property animation started on a detached view can end
        // up never running — which leaves the page at alpha 0 and the screen blank until the user
        // taps a tab. The first page must simply be on screen.
        show(0, false);
    }

    /** The screen bound to one page's body. Call it while building that page. */
    public BridgeScreen page(int index) {
        return BridgeScreen.bind(activity, bodies.get(index));
    }

    /** The floating bar, so a page can retitle it or hang an action off it. */
    public GlassTopBar topBar() {
        return topBar;
    }

    /** Number of pages, so callers can drive the bar without keeping their own count. */
    public int size() {
        return pages.size();
    }

    public void show(int index) {
        show(index, true);
    }

    private void show(int index, boolean fade) {
        if (index < 0 || index >= pages.size()) return;
        boolean reselect = index == current && pages.get(index).getVisibility() == View.VISIBLE;
        for (int i = 0; i < pages.size(); i++) {
            pages.get(i).setVisibility(i == index ? View.VISIBLE : View.GONE);
        }
        bar.select(index);
        if (reselect) {
            // Tapping the tab you are already on is the standard "take me back to the top".
            pages.get(index).smoothScrollTo(0, 0);
            return;
        }
        current = index;
        View page = pages.get(index);
        page.setAlpha(1f);
        if (!fade) return;
        page.setAlpha(0f);
        page.animate().alpha(1f).setDuration(150).start();
        // Belt and braces: if the animator is dropped (see the note in the constructor), the page
        // must still end up visible rather than staying blank forever.
        page.postDelayed(() -> page.setAlpha(1f), 220);
    }

    /** Selects a page programmatically; the tab bar follows without treating it as a user tap. */
    public void pick(int index) {
        show(index);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}

package com.walter.claudewilson;

import android.content.Context;
import android.graphics.PorterDuff;
import android.widget.ImageView;

/** Line-art eye toggle using the supplied view/hide icons. */
final class PrivacyEye extends ImageView {
    PrivacyEye(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setScaleType(ScaleType.CENTER_INSIDE);
        setPadding(UiSection.dp(context, 7), UiSection.dp(context, 7),
                UiSection.dp(context, 7), UiSection.dp(context, 7));
        setHidden(Privacy.hidden());
    }

    void setHidden(boolean value) {
        int id = getResources().getIdentifier(
                value ? "eye_hide" : "eye_view", "drawable", getContext().getPackageName());
        if (id != 0) {
            setImageResource(id);
        }
        setColorFilter(value ? UiSection.CYAN : UiSection.DIM, PorterDuff.Mode.SRC_IN);
        setContentDescription(value
                ? Lang.t("显示敏感数据", "show sensitive data")
                : Lang.t("隐藏敏感数据", "hide sensitive data"));
        invalidate();
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }
}

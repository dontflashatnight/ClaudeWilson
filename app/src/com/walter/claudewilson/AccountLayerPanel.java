package com.walter.claudewilson;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.walter.claudewilson.model.Account;

import java.util.List;

/** Google account emails only, below the target view. */
final class AccountLayerPanel extends UiSection {
    AccountLayerPanel(Context context, LinearLayout root) {
        super(context);
        this.root = root;
    }

    void render(List<Account> accounts, boolean readable) {
        section(Lang.t("账号层", "Account layer"));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
        card.setBackground(UiSection.card(this));

        if (!readable) {
            row(card, "Google",
                    Lang.t("— · 读不到", "— · unreadable"));
        } else if (accounts == null || accounts.isEmpty()) {
            row(card, "Google",
                    Lang.t("— · 无记录", "— · no record"));
        } else {
            boolean found = false;
            for (Account account : accounts) {
                if (account == null || account.name().isEmpty()
                        || !"com.google".equals(account.type())) {
                    continue;
                }
                row(card, "Google", Privacy.mask(account.name()));
                found = true;
            }
            if (!found) {
                row(card, "Google",
                        Lang.t("— · 无记录", "— · no record"));
            }
        }

        root.addView(card);
    }

    private void row(LinearLayout card, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(this, 5), 0, dp(this, 5));

        TextView name = txt(label, 10, DIM);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(name);

        TextView text = txt(value, 10, FG);
        text.setTypeface(Typeface.MONOSPACE);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setGravity(Gravity.END);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.7f));
        row.addView(text);

        card.addView(row);
    }
}

// SPDX-License-Identifier: MIT
package ru.vtosters.lite.core.ui;

import ru.vtosters.hooks.other.ThemesUtils;
import ru.vtosters.lite.utils.AndroidUtils;

/**
 * Design tokens (F-03): one place for colors and sizes. For now it only proxies existing
 * helpers, so switching to a new design system does not touch feature code.
 */
public final class Tokens {

    private Tokens() {
    }

    public static int color(int colorRes) {
        return ThemesUtils.getColor(colorRes);
    }

    public static int attr(int attrRes) {
        return ThemesUtils.getColorFromAttr(attrRes);
    }

    public static int accent() {
        return ThemesUtils.getAccentColor();
    }

    public static int dp(float dp) {
        return AndroidUtils.dp2px(dp);
    }
}

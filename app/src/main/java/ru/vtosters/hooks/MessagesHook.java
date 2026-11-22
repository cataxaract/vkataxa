package ru.vtosters.hooks;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import com.vk.api.internal.MethodCall;
import com.vk.im.engine.models.messages.MsgFromUser;
import com.vk.im.engine.models.messages.NestedMsg;
import com.vk.im.ui.providers.audiomsg.ImAudioMsgPlayerProvider;
import com.vk.im.ui.providers.audiomsg.PlayerActionSources;
import com.vtosters.lite.im.ImEngineProvider;
import ru.vtosters.hooks.other.Preferences;
import ru.vtosters.lite.translators.BaseTranslator;
import ru.vtosters.lite.ui.dialogs.MessageSettings;
import ru.vtosters.lite.utils.LifecycleUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static ru.vtosters.hooks.other.Preferences.*;
import static ru.vtosters.lite.ui.dialogs.MessageSettings.bombCount;
import static ru.vtosters.lite.ui.dialogs.MessageSettings.isSilentEnabled;

public class MessagesHook {
    private static final Pattern MENTIONS_PATTERN = Pattern.compile("(\\[(?:club|id|public)\\d+\\|[^]]+\\])|(@[^\\s]+)");
    private static final Pattern MENTION_PLACEHOLDER_PATTERN = Pattern.compile("vtl_mention(\\d+)");

    public static void prepareDeletedForward(MsgFromUser msg) {
        if (msg == null || !DeletedMessagesHook.hook()) return;

        List<NestedMsg> nestedMessages = msg.m0();
        if (nestedMessages == null || nestedMessages.isEmpty()) return;

        List<NestedMsg> remaining = new ArrayList<>(nestedMessages.size());
        StringBuilder copiedText = new StringBuilder();
        boolean hasDeleted = false;

        for (NestedMsg nestedMsg : nestedMessages) {
            if (nestedMsg == null) continue;

            if (DeletedMessagesHook.isDeletedMessage(nestedMsg)) {
                hasDeleted = true;

                String body = nestedMsg.f();
                if (TextUtils.isEmpty(body)) continue;

                int peerId = nestedMsg.getFrom() != null ? nestedMsg.getFrom().getId() : 0;
                String text = EncryptionMessagesHook.decryptMessage(body, peerId);

                if (!TextUtils.isEmpty(text)) {
                    if (copiedText.length() > 0) copiedText.append("\n\n");
                    copiedText.append(text);
                }
            } else {
                remaining.add(nestedMsg);
            }
        }

        if (!hasDeleted || copiedText.length() == 0) return;

        String ownText = msg.f();
        if (!TextUtils.isEmpty(ownText)) {
            copiedText.insert(0, ownText + "\n\n");
        }

        msg.d(copiedText.toString());
        msg.d(remaining);
    }

    public static String injectOwnText(String oldText) {
        if (!autotranslate() || Preferences.serverFeaturesDisable() || TextUtils.isEmpty(oldText))
            return oldText;

        var instance = BaseTranslator.getInstance();
        if (instance == null) return oldText;

        return replaceMentions(oldText, instance);
    }

    public static String injectOwnTextAll(String oldText) {
        if (!autoalltranslate() || Preferences.serverFeaturesDisable() || TextUtils.isEmpty(oldText)) {
            return oldText;
        }

        var instance = BaseTranslator.getInstance();
        if (instance == null) return oldText;

        return replaceMentions(oldText, instance);
    }

    public static String replaceMentions(String oldText, BaseTranslator instance) {
        if (instance == null || oldText == null) return oldText;

        try {
            var matcher = MENTIONS_PATTERN.matcher(oldText);
            var mentionsCount = 0;

            var textBuff = new StringBuffer();
            var mentions = new ArrayList<String>();
            while (matcher.find()) {
                mentions.add(matcher.group());
                matcher.appendReplacement(textBuff, "vtl_mention" + mentionsCount);
                ++mentionsCount;
            }
            matcher.appendTail(textBuff);

            var translatedText = instance.getTranslation(textBuff.toString());
            var matcherMentions = MENTION_PLACEHOLDER_PATTERN.matcher(translatedText);
            var retTextBuff = new StringBuffer();
            while (matcherMentions.find()) {
                matcherMentions.appendReplacement(retTextBuff, mentions.get(
                        Integer.parseInt(matcherMentions.group(1))
                ));
            }
            matcherMentions.appendTail(retTextBuff);

            return retTextBuff.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return instance.getTranslation(oldText);
        }
    }

    public static void onLongClick(View view) {
        view.setOnLongClickListener(v -> {
            MessageSettings.argDialog(v.getContext());
            return true;
        });
    }

    public static Context fixCTX(Context ctx) {
        if (ctx == null) {
            return LifecycleUtils.getCurrentActivity();
        } else {
            return ctx;
        }
    }

    public static void injRequest(MethodCall.a req) {
        var time = expireTime();

        if (time > 0) {
            req.a("expire_ttl", time);
            if (!savemsgsett()) bombCount = "0";
        }

        if (isSilentEnabled) {
            req.a("silent", 1);
            if (!savemsgsett()) isSilentEnabled = false;
        }

    }

    private static int expireTime() {
        return switch (bombCount) {
            case "15s" -> 15;
            case "1m" -> 60;
            case "5m" -> 300;
            case "1h" -> 3600;
            case "24h" -> 86400;
            default -> 0;
        };
    }

    public static void reloadMessages() {
        ImEngineProvider.b().a();
        ImAudioMsgPlayerProvider.b().e(PlayerActionSources.a);
        ImAudioMsgPlayerProvider.b().d(PlayerActionSources.a);
    } // Delete and reload msg cache
}

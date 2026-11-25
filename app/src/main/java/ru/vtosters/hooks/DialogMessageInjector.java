package ru.vtosters.hooks;

import android.content.Context;
import android.text.TextUtils;
import com.vk.im.engine.models.messages.Msg;
import com.vk.im.engine.models.messages.MsgFromUser;
import com.vk.im.ui.components.common.MsgAction;
import com.vk.im.ui.components.viewcontrollers.popup.DelegateMsg;
import ru.vtosters.lite.core.guard.HookGuard;
import ru.vtosters.lite.dialogs.Requests;
import ru.vtosters.lite.ui.dialogs.Translate;
import ru.vtosters.lite.utils.AndroidUtils;

import java.util.LinkedHashMap;

public class DialogMessageInjector {
    public static LinkedHashMap<MsgAction, DelegateMsg.a.a> injectToHashMap(LinkedHashMap<MsgAction, DelegateMsg.a.a> hashMap) {
        return HookGuard.safe("DialogMsg", hashMap, () -> {
            hashMap.put(MsgAction.KICK, new DelegateMsg.a.a(com.vtosters.lite.R.string.vkim_accessibility_kick_from_chat));
            hashMap.put(MsgAction.TRANSLATE, new DelegateMsg.a.a(com.vtosters.lite.R.string.translator));
            hashMap.put(MsgAction.READTO, new DelegateMsg.a.a(com.vtosters.lite.R.string.readto));
            return hashMap;
        });
    }

    public static boolean onClick(Context context, MsgAction action, Msg msg) { // popup menu in chat
        return HookGuard.safe("DialogMsg", false, () -> {
            var peerId = msg.v1();

            switch (action) {
                case KICK -> {
                    Requests.hookKick(msg);
                    return true;
                }
                case READTO -> {
                    Requests.hookReadStartMsgTo(msg);
                    return true;
                }
                case TRANSLATE -> {
                    if (msg instanceof MsgFromUser) {
                        var text = ((MsgFromUser) msg).f();
                        var isTextExist = !TextUtils.isEmpty(text) && !text.trim().isEmpty();

                        if (isTextExist) {
                            Translate.showTranslatedText(context, EncryptionMessagesHook.decryptMessage(text, peerId));
                        } else {
                            AndroidUtils.sendToast(context.getString(com.vtosters.lite.R.string.translator_no_text));
                        }

                        return true;
                    }
                }
            }

            return false;
        });
    }
}

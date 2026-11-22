package ru.vtosters.hooks;

import android.util.LruCache;
import com.vk.im.engine.models.messages.Msg;
import com.vk.im.engine.models.messages.MsgFromUser;
import com.vk.im.ui.components.msg_list.MsgListComponent;
import ru.vtosters.lite.encryption.EncryptProvider;
import ru.vtosters.lite.encryption.base.IMProcessor;

import static ru.vtosters.hooks.MessagesHook.injectOwnText;
import static ru.vtosters.hooks.MessagesHook.injectOwnTextAll;

public class EncryptionMessagesHook {
    private static final String LOCK_PREFIX = "\uD83D\uDD12 ";
    private static final LruCache<String, Boolean> sPlainTextCache = new LruCache<>(256);

    // For message editing
    public static String decryptMessage(MsgFromUser msg, boolean showEmoji) {
        if (msg == null) return "";

        var decryptedMessage = decryptMessage(EncryptProvider.getBody(msg), EncryptProvider.getPeerId(msg));
        if (!showEmoji) {
            decryptedMessage = stripLockPrefix(decryptedMessage);
        }
        return decryptedMessage;
    }

    public static String decryptMessage(String msg, MsgListComponent list) {
        if (msg == null || list == null) return msg;

        var peerid = list.G().getId();
        return stripLockPrefix(decryptMessage(msg, peerid));
    }

    public static CharSequence decryptMessage(CharSequence msg, int peerid) {
        if (msg == null) return null;

        return decryptMessage(msg.toString(), peerid);
    }

    public static CharSequence decryptMessage(Msg msg, CharSequence orig) {
        if (msg instanceof MsgFromUser) {
            var decrypted = decryptMessage(((MsgFromUser) msg).f(), msg.v1());

            if (decrypted != null && decrypted.contains("\uD83D\uDD12")) {
                return decrypted;
            }

        }

        return orig;
    }

    public static String decryptMessages(String msgBody, int peer, boolean showEmoji) {
        return (String) decryptMessage(msgBody, peer, showEmoji);
    }

    public static CharSequence decryptMessage(CharSequence msgBody, MsgFromUser msgFromUser, boolean showEmoji) {
        if (msgBody == null) return null;

        try {
            return decryptMessage((String) msgBody, msgFromUser.v1(), showEmoji);
        } catch (Exception e) {
            return decryptMessage((String) msgBody, 0, showEmoji);
        }
    }

    // For MentionsFormatter that requires CharSequence
    public static CharSequence decryptMessage(String msgBody, int peer, boolean showEmoji) {
        if (msgBody == null) return null;

        var decryptedMessage = decryptMessage(msgBody, peer);
        if (!showEmoji) {
            decryptedMessage = stripLockPrefix(decryptedMessage);
        }
        return decryptedMessage;
    }

    // This will run through EVERY single processor available.
    public static String decryptMessage(MsgFromUser msg) {
        if (msg == null) return "";

        return decryptMessage(EncryptProvider.getBody(msg), EncryptProvider.getPeerId(msg));
    }

    public static String decryptMessage(String msgBody, int peer) {
        if (msgBody == null) return null;
        if (Boolean.FALSE.equals(sPlainTextCache.get(msgBody))) return injectOwnTextAll(msgBody);

        try {
            boolean matched = false;

            for (IMProcessor processor : EncryptProvider.processors) {
                if (!processor.isEncrypted(msgBody)) continue;

                matched = true;

                if (processor.isPublic()) {
                    if (!processor.isUsed()) continue;

                    return LOCK_PREFIX + processor.getUIName() + ": " + injectOwnTextAll(processor.decode(msgBody, null));
                }

                byte[] key = EncryptProvider.getKeyForProcessor(processor, peer);
                if (key == null) continue;

                return LOCK_PREFIX + processor.getUIName() + ": " + injectOwnTextAll(processor.decode(msgBody, key));
            }

            if (!matched) {
                sPlainTextCache.put(msgBody, false);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return injectOwnTextAll(msgBody);
    }

    // This will use only the processor which was chosen in chat menu
    public static String encryptMessage(MsgFromUser msg) {
        String msgBody = EncryptProvider.getBody(msg);
        if (msgBody == null) return "";

        int peer = EncryptProvider.getPeerId(msg);

        for (IMProcessor processor : EncryptProvider.processors) {
            if (!processor.isUsedToEncrypt(peer)) continue;

            byte[] key = EncryptProvider.getKeyForProcessor(processor, peer);
            if (!processor.isPublic() && key == null) continue;

            return injectOwnText(processor.encode(msgBody, key));
        }

        return injectOwnText(msgBody);
    }

    private static String stripLockPrefix(String text) {
        if (text != null && text.startsWith(LOCK_PREFIX)) {
            return text.substring(LOCK_PREFIX.length());
        }
        return text;
    }
}

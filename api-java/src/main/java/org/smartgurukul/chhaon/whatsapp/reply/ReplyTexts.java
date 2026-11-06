package org.smartgurukul.chhaon.whatsapp.reply;

/**
 * The words we send back to a parent after they replied (English and Hindi). Ported from
 * {@code api/app/whatsapp/templates.py} (REPLIES), rewritten for a chat without buttons: they tell the parent to
 * TYPE a number or word. Reply convention everywhere: 1 = yes / taken / I am okay, 2 = no / call me, STOP any time.
 *
 * <p>These are free-form replies to a message the parent just sent (inside WhatsApp's 24-hour window). They never
 * contain what the parent wrote.
 */
public final class ReplyTexts {

    private ReplyTexts() {}

    private static boolean hi(String language) {
        return "hi".equals(language);
    }

    // ---- ported from the Python REPLIES

    public static String consentYes(String lang) {
        return hi(lang)
                ? "धन्यवाद 🙏 अब आपको यहाँ दवा के रिमाइंडर मिलेंगे। रोकने के लिए कभी भी STOP लिखें।"
                : "Thank you 🙏 You will now get gentle medicine reminders here. Reply STOP any time to stop.";
    }

    public static String consentNo(String lang) {
        return hi(lang)
                ? "कोई बात नहीं। हम आपको कोई संदेश नहीं भेजेंगे। मन बदले तो START लिखें।"
                : "No problem. We will not send you any messages. If you change your mind, reply START.";
    }

    public static String stopped(String lang) {
        return hi(lang)
                ? "ठीक है, हमने सभी संदेश बंद कर दिए हैं। दोबारा चाहिए तो START लिखें।"
                : "Okay, we have stopped all messages. Reply START if you want them again.";
    }

    public static String resumed(String lang) {
        return hi(lang)
                ? "फिर से स्वागत है 🙏 रिमाइंडर दोबारा शुरू हो गए हैं।"
                : "Welcome back 🙏 Reminders have started again.";
    }

    public static String alertsOn(String lang) {
        return hi(lang)
                ? "धन्यवाद 🙏 छाँव अलर्ट चालू हो गए हैं। जब भी कोई ज़रूरी बात होगी, हम आपको यहाँ बता देंगे। बंद करने के लिए कभी भी STOP लिखें।"
                : "Thank you 🙏 Chhaon alerts are on. We will message you here when something needs your attention. Reply STOP any time to turn them off.";
    }

    public static String alertsOff(String lang) {
        return hi(lang)
                ? "ठीक है, छाँव अलर्ट बंद हैं। आप इन्हें ऐप में दोबारा चालू कर सकते हैं।"
                : "Okay, Chhaon alerts are off. You can turn them on again in the app.";
    }

    public static String alertsResumed(String lang) {
        return hi(lang)
                ? "छाँव अलर्ट फिर से चालू हैं। बंद करने के लिए STOP लिखें।"
                : "Chhaon alerts are on again. Reply STOP to turn them off.";
    }

    /** A caregiver typed 2 to "turn on alerts?". */
    public static String alertsDeclined(String lang) {
        return hi(lang)
                ? "कोई बात नहीं 🙏 छाँव अलर्ट बंद रहेंगे। मन बदले तो ऐप में फिर से चालू कर सकते हैं।"
                : "No problem 🙏 Chhaon alerts stay off. If you change your mind, you can turn them on in the app.";
    }

    /** START from a number that only had alerts asked (not a parent, never confirmed): it may be asked again now. */
    public static String alertsMayAskAgain(String lang) {
        return hi(lang)
                ? "ठीक है 🙏 अब आप ऐप में WhatsApp अलर्ट फिर से चालू कर सकते हैं।"
                : "Okay 🙏 You can now turn on WhatsApp alerts again in the app.";
    }

    // ---- new for typed replies

    /** After a dose was recorded. {@code medicine} is the parent's own medicine name, shown back so they know what was noted. */
    public static String doseTaken(String lang, String medicine) {
        String med = medicine == null || medicine.isBlank() ? "" : " " + medicine.trim();
        return hi(lang)
                ? "धन्यवाद 🙏 दवा" + med + " नोट कर ली ✅"
                : "Thank you 🙏 Noted:" + (med.isEmpty() ? " your medicine" : med) + " ✅";
    }

    public static String doseNotYet(String lang) {
        return hi(lang)
                ? "ठीक है 🙏 दवा लेने के बाद 1 लिखकर भेज दीजिए।"
                : "Okay 🙏 Please reply 1 once you have taken it.";
    }

    /** A "⏰ Later" vote on a reminder poll: nothing is recorded; tap ✅ once taken. */
    public static String dosePollLater(String lang) {
        return hi(lang)
                ? "ठीक है 🙏 लेने के बाद ✅ चुन लीजिए"
                : "Okay 🙏 Tap ✅ once you have taken it.";
    }

    /** Appended to a confirmation when another medicine is still waiting. */
    public static String nextDoseHint(String lang) {
        return hi(lang)
                ? "अगली दवा के लिए फिर से 1 लिखें।"
                : "Reply 1 again for the next medicine.";
    }

    /** Appended to a confirmation when today's check-in has not been answered yet. */
    public static String checkinHint(String lang) {
        return hi(lang)
                ? "आज आपकी तबीयत कैसी है? ठीक हैं तो 1 लिखें, बात करनी हो तो 2 लिखें।"
                : "Are you okay today? Reply 1 if yes, 2 if you want a call.";
    }

    public static String checkinOk(String lang) {
        return hi(lang)
                ? "धन्यवाद 🙏 जानकर अच्छा लगा कि आप ठीक हैं। आपका दिन अच्छा रहे।"
                : "Thank you 🙏 Glad you are okay. Have a lovely day.";
    }

    public static String checkinCall(String lang) {
        return hi(lang)
                ? "ठीक है 🙏 हमने आपके परिवार को बता दिया है।"
                : "Okay 🙏 We have let your family know.";
    }

    /** A reply we could not understand (sent at most once every few hours per number). */
    public static String help(String lang) {
        return hi(lang)
                ? "नमस्ते 🙏 जवाब में बस नंबर लिखें:\n1 = हाँ / दवा ले ली / मैं ठीक हूँ\n2 = नहीं / बात करनी है\nसंदेश रोकने के लिए STOP लिखें।"
                : "Namaste 🙏 To reply: type 1 for yes / taken / I am okay, 2 for no / call me. Type STOP to stop messages.";
    }

    /** A reply we could not understand while a consent question is open. */
    public static String consentHelp(String lang) {
        return hi(lang)
                ? "नमस्ते 🙏 रिमाइंडर शुरू करने के लिए 1 लिखें, नहीं चाहिए तो 2। रोकने के लिए कभी भी STOP लिखें।"
                : "Namaste 🙏 Reply 1 to start reminders, 2 for no. STOP any time.";
    }
}

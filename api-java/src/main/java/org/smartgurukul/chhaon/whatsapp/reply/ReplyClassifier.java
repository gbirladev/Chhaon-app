package org.smartgurukul.chhaon.whatsapp.reply;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns what a parent typed into an {@link Intent}. Pure: no database, no clock, no state.
 *
 * <p>Reply convention (the texts we send end with it): <b>1</b> = yes / taken / I am okay,
 * <b>2</b> = no / call me. STOP is always honoured, in English and Hindi.
 *
 * <p>Normalisation: Unicode NFKC (so full-width "ＳＴＯＰ" and circled digits fold to plain letters and digits),
 * invisible characters removed, Devanagari digits turned into ASCII digits, the nukta and the chandrabindu
 * folded (so different spellings of the same Hindi word match), lower-case, emoji and punctuation ignored for
 * the words (a few emoji are read as answers when nothing else was written).
 *
 * <p>The STOP rules are a faithful port of {@code api/app/services/inbound.py}: a short message (at most
 * {@value #MAX_STOP_WORDS} words) containing a stop word and no negation; "बंद करो" (close + do) also counts.
 * A long sentence that merely contains "stop", or "please don't stop", never opts anyone out.
 */
public final class ReplyClassifier {

    static final int MAX_STOP_WORDS = 4;
    static final int MAX_START_WORDS = 2;
    private static final int MAX_ANSWER_WORDS = 6;
    private static final int MAX_CALL_WORDS = 8;

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}\\p{N}']+");

    // ---------------------------------------------------------------- opt-out / opt-in (ported from Python)
    private static final Set<String> STOP_WORDS = words("stop", "unsubscribe", "रुको", "रुकिए", "रुकें", "रोकें");
    private static final Set<String> STOP_PHRASE_WORD = words("बंद", "बन्द");
    private static final Set<String> STOP_PHRASE_VERB = words("करो", "करें", "कीजिए", "कर");
    /** "please don't stop" is not an opt-out. Exactly the Python set. */
    private static final Set<String> STOP_NEGATIONS = words("dont", "don't", "not", "no", "never", "मत", "नहीं");
    private static final Set<String> START_WORDS = words("start", "शुरू", "शुरु");

    /** Wider negation set for the answers ("nahi li" = not taken). */
    private static final Set<String> NEGATIONS = union(STOP_NEGATIONS, words(
            "didnt", "didn't", "havent", "haven't", "hasnt", "hasn't", "wont", "won't", "cant", "can't", "cannot",
            "nahi", "nahin", "nai", "नही", "नहिं", "नहि", "mat"));

    // ---------------------------------------------------------------- answers
    private static final Set<String> POLITE = words("ji", "जी", "please", "pls", "plz", "sir", "madam", "madame");

    private static final Set<String> YES_EXACT = phrases(
            "1", "yes", "y", "yeah", "yep", "yup", "ya", "haan", "han", "haa", "ha", "हां", "हा", "हांजी", "hanji", "haanji",
            "sahi", "सही", "bilkul", "बिलकुल", "बिल्कुल", "sure", "agree", "i agree", "accept", "one", "ek", "एक");
    private static final Set<String> NO_EXACT = phrases(
            "2", "no", "n", "nope", "nahi", "nahin", "nai", "नहीं", "नही", "ना", "na", "no thanks", "no thank you",
            "nahi chahiye", "नहीं चाहिए", "not now", "not yet", "later", "abhi nahi", "अभी नहीं", "baad mein", "बाद में",
            "two", "दो");
    private static final Set<String> ACK_EXACT = phrases(
            "ok", "okay", "k", "kk", "okk", "okey", "alright", "all right", "theek hai", "thik hai", "theek h", "thik h",
            "ठीक है", "ठीक", "theek", "thik", "accha", "achha", "अच्छा", "got it", "noted", "understood",
            "samajh gaya", "samajh gayi", "समझ गया", "समझ गई");
    private static final Set<String> THANKS_EXACT = phrases(
            "thanks", "thank you", "thankyou", "thx", "dhanyavad", "dhanyawad", "धन्यवाद", "shukriya", "शुक्रिया",
            "god bless", "bless you", "god bless you");

    private static final List<List<String>> TAKEN = seqs(
            "taken", "took", "done", "liya", "li", "le li", "le liya", "kha li", "kha liya",
            "ले ली", "ले लिया", "खा ली", "खा लिया", "ली", "लिया");
    private static final Set<String> ALL_WORDS = words("all", "sab", "sabhi", "saari", "sari", "सब", "सभी", "सारी", "सारे");

    private static final List<List<String>> CHECKIN_OK = seqs(
            "i am okay", "i am ok", "i am fine", "i am well", "i am good", "am fine", "am ok", "am okay",
            "im okay", "im ok", "im fine", "im well", "im good", "i'm okay", "i'm ok", "i'm fine", "i'm well", "i'm good",
            "all good", "all fine", "all well", "fine", "doing well", "doing fine", "feeling fine", "feeling good",
            "feeling well", "theek hoon", "theek hu", "theek hun", "thik hoon", "thik hu", "thik hun",
            "main theek", "mein theek", "sab theek", "sab badhiya", "badhiya", "ठीक हूं", "ठीक हु", "मैं ठीक", "मै ठीक",
            "सब ठीक", "बढ़िया", "अच्छा हूं", "अच्छी हूं", "सब बढ़िया");

    /** Wants a call, or says something is wrong. Sent to the family as a "call me" request. */
    private static final List<List<String>> CALL_ME = seqs(
            "call me", "call karo", "call kar do", "call kijiye", "call back", "phone karo", "phone kar do", "fone karo",
            "फोन करो", "फोन कर", "फोन करना", "फोन कीजिए", "कॉल करो", "कॉल कर", "मुझे फोन", "bulao", "बुलाओ",
            "need help", "help", "madad", "मदद", "emergency", "pain", "dard", "दर्द", "sick", "unwell", "bimar", "बीमार",
            "not well", "not okay", "not ok", "not fine", "not good", "theek nahi", "thik nahi", "ठीक नहीं",
            "tabiyat kharab", "तबीयत खराब");
    /** Single words that only count when they are the whole message ("I will call you later" must not alert anyone). */
    private static final Set<String> CALL_ME_EXACT = phrases("call", "phone", "फोन", "कॉल");

    // ---------------------------------------------------------------- emoji read as an answer when no words were written
    private static final Set<Integer> EMOJI_YES = Set.of(0x2705, 0x2714, 0x2611, 0x1F44D, 0x1F197);
    private static final Set<Integer> EMOJI_NO = Set.of(0x274C, 0x274E, 0x1F44E, 0x2716);
    private static final Set<Integer> EMOJI_ACK = Set.of(0x1F44C);
    private static final Set<Integer> EMOJI_THANKS = Set.of(0x1F64F);
    private static final Set<Integer> EMOJI_HELP = Set.of(0x1F198);

    private ReplyClassifier() {}

    // ================================================================ public API

    public static Intent classify(String text) {
        if (text == null) return Intent.UNKNOWN;
        String norm = normalise(text.length() > 1000 ? text.substring(0, 1000) : text);
        List<String> tokens = tokens(norm);

        if (wantsStop(tokens)) return Intent.STOP;
        if (wantsStart(tokens)) return Intent.START;

        if (tokens.isEmpty()) return fromEmoji(norm);

        boolean negated = tokens.stream().anyMatch(NEGATIONS::contains);

        // Something is wrong / a call was asked for. Checked before the negation rule on purpose:
        // "not well" and "theek nahi" must alert the family, never be read as "okay".
        if (tokens.size() <= MAX_CALL_WORDS && matchesCall(tokens, negated)) return Intent.CHECKIN_CALL_ME;
        if (tokens.size() <= 2 && CALL_ME_EXACT.contains(String.join(" ", tokens))) return Intent.CHECKIN_CALL_ME;

        List<String> core = coreWords(tokens);
        String joined = String.join(" ", core);
        if (core.isEmpty()) return Intent.ACK; // only "ji" / "please": a polite acknowledgement
        if (YES_EXACT.contains(joined)) return Intent.YES;
        if (NO_EXACT.contains(joined)) return Intent.NO;
        if (ACK_EXACT.contains(joined)) return Intent.ACK;
        if (THANKS_EXACT.contains(joined)) return Intent.THANKS;
        // Several one-word answers of the same kind in mixed scripts ("haan yes", "ok theek hai").
        if (core.size() > 1 && core.size() <= 3) {
            if (core.stream().allMatch(YES_EXACT::contains)) return Intent.YES;
            if (core.stream().allMatch(NO_EXACT::contains)) return Intent.NO;
            if (core.stream().allMatch(w -> ACK_EXACT.contains(w) || w.equals("hai") || w.equals("है"))) return Intent.ACK;
        }

        boolean taken = containsAny(tokens, TAKEN);
        if (negated) return taken ? Intent.NO : Intent.UNKNOWN; // "nahi li", "not taken"

        if (tokens.size() <= MAX_ANSWER_WORDS) {
            if (taken) return tokens.stream().anyMatch(ALL_WORDS::contains) ? Intent.TAKEN_ALL : Intent.TAKEN;
            if (containsAny(tokens, CHECKIN_OK)) return Intent.CHECKIN_OK;
        }
        return Intent.UNKNOWN;
    }

    /** The words of a message after normalisation (exposed for tests). */
    public static List<String> wordsOf(String text) {
        return text == null ? List.of() : tokens(normalise(text));
    }

    /** NFKC, invisible characters dropped, Devanagari digits to ASCII, Hindi spelling variants folded, lower-case. */
    public static String normalise(String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            if (isInvisible(cp)) return;
            if (cp == 0x0901) cp = 0x0902; // chandrabindu -> anusvara (हाँ = हां)
            if (cp == '’' || cp == '‘' || cp == 'ʼ' || cp == '`' || cp == '´') cp = '\'';
            if (Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER) {
                int v = Character.digit(cp, 10);
                if (v >= 0) cp = '0' + v;
            }
            out.appendCodePoint(cp);
        });
        return out.toString().toLowerCase(Locale.ROOT);
    }

    // ================================================================ internals

    private static boolean isInvisible(int cp) {
        return (cp >= 0x200B && cp <= 0x200F)       // zero-width space/joiners, direction marks
                || (cp >= 0x202A && cp <= 0x202E)   // embedding / override
                || (cp >= 0x2060 && cp <= 0x2064)   // word joiner, invisible operators
                || cp == 0xFEFF || cp == 0x00AD || cp == 0x180E
                || cp == 0xFE0E || cp == 0xFE0F     // emoji variation selectors
                || cp == 0x20E3                     // combining keycap (1️⃣)
                || (cp >= 0x1F3FB && cp <= 0x1F3FF) // skin tones
                || cp == 0x093C;                    // Devanagari nukta (फ़ = फ)
    }

    private static List<String> tokens(String normalised) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(normalised);
        while (m.find()) {
            String t = m.group();
            int a = 0, b = t.length();
            while (a < b && t.charAt(a) == '\'') a++;
            while (b > a && t.charAt(b - 1) == '\'') b--;
            if (b > a) out.add(t.substring(a, b));
        }
        return out;
    }

    private static boolean wantsStop(List<String> tokens) {
        if (tokens.isEmpty() || tokens.size() > MAX_STOP_WORDS) return false;
        Set<String> w = new LinkedHashSet<>(tokens);
        if (!Collections.disjoint(w, STOP_NEGATIONS)) return false;
        return !Collections.disjoint(w, STOP_WORDS)
                || (!Collections.disjoint(w, STOP_PHRASE_WORD) && !Collections.disjoint(w, STOP_PHRASE_VERB));
    }

    private static boolean wantsStart(List<String> tokens) {
        if (tokens.isEmpty() || tokens.size() > MAX_START_WORDS) return false;
        Set<String> w = new LinkedHashSet<>(tokens);
        // A deliberate improvement on the Python rule: "don't start" must never grant consent.
        return !Collections.disjoint(w, START_WORDS) && Collections.disjoint(w, NEGATIONS);
    }

    private static boolean matchesCall(List<String> tokens, boolean negated) {
        for (List<String> phrase : CALL_ME) {
            if (!containsSeq(tokens, phrase)) continue;
            boolean phraseHasNegation = phrase.stream().anyMatch(NEGATIONS::contains)
                    || phrase.contains("not"); // "not well" etc.
            if (negated && !phraseHasNegation && !phrase.contains("nahi")) continue; // "don't call me"
            return true;
        }
        return false;
    }

    /** Tokens without politeness words at either end and with immediate repeats collapsed ("haan haan ji" = "haan"). */
    private static List<String> coreWords(List<String> tokens) {
        List<String> t = new ArrayList<>();
        for (String s : tokens) {
            if (t.isEmpty() || !t.get(t.size() - 1).equals(s)) t.add(s);
        }
        int a = 0, b = t.size();
        while (a < b && POLITE.contains(t.get(a))) a++;
        while (b > a && POLITE.contains(t.get(b - 1))) b--;
        return t.subList(a, b);
    }

    private static Intent fromEmoji(String normalised) {
        boolean yes = false, no = false, ack = false, thanks = false, help = false;
        for (int i = 0; i < normalised.length(); ) {
            int cp = normalised.codePointAt(i);
            i += Character.charCount(cp);
            yes |= EMOJI_YES.contains(cp);
            no |= EMOJI_NO.contains(cp);
            ack |= EMOJI_ACK.contains(cp);
            thanks |= EMOJI_THANKS.contains(cp);
            help |= EMOJI_HELP.contains(cp);
        }
        if (help && !yes) return Intent.CHECKIN_CALL_ME;
        int kinds = (yes ? 1 : 0) + (no ? 1 : 0) + (ack ? 1 : 0);
        if (kinds > 1) return Intent.UNKNOWN; // a tick and a cross together: not an answer
        if (yes) return Intent.YES;
        if (no) return Intent.NO;
        if (ack) return Intent.ACK;
        if (thanks) return Intent.THANKS;
        return Intent.UNKNOWN;
    }

    private static boolean containsAny(List<String> tokens, List<List<String>> phrases) {
        for (List<String> p : phrases) if (containsSeq(tokens, p)) return true;
        return false;
    }

    private static boolean containsSeq(List<String> tokens, List<String> phrase) {
        int n = phrase.size();
        for (int i = 0; i + n <= tokens.size(); i++) {
            if (tokens.subList(i, i + n).equals(phrase)) return true;
        }
        return false;
    }

    // ---- tables are normalised with the same pipeline as the input, so spelling variants line up

    private static Set<String> words(String... w) {
        Set<String> s = new LinkedHashSet<>();
        for (String x : w) s.addAll(tokens(normalise(x)));
        return s;
    }

    private static Set<String> phrases(String... p) {
        Set<String> s = new LinkedHashSet<>();
        for (String x : p) s.add(String.join(" ", tokens(normalise(x))));
        return s;
    }

    private static List<List<String>> seqs(String... p) {
        List<List<String>> l = new ArrayList<>();
        for (String x : p) l.add(tokens(normalise(x)));
        return l;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> s = new LinkedHashSet<>(a);
        s.addAll(b);
        return s;
    }
}

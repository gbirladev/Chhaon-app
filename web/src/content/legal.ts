/**
 * Privacy policy and Terms of use (English + Hindi), kept short and in plain words.
 * The screens in screens/Privacy.tsx render this as it is. When the meaning changes in a way that needs the
 * caregiver to agree again, bump POLICY_VERSION (people are then asked once more).
 */
export const POLICY_VERSION = "2026-10-1";

export type LegalSection = { h: string; p?: string[]; ul?: string[] };
export type LegalDoc = { title: string; updated: string; draft: string; sections: LegalSection[] };
type Both = { en: LegalDoc; hi: LegalDoc };

const privacy: Both = {
  en: {
    title: "Privacy policy",
    updated: "Version: 2026-10",
    draft: "",
    sections: [
      { h: "What Chhaon does", p: ["Chhaon helps you look after a parent who lives far away. You use this app. Your parent only gets WhatsApp messages: medicine reminders, a daily \"are you okay?\" and appointment reminders. They never need to install anything."] },
      { h: "What we keep", ul: [
        "About you: your name, phone number or email, and your language.",
        "About your parent: name, age, city, WhatsApp number, time zone, health conditions, allergies, blood group and emergency contacts that you add.",
        "Medicines and doctor visits you add, and whether each dose was confirmed on WhatsApp.",
        "Photos of medicines and, if report reading is switched on, reports you upload.",
      ] },
      { h: "Why we keep it", p: ["Only to run the service: to send reminders at the right time, to show you what happened today, and to alert you when something needs attention. We do not sell your data and we do not show advertisements."] },
      { h: "Who can see it", ul: [
        "You, and the family members you invite to the same parent. They can see that parent's details.",
        "Anyone you give the emergency link to. It shows only the basic emergency details and you can turn it off at any time.",
        "The services we use to run Chhaon: Supabase (database and storage), WhatsApp / Meta (to send messages to your parent and to you), and an AI service only if you use report reading.",
      ] },
      { h: "Your parent's consent", p: ["Before we send anything, your parent is asked on WhatsApp to agree. If they say no, or reply STOP at any time, we stop messaging them."] },
      { h: "Your choices", ul: [
        "Download all your data from Privacy & data.",
        "Delete your account from Privacy & data. If you are the only person looking after a parent, that parent's data is deleted too.",
        "Turn off WhatsApp alerts to you, or reply STOP to our message.",
      ] },
      { h: "Safety", p: ["Files are kept in private storage and every account can only see its own family's data. No system is perfectly safe, but we protect your data with care."] },
      { h: "Changes", p: ["If this policy changes in a way that matters, we will ask you to agree again in the app."] },
    ],
  },
  hi: {
    title: "गोपनीयता नीति",
    updated: "संस्करण: 2026-10",
    draft: "",
    sections: [
      { h: "छाँव क्या करता है", p: ["छाँव दूर रहने वाले माता-पिता का ध्यान रखने में आपकी मदद करता है। आप यह ऐप इस्तेमाल करते हैं। आपके माता-पिता को सिर्फ़ WhatsApp संदेश मिलते हैं: दवा के रिमाइंडर, रोज़ का \"क्या आप ठीक हैं?\" और डॉक्टर से मुलाक़ात के रिमाइंडर। उन्हें कुछ भी इंस्टॉल नहीं करना पड़ता।"] },
      { h: "हम क्या रखते हैं", ul: [
        "आपके बारे में: नाम, फ़ोन नंबर या ईमेल, और भाषा।",
        "आपके माता-पिता के बारे में: नाम, उम्र, शहर, WhatsApp नंबर, समय क्षेत्र, बीमारियाँ, एलर्जी, ब्लड ग्रुप और आपके डाले गए आपातकालीन संपर्क।",
        "आपकी डाली गई दवाइयाँ और डॉक्टर से मुलाक़ातें, और हर खुराक WhatsApp पर पक्की हुई या नहीं।",
        "दवाओं की फ़ोटो और, अगर रिपोर्ट पढ़ने की सुविधा चालू हो, तो आपकी डाली गई रिपोर्टें।",
      ] },
      { h: "क्यों रखते हैं", p: ["सिर्फ़ सेवा चलाने के लिए: सही समय पर रिमाइंडर भेजने, आज क्या हुआ यह दिखाने, और ज़रूरत पड़ने पर आपको सूचना देने के लिए। हम आपका डेटा बेचते नहीं और कोई विज्ञापन नहीं दिखाते।"] },
      { h: "कौन देख सकता है", ul: [
        "आप, और वे परिवार के सदस्य जिन्हें आप उसी माता-पिता के लिए जोड़ते हैं। वे उस माता-पिता की जानकारी देख सकते हैं।",
        "जिसे आप आपातकालीन लिंक देते हैं। उसमें सिर्फ़ ज़रूरी बुनियादी जानकारी दिखती है और आप उसे कभी भी बंद कर सकते हैं।",
        "छाँव चलाने के लिए हमारी इस्तेमाल की गई सेवाएँ: Supabase (डेटाबेस और स्टोरेज), WhatsApp / Meta (आपको और आपके माता-पिता को संदेश भेजने के लिए), और रिपोर्ट पढ़ने की सुविधा इस्तेमाल करने पर एक AI सेवा।",
      ] },
      { h: "आपके माता-पिता की सहमति", p: ["कुछ भी भेजने से पहले WhatsApp पर आपके माता-पिता से हाँ पूछी जाती है। अगर वे ना कहें, या कभी भी STOP लिख दें, तो हम संदेश भेजना बंद कर देते हैं।"] },
      { h: "आपके विकल्प", ul: [
        "“गोपनीयता और डेटा” से अपना सारा डेटा डाउनलोड करें।",
        "“गोपनीयता और डेटा” से अपना खाता हटाएँ। अगर किसी माता-पिता का ध्यान सिर्फ़ आप रखते हैं, तो उनका डेटा भी हट जाता है।",
        "आपको मिलने वाले WhatsApp अलर्ट बंद करें, या हमारे संदेश पर STOP लिखें।",
      ] },
      { h: "सुरक्षा", p: ["फ़ाइलें निजी स्टोरेज में रखी जाती हैं और हर खाता सिर्फ़ अपने परिवार का डेटा देख सकता है। कोई भी सिस्टम पूरी तरह सुरक्षित नहीं होता, पर हम आपके डेटा की पूरी देखभाल करते हैं।"] },
      { h: "बदलाव", p: ["अगर इस नीति में कोई ज़रूरी बदलाव होगा, तो हम ऐप में आपसे दोबारा सहमति माँगेंगे।"] },
    ],
  },
};

const terms: Both = {
  en: {
    title: "Terms of use",
    updated: "Version: 2026-10",
    draft: "",
    sections: [
      { h: "Using Chhaon", p: ["Chhaon helps families keep track of a parent's medicines, check-ins and doctor visits. Use it only for that, and only for people who have agreed to it."] },
      { h: "Not medical advice", p: ["Chhaon does not give medical advice, does not diagnose, and never tells anyone to change a medicine. For any health question, ask a doctor. In an emergency, call your local emergency number first."] },
      { h: "Reminders are not guaranteed", p: ["We try hard to send every reminder and alert on time. But WhatsApp, phones and networks can fail, so please do not rely on Chhaon alone. Call your parent if you are worried."] },
      { h: "Your part", ul: [
        "Enter correct details, especially medicine names, doses and times.",
        "Add only a parent who has agreed. Their WhatsApp number must be theirs.",
        "Keep your login safe and invite only people you trust. Family members you invite can see your parent's details.",
      ] },
      { h: "Fair use", p: ["Do not misuse the service, try to break it, or send messages to people who have not agreed."] },
      { h: "Changes and stopping", p: ["We may improve or change Chhaon. If we stop the service we will tell you, and you can download your data first."] },
    ],
  },
  hi: {
    title: "उपयोग की शर्तें",
    updated: "संस्करण: 2026-10",
    draft: "",
    sections: [
      { h: "छाँव का उपयोग", p: ["छाँव परिवारों को माता-पिता की दवाइयों, हाल-चाल और डॉक्टर से मुलाक़ातों का हिसाब रखने में मदद करता है। इसका उपयोग सिर्फ़ इसी काम के लिए करें, और सिर्फ़ उन लोगों के लिए जिन्होंने इसके लिए हाँ कही है।"] },
      { h: "यह चिकित्सा सलाह नहीं है", p: ["छाँव चिकित्सा सलाह नहीं देता, बीमारी की पहचान नहीं करता, और किसी को दवा बदलने के लिए नहीं कहता। सेहत से जुड़े किसी भी सवाल के लिए डॉक्टर से पूछें। आपातकाल में पहले अपने इलाके का आपातकालीन नंबर मिलाएँ।"] },
      { h: "रिमाइंडर की गारंटी नहीं", p: ["हम हर रिमाइंडर और अलर्ट समय पर भेजने की पूरी कोशिश करते हैं। पर WhatsApp, फ़ोन और नेटवर्क में गड़बड़ी हो सकती है, इसलिए कृपया सिर्फ़ छाँव पर निर्भर न रहें। चिंता हो तो अपने माता-पिता को फ़ोन करें।"] },
      { h: "आपकी ज़िम्मेदारी", ul: [
        "सही जानकारी डालें, खासकर दवा का नाम, मात्रा और समय।",
        "सिर्फ़ उसी माता-पिता को जोड़ें जिन्होंने हाँ कही है। WhatsApp नंबर उन्हीं का होना चाहिए।",
        "अपना लॉगिन सुरक्षित रखें और सिर्फ़ भरोसेमंद लोगों को जोड़ें। आपके जोड़े गए परिवार के सदस्य आपके माता-पिता की जानकारी देख सकते हैं।",
      ] },
      { h: "सही उपयोग", p: ["सेवा का दुरुपयोग न करें, उसे बिगाड़ने की कोशिश न करें, और उन लोगों को संदेश न भेजें जिन्होंने हाँ नहीं कही है।"] },
      { h: "बदलाव और बंद होना", p: ["हम छाँव में सुधार या बदलाव कर सकते हैं। अगर हम सेवा बंद करेंगे तो आपको बताएँगे, और आप पहले अपना डेटा डाउनलोड कर सकेंगे।"] },
    ],
  },
};

export const LEGAL = { privacy, terms } as const;

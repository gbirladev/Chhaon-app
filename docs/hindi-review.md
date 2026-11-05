# Hindi wording to review (for a native speaker)

Written 2026-11-04. Every line below is Hindi that a real person will read. **Part 1 matters most**: parents read these on WhatsApp, and
a stiff or wrong word there costs trust. Please read each Hindi line out loud and mark OK or write a better version in the last column.
Placeholders in {braces} are filled in by the app: {name} is what the family calls the parent (for example Papa), {medicine} the medicine name,
{caregiver} the family member's name.

Questions to keep in mind: Is it warm and respectful (आप, not तुम)? Is it simple enough for an older person with little schooling? Would a Hinglish word (for example रिमाइंडर, अलर्ट, WhatsApp) be better than a formal one?

## Part 1: WhatsApp messages to parents and family (the Java backend)

### Messages we start

| Message | Hindi | English meaning | OK / better wording |
|---|---|---|---|
| Dose reminder (we send, at the time) | नमस्ते {name} 🙏 दवा लेने का समय हो गया है ({medicine})। लेने के बाद 1 लिखकर भेजिए। | Namaste {name} 🙏 It's time for your medicine ({medicine}). Reply 1 when you have taken it. | |
| Daily check-in (we send, morning) | सुप्रभात {name} 🙏 आज आप कैसे हैं? ठीक हैं तो 1 लिखें, फ़ोन चाहिए तो 2। | Good morning {name} 🙏 Are you okay today? Reply 1 if you are okay, 2 if you want a call. | |
| Consent request (first message to a parent) | नमस्ते {name} 🙏 {caregiver} आपको WhatsApp पर दवा के हल्के रिमाइंडर भेजना चाहते हैं। शुरू करने के लिए 1 लिखें, नहीं चाहिए तो 2। कभी भी STOP लिखकर रोक सकते हैं। | Namaste {name} 🙏 {caregiver} would like to send you gentle medicine reminders on WhatsApp. Reply 1 to start, 2 for no. You can reply STOP any time. | |
| Consent request for the caregiver's own medicines | नमस्ते {name} 🙏 आपने अपनी दवाओं के रिमाइंडर के लिए अपना नंबर जोड़ा है। शुरू करने के लिए 1 लिखें, नहीं चाहिए तो 2। कभी भी STOP लिखकर रोक सकते हैं। | Namaste {name} 🙏 You added this number for your own medicine reminders on Chhaon. Reply 1 to start, 2 for no. You can reply STOP any time. | |
| Alerts opt-in (to a caregiver) | नमस्ते {name} 🙏 छाँव आपको यहाँ ज़रूरी अलर्ट भेजना चाहता है (जैसे माता-पिता की छूटी हुई दवा)। चालू करने के लिए 1 लिखें, नहीं चाहिए तो 2। कभी भी STOP लिखकर रोक सकते हैं। | Namaste {name} 🙏 Chhaon would like to send you important alerts here (for example a missed medicine of your parent). Reply 1 to turn alerts on, 2 for no. You can reply STOP any time. | |

### Replies we send back after the parent answered

| Message | Hindi | English meaning | OK / better wording |
|---|---|---|---|
| consentYes | धन्यवाद 🙏 अब आपको यहाँ दवा के हल्के रिमाइंडर मिलेंगे। रोकने के लिए कभी भी STOP लिखें। | Thank you 🙏 You will now get gentle medicine reminders here. Reply STOP any time to stop. | |
| consentNo | कोई बात नहीं। हम आपको कोई संदेश नहीं भेजेंगे। मन बदले तो START लिखें। | No problem. We will not send you any messages. If you change your mind, reply START. | |
| stopped | ठीक है, हमने सभी संदेश बंद कर दिए हैं। दोबारा चाहिए तो START लिखें। | Okay, we have stopped all messages. Reply START if you want them again. | |
| resumed | वापसी पर स्वागत है 🙏 रिमाइंडर फिर से शुरू हो गए हैं। | Welcome back 🙏 Reminders have started again. | |
| alertsOn | धन्यवाद 🙏 छाँव अलर्ट चालू हो गए हैं। जब किसी बात पर आपका ध्यान चाहिए होगा, हम यहाँ संदेश भेजेंगे। बंद करने के लिए कभी भी STOP लिखें। | Thank you 🙏 Chhaon alerts are on. We will message you here when something needs your attention. Reply STOP any time to turn them off. | |
| alertsOff | ठीक है, छाँव अलर्ट बंद हैं। आप इन्हें ऐप में दोबारा चालू कर सकते हैं। | Okay, Chhaon alerts are off. You can turn them on again in the app. | |
| alertsResumed | छाँव अलर्ट फिर से चालू हैं। बंद करने के लिए STOP लिखें। | Chhaon alerts are on again. Reply STOP to turn them off. | |
        return hi(lang)
                ? "धन्यवाद 🙏 दवा" + med + " नोट कर ली ✅"
                : "Thank you 🙏 Noted:" + (med.isEmpty() ? " your medicine" : med) + " ✅ | |
| doseNotYet | ठीक है 🙏 दवा लेने के बाद 1 लिखकर भेज दीजिए। | Okay 🙏 Please reply 1 once you have taken it. | |
| nextDoseHint | अगली दवा के लिए फिर से 1 लिखें। | Reply 1 again for the next medicine. | |
| checkinHint | आज आप ठीक हैं? ठीक हों तो 1 लिखें, फ़ोन चाहिए तो 2। | Are you okay today? Reply 1 if yes, 2 if you want a call. | |
| checkinOk | धन्यवाद 🙏 जानकर अच्छा लगा कि आप ठीक हैं। | Thank you 🙏 Glad you are okay. Have a lovely day. | |
| checkinCall | ठीक है 🙏 हमने आपके परिवार को बता दिया है। | Okay 🙏 We have let your family know. | |
| help | नमस्ते 🙏 जवाब देने के लिए: हाँ / ले ली / मैं ठीक हूँ के लिए 1 लिखें, नहीं / फ़ोन चाहिए के लिए 2 लिखें। रोकने के लिए STOP लिखें। | Namaste 🙏 To reply: type 1 for yes / taken / I am okay, 2 for no / call me. Type STOP to stop messages. | |
| consentHelp | नमस्ते 🙏 रिमाइंडर शुरू करने के लिए 1 लिखें, नहीं चाहिए तो 2। रोकने के लिए कभी भी STOP लिखें। | Namaste 🙏 Reply 1 to start reminders, 2 for no. STOP any time. | |
| doseTaken | धन्यवाद 🙏 दवा {medicine} नोट कर ली ✅ | Thank you 🙏 Noted: {medicine} ✅ | |

What parents can type (also in `docs/java-whatsapp-replies.md`): 1 = yes / taken / I am okay; 2 = no / call me; STOP; START. Words like `haan`, `le li`, `ले ली`, `theek hoon`, `phone karo`, `रुको`, `बंद करो` are understood too. Tell us any common Hindi reply we miss.

**Update 2026-11-05:** the five messages we start (dose reminder, daily check-in, consent request, own-number consent, alerts opt-in) were re-laid-out
for readability: a greeting line, one line of news, and the answers on their own lines with the number in bold (*1*). The Hindi consent text also
fixes a grammar mistake ("आपके परिवार ... चाहते हैं" is now "आपका परिवार ... चाहता है"). The table above still shows the old one-line wording of
these five; the current text is in `api-java/src/main/java/org/smartgurukul/chhaon/whatsapp/MessageTexts.java` and the unit test `MessageTextsTest.java`. Please review the new
layout rather than the old table rows.

## Part 2: new app screens (Hindi, `web/src/messages/hi.json`, added since the last push)

| Key | English | Hindi | OK / better wording |
|---|---|---|---|
| today.delayedTitle | Reminders are delayed | रिमाइंडर देर से हैं | |
| today.delayedBody | {call} has not been sent a reminder for: {names}. Chhaon is not sending messages right now, so please remind {call} yourself. | {call} को इन दवाइयों का रिमाइंडर अभी तक नहीं भेजा गया: {names}। छाँव अभी संदेश नहीं भेज पा रहा है, इसलिए कृपया {call} को खुद याद दिलाइए। | |
| today.staleTitle | Reminders have stopped | रिमाइंडर रुके हुए हैं | |
| today.staleBody | Chhaon's reminder system last ran {ago} ago, so reminders and alerts may not be going out. Please remind {call} yourself. | छाँव का रिमाइंडर सिस्टम {ago} पहले आख़िरी बार चला था, इसलिए रिमाइंडर और अलर्ट शायद न जा रहे हों। कृपया {call} को खुद याद दिलाइए। | |
| today.agoMinutes | {count, plural, one {# minute} other {# minutes}} | {count} मिनट | |
| today.agoHours | {count, plural, one {# hour} other {# hours}} | {count} घंटे | |
| today.agoDays | {count, plural, one {# day} other {# days}} | {count} दिन | |
| appearance.title | Appearance | थीम | |
| appearance.hint | Choose how Chhaon looks on this phone. | चुनिए कि इस फ़ोन पर छाँव कैसा दिखे। | |
| appearance.system | System | फ़ोन जैसा | |
| appearance.light | Light | हल्का | |
| appearance.dark | Dark | गहरा | |
| appearance.systemNote | Follows your phone's light or dark setting. | आपके फ़ोन की हल्की या गहरी सेटिंग के अनुसार बदलेगा। | |
| appearance.lightNote | Always light, even if your phone is set to dark. | हमेशा हल्का रहेगा, चाहे फ़ोन में गहरा चुना हो। | |
| appearance.darkNote | Always dark, easier on the eyes at night. | हमेशा गहरा रहेगा, रात में आँखों को आराम मिलता है। | |

## Part 3: the Hindi website page (https://chhaon.site/hi/, what Google shows in search results)

| Where | English | Hindi | OK / better wording |
|---|---|---|---|
| Search result title | Chhaon: WhatsApp medicine reminders for your parents | छाँव: माता-पिता के लिए WhatsApp पर दवा के रिमाइंडर | |
| Search result description | Gentle WhatsApp medicine reminders and a daily check-in for your parents, and an alert to you if they miss one. Parents install nothing. | माता-पिता को WhatsApp पर दवा के समय प्यार भरा रिमाइंडर और रोज़ सुबह हाल-चाल का संदेश। दवा छूटे तो आपको पता चल जाता है। माता-पिता को कुछ इंस्टॉल नहीं करना। | |

The rest of the Hindi page is the existing Hindi text of the home page (`web/public/home.html`, the `class="hi"` parts). The words people
actually type into Google in Hindi matter here: if a reviewer knows better search words (for example दवा याद दिलाने वाला ऐप), tell us.

## Part 4: tablet colour and shape (added 2026-11-05)

The dose reminder now names how the tablet looks, on its own line under the medicine name. Colour words change with the noun
(गोली is feminine, कैप्सूल masculine); please check that and the words themselves (`MessageTexts.pillLook` in the Java backend).

| Example | Hindi |
|---|---|
| blue round tablet | 🔵 नीली गोल गोली |
| blue capsule | 🔵 नीला कैप्सूल |
| yellow long tablet | 🟡 पीली लंबी गोली |
| red oval tablet | 🔴 लाल अंडाकार गोली |
| green, no shape chosen | 🟢 रंग: हरा |

The colours: सफ़ेद, पीला/पीली, नारंगी, गुलाबी, लाल, नीला/नीली, हरा/हरी, भूरा/भूरी, बैंगनी. App screen (`add.lookTitle`, `add.lookHint`,
`medicine.look`): "दवा कैसी दिखती है? (ज़रूरी नहीं)", "इससे Papa बिना पढ़े सही दवा पहचान सकेंगे। WhatsApp रिमाइंडर में भी यही लिखा आएगा।", "पहचान".

## Part 5: WhatsApp alerts to the family (added 2026-11-05, branch `family-alerts`)

Sent to a caregiver (son, daughter...) who turned on "Get alerts on WhatsApp" and confirmed by typing 1. The reader is an adult child, not
the parent. {name} is what the family calls the parent (Papa, Mummy), {medicine} a medicine name, {time} like सुबह 8:00. Code:
`AlertTexts.java` and `ReplyTexts.java` in the Java backend. Every alert starts with **छाँव अलर्ट 🔔** on its own line and ends with
**ये अलर्ट बंद करने के लिए STOP लिखें।** (English: "Chhaon alert 🔔" ... "Reply STOP to turn off these alerts.").

| Message | Hindi (middle part) | English meaning | OK / better wording |
|---|---|---|---|
| Dose missed | {name} ने {time} की दवा {medicine} लेने की पुष्टि नहीं की है। एक बार फ़ोन करके हाल पूछ लें। | {name} has not confirmed {medicine} (due {time}). You may want to give them a call. | |
| Several doses missed together | {name} ने {time} की 3 दवाइयाँ लेने की पुष्टि नहीं की है। एक बार फ़ोन करके हाल पूछ लें। | {name} has not confirmed 3 medicines (due {time}). | |
| Time when the caregiver lives in another time zone | सुबह 8:00 (उनके समय से) | 8:00 AM (their time) | |
| Check-in not answered | {name} ने आज सुबह के चेक-इन का जवाब नहीं दिया है। एक बार फ़ोन करके पूछ लें कि वे ठीक हैं। | {name} has not replied to today's morning check-in. You may want to call to make sure they are okay. | |
| Parent asked for a call | {name} ने आज के चेक-इन में फ़ोन करने को कहा है। जब हो सके, उन्हें फ़ोन कर लें। | {name} asked for a call in today's check-in. Please phone them when you can. | |
| Medicine running low | {name} की दवा {medicine} कम बची है। दोबारा मँगाने का समय है। | {name}'s medicine {medicine} is running low. Time to arrange a refill. | |
| Medicine run out | {name} की दवा {medicine} ख़त्म हो गई है। आज ही मँगा लें, ताकि कोई ख़ुराक न छूटे। | {name}'s medicine {medicine} has run out. Please arrange it today so that no dose is missed. | |
| Reminder could not be sent | हम {name} को {medicine} का रिमाइंडर नहीं भेज पाए। कृपया उन्हें ख़ुद याद दिला दें या फ़ोन कर लें। | We could not send the reminder for {medicine} to {name}. Please remind them yourself or give them a call. | |
| Parent typed STOP | {name} ने WhatsApp पर छाँव के संदेश बंद कर दिए हैं, इसलिए रिमाइंडर और चेक-इन रुके हुए हैं। | {name} chose to stop Chhaon messages on WhatsApp, so reminders and check-ins are paused. | |
| Parent said no to reminders | {name} ने WhatsApp पर छाँव के रिमाइंडर लेने से मना किया है, इसलिए कोई रिमाइंडर नहीं भेजा जाएगा। | {name} said no to Chhaon reminders on WhatsApp, so no reminders will be sent. | |
| When the parent's name is unknown | आपके माता-पिता | your parent | |

Replies to the caregiver's typed answer:

| Message | Hindi | English meaning | OK / better wording |
|---|---|---|---|
| alertsDeclined (typed 2) | कोई बात नहीं 🙏 छाँव अलर्ट बंद रहेंगे। मन बदले तो ऐप में फिर से चालू कर सकते हैं। | No problem 🙏 Chhaon alerts stay off. If you change your mind, you can turn them on in the app. | |
| alertsMayAskAgain (typed START after 2) | ठीक है 🙏 अब आप ऐप में WhatsApp अलर्ट फिर से चालू कर सकते हैं। | Okay 🙏 You can now turn on WhatsApp alerts again in the app. | |

App screen (`alerts.*` in `web/src/messages/hi.json`, changed because there are no buttons any more): "आपके जवाब का इंतज़ार",
"हमने {phone} पर संदेश भेजा है। WhatsApp खोलकर 1 लिखकर भेजें, तब अलर्ट शुरू होंगे।", "इस नंबर की पुष्टि के लिए हम एक संदेश भेजेंगे।
1 लिखकर भेजने के बाद ही अलर्ट शुरू होंगे।"

## Part 6: "Prepared for" on the doctor summary (added 2026-11-05)

The family can address the doctor summary (Family > Share with doctor) to their own doctor. The last three lines are printed at the top of
the sheet when it is in Hindi; {doctor} and {clinic} are what the family typed (often in English letters). `doctorSummary.*` in `hi.json`.

| Key | Hindi | English meaning | OK / better wording |
|---|---|---|---|
| forTitle | आपके डॉक्टर (ज़रूरी नहीं) | Your doctor (optional) | |
| forHint | सारांश में सबसे ऊपर दिखेगा। सिर्फ़ इसी फ़ोन में सेव रहता है। | Shown at the top of the summary. Saved only on this phone. | |
| doctorName | डॉक्टर का नाम | Doctor's name | |
| doctorPlaceholder | जैसे: अनीता शर्मा | For example: Anita Sharma | |
| clinicName | क्लिनिक या अस्पताल | Clinic or hospital | |
| clinicPlaceholder | जैसे: सिटी क्लिनिक, पुणे | For example: City Clinic, Pune | |
| preparedForBoth | किसके लिए: डॉ. {doctor}, {clinic} | Prepared for: Dr {doctor}, {clinic} | |
| preparedForDoctor | किसके लिए: डॉ. {doctor} | Prepared for: Dr {doctor} | |
| preparedForClinic | किसके लिए: {clinic} | Prepared for: {clinic} | |

Doctor QR cards (`web/public/doctors.html`): when a clinic name is typed, each card ends with the name and "For our patients · हमारे मरीज़ों के लिए".

## Still to come

Appointment reminders are not ported to the Java backend yet; their Hindi texts will need the same review once merged.

## Part 7: who gets the reminder: helper / "Me" (added 2026-11-05, branch `reminder-receiver`)

When the family chooses that someone other than the person gets the WhatsApp reminders (a maid, an attendant, Nani, the
husband, or the caregiver themself), the messages are written in the third person and always name the person. Code:
`api-java/.../whatsapp/ReceiverTexts.java`, `AlertTexts.alert(..., receiver)`; app texts in `web/src/messages/hi.json`.
Please check especially the possessives ("पापा की दवा", "पापा के दवा लेने के बाद") and the respectful "जी" added after a helper's name.

**WhatsApp (to the helper / caregiver)**

| What | Hindi | English | OK? |
|---|---|---|---|
| Reminder to a helper | नमस्ते सुनीता जी 🙏 / पापा की दवा का समय हो गया है 💊 *Metformin* / ⚪ सफ़ेद गोल गोली / दवा देने के बाद *1* या 👍 भेजिए ✅ | Namaste Sunita ji 🙏 / Time for Papa's medicine 💊 *Metformin* / ⚪ White round tablet / Reply *1* or 👍 once you have given it ✅ | |
| Reminder to the caregiver ("Me") | नमस्ते Ganesh 🙏 / पापा की दवा का समय हो गया है 💊 *Metformin* / पापा के दवा लेने के बाद *1* या 👍 भेजिए ✅ | Namaste Ganesh 🙏 / Time for Papa's medicine 💊 *Metformin* / Reply *1* or 👍 once Papa has taken it ✅ | |
| Consent, helper | नमस्ते सुनीता जी 🙏 / Ganesh आपको यहाँ WhatsApp पर पापा की दवाओं के रिमाइंडर भेजना चाहते हैं। / शुरू करने के लिए *1* लिखें ✅ / नहीं चाहिए तो *2* लिखें / कभी भी STOP लिखकर रोक सकते हैं। | Namaste Sunita ji 🙏 / Ganesh would like to send you reminders for Papa's medicines here on WhatsApp. / Reply *1* to start ✅ ... | |
| Consent, helper, no caregiver name | पापा का परिवार आपको यहाँ WhatsApp पर पापा की दवाओं के रिमाइंडर भेजना चाहता है। | Papa's family would like to send you reminders for Papa's medicines here on WhatsApp. | |
| Consent, caregiver | आपने पापा की दवाओं के रिमाइंडर यहाँ WhatsApp पर पाना चुना है। | You chose to get Papa's medicine reminders here on WhatsApp. | |
| After "1" to consent | धन्यवाद 🙏 अब आपको यहाँ पापा की दवा के रिमाइंडर मिलेंगे। रोकने के लिए कभी भी STOP लिखें। | Thank you 🙏 You will now get Papa's medicine reminders here. Reply STOP any time to stop. | |
| After "diya" / "1" | धन्यवाद 🙏 पापा की दवा Metformin नोट कर ली ✅ | Thank you 🙏 Noted for Papa: Metformin ✅ | |
| After "2" (helper) | ठीक है 🙏 पापा को दवा देने के बाद 1 लिखकर भेज दीजिए। | Okay 🙏 Please reply 1 once you have given Papa the medicine. | |
| After "2" (caregiver) | ठीक है 🙏 पापा के दवा लेने के बाद 1 लिखकर भेज दीजिए। | Okay 🙏 Please reply 1 once Papa has taken the medicine. | |

New words understood as "given": diya, de diya, de di, दिया, दे दिया, दे दी, given, gave; and, only as the whole message, ho gaya / हो गया / हो गई.

**WhatsApp alerts to the family (when a helper or a family member gets the reminders)**

| What | Hindi | English | OK? |
|---|---|---|---|
| Missed dose | पापा की दवा *Metformin* (सुबह 8:00) की पुष्टि नहीं हुई। रिमाइंडर Sunita (maid) को भेजा गया था। / एक बार फ़ोन करके पूछ लें। | Papa's medicine *Metformin* (due 8:00 AM) was not confirmed. The reminder went to Sunita (maid). / You may want to give them a call. | |
| Missed dose, to the caregiver who got it | ... रिमाइंडर आपको भेजा गया था। | ... The reminder went to you. | |
| Reminder not sent | हम पापा की दवा *Metformin* का रिमाइंडर Sunita (maid) को नहीं भेज पाए। / कृपया ख़ुद याद दिला दें या फ़ोन कर लें। | We could not send the reminder for Papa's medicine *Metformin* to Sunita (maid). / Please make sure it is given, or give them a call. | |
| STOP | पापा के रिमाइंडर पाने वाले Sunita (maid) ने WhatsApp पर छाँव के संदेश बंद कर दिए हैं, इसलिए पापा के रिमाइंडर रुके हुए हैं। | Sunita (maid), who gets Papa's reminders, stopped Chhaon messages on WhatsApp, so Papa's reminders are paused. | |
| Said no | Sunita (maid) ने WhatsApp पर पापा की दवा के रिमाइंडर लेने से मना किया है, इसलिए कोई रिमाइंडर नहीं भेजा जाएगा। | Sunita (maid) said no to getting Papa's medicine reminders on WhatsApp, so no reminders will be sent. | |

**App (`profile`, `today`, `emergency` in hi.json)**

| Key | Hindi | English | OK? |
|---|---|---|---|
| profile.receiverTitle | WhatsApp रिमाइंडर किसे मिलें? | Who gets the WhatsApp reminders? | |
| profile.receiverMe / receiverHelper | मुझे / किसी और को | Me / Someone else | |
| profile.receiverHintPerson | रिमाइंडर {call} के अपने WhatsApp पर जाएँगे। दवा लेने के बाद {call} 1 लिखकर जवाब देंगे। | Reminders go to {call}'s own WhatsApp. {call} replies 1 after taking the medicine. | |
| profile.receiverHintMe | रिमाइंडर आपके WhatsApp पर {call} के नाम से आएँगे। {call} के दवा लेने के बाद 1 लिखें। तब रोज़ का चेक-इन नहीं होगा। | Reminders come to your WhatsApp, with {call}'s name. Reply 1 once {call} has taken the medicine. There is no daily check-in then. | |
| profile.receiverHintHelper | घर की सहायिका, अटेंडेंट, आया या किसी रिश्तेदार के लिए, जो दवा देते हैं। दवा देने के बाद वे 1 लिखेंगे। तब रोज़ का चेक-इन नहीं होगा। | For a maid, attendant, nanny or relative who gives the medicine. They reply 1 once it is given. There is no daily check-in then. | |
| profile.receiverHintOther | {call} के रिमाइंडर अभी {name} को जाते हैं। बदलने के लिए कोई दूसरा विकल्प चुनें। | Reminders for {call} now go to {name}. To change this, choose another option. | |
| profile.helperName / helperRelation | उनका नाम / वे कौन हैं? (ज़रूरी नहीं) | Their name / Who are they? (optional) | |
| profile.helperRelationPlaceholder | सहायिका, नानी, आया | maid, Nani, nanny | |
| profile.whatsappHintHelper | हम पहले इस नंबर से WhatsApp पर अनुमति माँगेंगे। | We will first ask this number to agree on WhatsApp. | |
| profile.sharedNumberMe | यही नंबर आपकी अपनी दवाओं के लिए भी है। कोई बात नहीं: हर रिमाइंडर में लिखा होगा कि दवा किसकी है। | This is also the number for your own medicines. That is fine: each reminder says whose medicine it is. | |
| today.receiverOther | {call} के WhatsApp रिमाइंडर {name} को जाते हैं। रोज़ का चेक-इन नहीं होता। | WhatsApp reminders for {call} go to {name}. There is no daily check-in. | |
| today.consentPendingTitleReceiver | {name} की सहमति का इंतज़ार | Waiting for {name} to agree | |
| today.consentPendingReceiver | हमने {name} को {call} की दवा के रिमाइंडर भेजने की अनुमति माँगने के लिए WhatsApp संदेश भेजा है। ... | We sent {name} a WhatsApp message asking for permission to send {call}'s medicine reminders. ... | |
| today.consentPendingMe | हमने आपके WhatsApp पर संदेश भेजा है। {call} की दवा के रिमाइंडर शुरू करने के लिए वहाँ 1 लिखें। | We sent a message to your WhatsApp. Reply 1 there to start getting {call}'s medicine reminders. | |
| today.consentStoppedReceiver | {name} ने WhatsApp संदेश बंद कर दिए हैं, इसलिए {call} के रिमाइंडर रुके हुए हैं। {name} से WhatsApp पर START लिखने को कहें। | {name} stopped WhatsApp messages, so {call}'s reminders are paused. Ask {name} to reply START on WhatsApp. | |
| emergency.helperPhone | दवा देने वाले | GIVES THE MEDICINES | |

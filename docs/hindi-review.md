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

## Part 7: care type and pregnancy (added 2026-11-05, branch `care-type`)

A family can now choose "Pregnancy" when adding a person. WhatsApp texts go to the pregnant woman herself (so Hindi verbs are feminine:
"महसूस कर रही हैं"). Week numbers use ordinals: पहला, दूसरा, तीसरा, चौथा, 5वाँ, छठा, 7वाँ ... 24वाँ (`MessageTexts.hindiOrdinal`).

WhatsApp (`api-java/.../whatsapp/MessageTexts.java`, `pregnancyDoseReminder` / `pregnancyCheckin`):

| Text | Hindi | English meaning | OK / better wording |
|---|---|---|---|
| Greeting before 12:00 | सुप्रभात शिवानी ☀️ | Good morning Shivani | |
| Greeting 12:00-17:00 | नमस्ते शिवानी 🌤️ | Good afternoon Shivani | |
| Greeting after 17:00 | शुभ संध्या शिवानी 🌙 | Good evening Shivani | |
| Week line | आज आपका 24वाँ हफ़्ता है 💛 | Week 24 today | |
| Medicine line | *आयरन + फ़ोलिक एसिड* लेने का समय हो गया है 💊 | Time for your Iron + folic acid | |
| No medicine name | दवा लेने का समय हो गया है 💊 | Time for your medicine | |
| Reply line (unchanged) | लेने के बाद *1* लिखकर भेजिए ✅ | Reply 1 once taken | |
| Check-in question | आज आप कैसा महसूस कर रही हैं? | How are you feeling today? | |
| Check-in answers (unchanged) | ठीक हैं तो *1* लिखें 😊 / फ़ोन चाहिए तो *2* लिखें 📞 | Reply 1 if fine / 2 for a call | |

App (`web/src/messages/hi.json`):

| Key | Hindi | English meaning | OK / better wording |
|---|---|---|---|
| profile.careType | आप किसकी देखभाल कर रहे हैं? | Who are you caring for? | |
| profile.careOlder / carePregnant / careChild | बुज़ुर्ग माता-पिता / गर्भावस्था / बच्चा | Older parent / Pregnancy / Child | |
| profile.careSoon | जल्द आ रहा है | Coming soon | |
| profile.titleNewPregnant | होने वाली माँ को जोड़ें | Add the mother-to-be | |
| profile.titleEditPregnant | उनकी जानकारी | Her details | |
| profile.subtitlePregnant | गर्भावस्था में दवा और जाँच के रिमाइंडर | Medicine reminders and check-ups during the pregnancy | |
| profile.namePregnant / errNamePregnant | उनका नाम / कृपया उनका नाम लिखें। | Her name / Please enter her name. | |
| profile.whatsappHintPregnant | वह नंबर जिस पर वे WhatsApp इस्तेमाल करती हैं। रिमाइंडर इसी नंबर पर जाएँगे। | The number she uses on WhatsApp. Reminders go to this number. | |
| profile.timezonePregnant / timezoneHintPregnant | उनका टाइम ज़ोन / रिमाइंडर उनके इस समय पर जाएँगे। | Her time zone / Reminders go out at this time of day for her. | |
| profile.callNameHintPregnant | अलर्ट में और उन्हें मिलने वाले संदेशों में यही नाम इस्तेमाल होगा, जैसे शिवानी। | Used in alerts and in the messages she receives, e.g. Shivani. | |
| profile.notePregnant | रिमाइंडर भेजने से पहले हम उनसे WhatsApp पर सहमति माँगेंगे। | We'll ask her for permission before sending reminders. | |
| profile.dueDate | डिलीवरी की संभावित तारीख़ | Due date | |
| profile.dueDateHint | डॉक्टर की बताई तारीख़। इससे हम गर्भावस्था का हफ़्ता दिखाते हैं। | The date your doctor gave. We show the week from it. | |
| profile.errDueDate | कृपया अगले 10 महीनों के अंदर की तारीख़ चुनें। | Please pick a due date within the next 10 months. | |
| pregnancy.cardTitle | गर्भावस्था | Pregnancy | |
| pregnancy.week | हफ़्ता {week} | Week {week} | |
| pregnancy.trimester1/2/3 | पहली / दूसरी / तीसरी तिमाही | 1st / 2nd / 3rd trimester | |
| pregnancy.daysToGo | {count} दिन बाकी | {count} days to go | |
| pregnancy.dueParen | (तारीख़ {date}) | (due {date}) | |
| pregnancy.dueToday / duePassed / dueOnly | आज डिलीवरी की संभावित तारीख़ है / डिलीवरी की संभावित तारीख़ {date} थी / डिलीवरी की संभावित तारीख़: {date} | The due date is today / was {date} / Due date: {date} | |
| pregnancy.checksLink | आम जाँचें | Usual checks | |
| pregnancy.checksTitle | आम जाँचें — डॉक्टर से पक्का करें | Common checks — confirm with your doctor | |
| pregnancy.checksNote | यह एक आम सूची है, डॉक्टरी सलाह नहीं। आपके डॉक्टर इन्हें अलग तरह से तय कर सकते हैं। अपने आप कुछ नहीं जुड़ता: जोड़ें दबाने पर अपॉइंटमेंट बनता है, जिसे आप बदल सकते हैं। | A general list, not medical advice ... | |
| pregnancy.weeks / everyVisit | हफ़्ता {from}–{to} / हर जाँच पर | Weeks {from}–{to} / At every visit | |
| pregnancy.windowNow / windowPast | अभी / समय निकल गया | Now / Time has passed | |
| pregnancy.addCheck / addCheckLabel | जोड़ें / {name} को अपॉइंटमेंट में जोड़ें | Add / Add {name} as an appointment | |
| pregnancy.needDueDate | ये जाँचें कब होती हैं, यह देखने के लिए उनकी जानकारी में डिलीवरी की तारीख़ डालें। | Add the due date to see when these usually happen. | |
| pregnancy.supplementsTitle | आम सप्लीमेंट | Common supplements | |
| pregnancy.supplementsNote | इससे सिर्फ़ नाम भरता है। खुराक वही डालें जो डॉक्टर ने लिखी है। | Fills in the name only. Use the dose your doctor prescribed. | |
| whatsapp.msg.pregnancyWeek | आज आपका {ordinal} हफ़्ता है 💛 | Week {week} today | |
| whatsapp.msg.feelingToday | आज आप कैसा महसूस कर रही हैं? | How are you feeling today? | |
| whatsapp.sentInPregnant | {call} को ये संदेश {language} में मिलते हैं। आप यह उनकी जानकारी में बदल सकते हैं। | ... You can change this in her details. | |
| parents.add (changed for everyone) | किसी और को जोड़ें | Add another person (was: एक और माता-पिता जोड़ें) | |

Check names and supplement names (`web/src/lib/pregnancyChecks.ts`; the supplement name is what the WhatsApp reminder says):
पहली जाँच (रजिस्ट्रेशन) और खून की जाँचें, शुरुआती (डेटिंग) स्कैन, NT स्कैन, एनॉमली स्कैन, शुगर की जाँच (GTT), ग्रोथ स्कैन, BP और वज़न;
आयरन + फ़ोलिक एसिड, कैल्शियम + विटामिन D3, फ़ोलिक एसिड.

## Still to come

Appointment reminders are not ported to the Java backend yet; their Hindi texts will need the same review once merged.

## Part 8: child profiles and vaccines (added 2026-11-05, branch `child-vaccines`)

The Vaccines screen of a child's profile, the guardian confirmation when a child is added, and the in-app vaccine reminder.
`vaccines.*` in `web/src/messages/hi.json`. {call} is what the family calls the child, {date} a date, {vaccine} a vaccine name, {n} a number.
Medical wording ("as advised by your doctor", the IAP notes) must stay exactly as careful as the English: no "required", no "optional".

| Key | Hindi | English meaning | OK / better wording |
|---|---|---|---|
| title | टीके | Vaccines | |
| subtitle | {call} के टीकों की योजना | {call}'s vaccination plan | |
| linkTitle | टीके | Vaccines | |
| linkSub | {call} के टीकों की तारीख़ें, डॉक्टर की सलाह के अनुसार | {call}'s vaccine dates, as advised by your doctor | |
| nextNone | {call} के टीकों की योजना बनाएँ | Set up {call}'s vaccine plan | |
| setupTitle | टीकों की योजना बनाएँ | Set up the vaccine plan | |
| editDobTitle | जन्म की तारीख़ | Date of birth | |
| setupBody | हम इंडियन एकेडमी ऑफ़ पीडियाट्रिक्स (IAP) की सूची से, जन्म की तारीख़ से गिनकर तारीख़ें सुझाते हैं। हर तारीख़ आप डॉक्टर की सलाह के अनुसार बदल सकते हैं। | We suggest dates from the Indian Academy of Pediatrics (IAP) schedule, counted from the date of birth. You can change every date to what your doctor advises. | |
| dob | जन्म की तारीख़ | Date of birth | |
| dobHint | टीकों की तारीख़ें सुझाने के लिए ज़रूरी। | Needed to suggest the vaccine dates. | |
| errDob | कृपया जन्म की तारीख़ डालें (आगे की तारीख़ नहीं, 18 साल से कम)। | Please enter the date of birth (not in the future, under 18 years). | |
| guardian | मैं इस बच्चे का माता-पिता या क़ानूनी अभिभावक हूँ | I am the parent or legal guardian of this child | |
| guardianHint | भारतीय क़ानून (DPDP एक्ट) के अनुसार बच्चे की सेहत की जानकारी सेव करने से पहले यह पुष्टि ज़रूरी है। इसे छाँव पर सिर्फ़ आपका परिवार देख सकता है। कोई विज्ञापन नहीं, कोई ट्रैकिंग नहीं। | Indian law (DPDP Act) asks for this before we save a child's health details. Only your family on Chhaon can see them. No ads, no tracking. | |
| errGuardian | कृपया पुष्टि करें कि आप माता-पिता या क़ानूनी अभिभावक हैं। | Please confirm that you are the parent or legal guardian. | |
| makePlan | टीकों की योजना बनाएँ | Make the vaccine plan | |
| saveDob | सेव करें | Save | |
| making | सेव हो रहा है… | Saving… | |
| dobLine | जन्म: {date} | Born {date} | |
| changeDob | बदलें | Change | |
| safety | ये तारीख़ें इंडियन एकेडमी ऑफ़ पीडियाट्रिक्स की {edition} सूची से सुझाई गई हैं। हर तारीख़ डॉक्टर की सलाह के अनुसार है। {call} को कौन-से टीके और कौन-से ब्रांड लगें, यह आपके डॉक्टर तय करते हैं। | Suggested dates from the {edition} schedule of the Indian Academy of Pediatrics. Every date is as advised by your doctor. Your doctor decides which vaccines and brands {call} gets. | |
| notReviewed | इस सूची को अभी किसी बाल रोग विशेषज्ञ ने नहीं जाँचा है। | This list has not been checked by a paediatrician yet. | |
| overdue | सुझाई गई तारीख़ निकल गई | Past the suggested date | |
| dueSoon | अगले 30 दिनों में | In the next 30 days | |
| later | बाद में | Later | |
| done | हो चुके | Done | |
| showDone | हो चुके टीके दिखाएँ ({count}) | Show done ({count}) | |
| hideDone | हो चुके टीके छिपाएँ | Hide done | |
| emptyNext | अगले 30 दिनों में कोई टीका नहीं। | Nothing in the next 30 days. | |
| dueOn | तारीख़: {date} | Due {date} | |
| asAdvised | डॉक्टर की सलाह के अनुसार। | As advised by your doctor. | |
| givenOn | लगा: {date} | Given {date} | |
| notGiven | नहीं लगा | Not given | |
| changedByFamily | तारीख़ परिवार ने बदली | date changed by the family | |
| dose | खुराक {n} | Dose {n} | |
| booster | बूस्टर | Booster | |
| boosterN | बूस्टर {n} | Booster {n} | |
| age.birth | जन्म के समय | At birth | |
| age.weeks | {n} हफ़्ते | {n} weeks | |
| age.months | {n} महीने | {n} months | |
| age.monthsRange | {from}-{to} महीने | {from}-{to} months | |
| age.years | {n} साल | {n} years | |
| age.yearsRange | {from}-{to} साल | {from}-{to} years | |
| note.hepb4 | IAP: कॉम्बिनेशन वैक्सीन के हिस्से के रूप में हेपेटाइटिस बी की चौथी खुराक सुरक्षित है और दी जा सकती है। डॉक्टर से पूछें कि यह आप पर लागू है या नहीं। | IAP: a 4th dose of Hepatitis B is safe and permitted when it is part of a combination vaccine. Ask your doctor whether it applies. | |
| note.rotavirus3 | IAP: 2 खुराक वाले रोटावायरस टीके RV1 (GSK) में इसकी ज़रूरत नहीं; बाक़ी ब्रांड में 3 खुराक होती हैं। डॉक्टर से पूछें। | IAP: not needed with the 2-dose rotavirus vaccine RV1 (GSK); other brands have 3 doses. Ask your doctor. | |
| note.influenzaYearly | IAP: इसके बाद फ़्लू का टीका हर साल, मानसून से पहले, 5 साल की उम्र तक दोहराया जाता है। डॉक्टर से पूछें। | IAP: after this, the flu vaccine is repeated every year, before the monsoon, till 5 years of age. Ask your doctor. | |
| note.hepa1 | IAP: लाइव (जीवित) हेपेटाइटिस ए टीके की एक खुराक; इनएक्टिवेटेड टीके की दो खुराक होती हैं। | IAP: one dose for the live hepatitis A vaccine; the inactivated vaccine needs two doses. | |
| note.hepa2 | IAP: सिर्फ़ इनएक्टिवेटेड हेपेटाइटिस ए टीके के लिए। डॉक्टर से पूछें कि कौन-सा टीका लगा था। | IAP: only for the inactivated hepatitis A vaccine. Ask your doctor which one was given. | |
| note.hpv2dose | IAP: 9 से 14 साल में, 6 महीने के अंतर पर दो खुराक, लड़कियों और लड़कों दोनों के लिए। | IAP: from 9 to 14 years, two doses 6 months apart, for girls and boys. | |
| note.tdap | IAP: 10 साल पर लगता है, भले ही Tdap पहले DTP बूस्टर के रूप में लग चुका हो। डॉक्टर से पूछें। | IAP: given at 10 years even if Tdap was given earlier as the DTP booster. Ask your doctor. | |
| markGiven | लग गया | Mark as given | |
| givenDate | किस तारीख़ को लगा | Given on | |
| errGivenDate | कृपया वह तारीख़ चुनें जब टीका लगा (आगे की तारीख़ नहीं)। | Please pick the date it was given (not in the future). | |
| markNotGiven | नहीं लगा | Mark as not given | |
| reopen | अभी नहीं लगा (फिर से खोलें) | Mark as not done yet | |
| changeDate | तारीख़ | Due date | |
| saveDate | तारीख़ सेव करें | Save date | |
| useSuggested | सुझाई गई तारीख़ लें ({date}) | Use the suggested date ({date}) | |
| notes | नोट (ज़रूरी नहीं) | Notes (optional) | |
| notesPlaceholder | जैसे: अस्पताल, ब्रांड, बैच | For example: hospital, brand, batch | |
| saveNotes | नोट सेव करें | Save notes | |
| close | बंद करें | Close | |
| saved | सेव हो गया | Saved | |
| errSave | सेव नहीं हो पाया। कृपया फिर से कोशिश करें। | Could not save. Please try again. | |
| loadFailed | टीके लोड नहीं हो पाए। कृपया फिर से कोशिश करें। | Could not load the vaccines. Please try again. | |
| notChild | टीकों की योजना सिर्फ़ बच्चे की प्रोफ़ाइल के लिए है। | The vaccine plan is only for a child's profile. | |
| notifDue | {call}: {vaccine} की तारीख़ {date} है, डॉक्टर की सलाह के अनुसार। | {call}: {vaccine} is due on {date}, as advised by your doctor. | |
| notifDueGeneric | {call} का एक टीका जल्द है, डॉक्टर की सलाह के अनुसार। | A vaccine for {call} is due soon, as advised by your doctor. | |

Vaccine names (shown in Hindi mode; from `api-java/src/main/resources/vaccines/iap-acvip-2023.json`, also read by a paediatrician):

| Code | Hindi | English | OK / better wording |
|---|---|---|---|
| bcg | बीसीजी (BCG) | BCG | |
| opv | ओपीवी (मुँह से पोलियो की दवा) | OPV (oral polio) | |
| hepb | हेपेटाइटिस बी | Hepatitis B | |
| dtp | डीटीपी (DTwP / DTaP: डिप्थीरिया, टिटनेस, काली खाँसी) | DTwP / DTaP (diphtheria, tetanus, whooping cough) | |
| ipv | आईपीवी (पोलियो का इंजेक्शन) | IPV (polio injection) | |
| hib | हिब (Hib) | Hib | |
| rotavirus | रोटावायरस | Rotavirus | |
| pcv | पीसीवी (न्यूमोकोकल) | PCV (pneumococcal) | |
| influenza | इन्फ़्लुएंज़ा (फ़्लू) | Influenza (flu) | |
| tcv | टाइफ़ाइड कॉन्जुगेट वैक्सीन | Typhoid conjugate vaccine | |
| mmr | एमएमआर (खसरा, गलसुआ, रूबेला) | MMR (measles, mumps, rubella) | |
| hepa | हेपेटाइटिस ए | Hepatitis A | |
| varicella | वैरिसेला (चिकनपॉक्स) | Varicella (chickenpox) | |
| hpv | एचपीवी (HPV) | HPV | |
| tdap | टीडैप (Tdap) | Tdap | |
| td | टीडी (Td) | Td | |

## Part 9: who gets the reminder: helper / "Me" (added 2026-11-05, branch `reminder-receiver`)

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

## Part 10: pregnancy timeline home on Today (added 2026-11-05, branch `f7-pregnancy-home`)

When the selected person is a pregnancy, Today shows her own home: week hero, today's supplements, a checks timeline. Keys in
`pregnancyHome` (hi.json). It also reuses the Part 7 `pregnancy` texts (trimester, days to go, week windows, "अभी", "जोड़ें").

| Key | Hindi | English | OK? |
|---|---|---|---|
| pregnancyHome.label | गर्भावस्था | PREGNANCY | |
| pregnancyHome.weeksWord | हफ़्ता / हफ़्ते ("24 हफ़्ते · दूसरी तिमाही") | week / weeks ("24 weeks · 2nd trimester") | |
| pregnancyHome.progress | 40 में से हफ़्ता {week} | Week {week} of 40 | |
| pregnancyHome.dueOn | संभावित तारीख़ {date} | Due {date} | |
| pregnancyHome.noDueTitle | हफ़्ते देखने के लिए डिलीवरी की तारीख़ डालें | Add the due date to see the weeks | |
| pregnancyHome.noDueBody | यह तारीख़ डॉक्टर बताते हैं। उनकी जानकारी में डालने के लिए दबाएँ। | The doctor gives this date. Tap to add it to her details. | |
| pregnancyHome.todayTitle | आज | Today | |
| pregnancyHome.noMedicines | अभी कोई दवा या सप्लीमेंट नहीं जोड़ा गया है। | No medicines or supplements added yet. | |
| pregnancyHome.timelineTitle | जाँचों की टाइमलाइन | Checks timeline | |
| pregnancyHome.done | हो गई ("हो गई · हफ़्ता 20") | Done ("Done · Week 20") | |
| pregnancyHome.reportInReports | रिपोर्ट, रिपोर्ट टैब में | Report in Reports | |
| pregnancyHome.delivery | डिलीवरी | Delivery | |
| pregnancyHome.footer | आम जाँचें — डॉक्टर से पक्का करें। यह डॉक्टरी सलाह नहीं है। | Common checks — confirm with your doctor. Not medical advice. | |

## Part 11: reply with a 👍 reaction (added 2026-11-05, branch `f6-thumbs-reaction`)

A parent (or helper) can now long-press our message and tap 👍 instead of typing 1. The reply line of each question says so.
"लिखें" (write) became "भेजें" (send) where 👍 was added, because an emoji is sent, not written. Please check that this reads
naturally and that "*1* या 👍 भेजें" is clear to an older reader.

| What | Hindi (new) | English (new) | OK? |
|---|---|---|---|
| Dose reminder (and pregnancy reminder), last line | लेने के बाद *1* या 👍 भेजिए ✅ | Reply *1* or 👍 once you have taken it ✅ | |
| Daily check-in (and pregnancy check-in), "fine" line | ठीक हैं तो *1* या 👍 भेजें 😊 | Reply *1* or 👍 if you are fine 😊 | |
| Consent request (parent, "Me", helper) | शुरू करने के लिए *1* या 👍 भेजें ✅ | Reply *1* or 👍 to start ✅ | |

Unchanged: "फ़ोन चाहिए तो *2* लिखें 📞", "नहीं चाहिए तो *2* लिखें", the answers we send back, and the caregiver's alert question
(a reaction does not answer that one).

## Part 12: weekly summary to the family (added 2026-11-05, branch `f3-weekly-summary`)

Sent on Sunday morning to a caregiver who turned on WhatsApp alerts (the reader is the adult child, not the person). One message
covers everyone they care for. Code: `api-java/.../whatsapp/WeeklySummaryTexts.java`; app card: `weekly.*` in `web/src/messages/hi.json`.
Please check especially the short day and month names and "छूटी" / "किसने दी" as line labels.

**WhatsApp**

| What | Hindi | English | OK? |
|---|---|---|---|
| Header (one person) | छाँव साप्ताहिक 🗓️ · मम्मी | Chhaon weekly 🗓️ · Mummy | |
| Period | 30 अक्टू – 5 नव | 30 Oct – 5 Nov | |
| Pregnancy week | हफ़्ता 24 | Week 24 | |
| Confirmed | दवा की पुष्टि: 28 में से 26 | Medicines confirmed: 26 of 28 | |
| Missed | छूटी: Metformin मंगल रात, गुरु रात | Missed: Metformin Tue night, Thu night | |
| More missed | (+3 और) | (+3 more) | |
| Given by | किसने दी: Sunita 24 · आप 2 | Given by: Sunita 24 · you 2 | |
| Given in the app, name unknown | परिवार | family | |
| Check-ins | चेक-इन का जवाब: 7 में से 6 | Check-ins answered: 6 of 7 | |
| Refill | जल्द मँगाएँ: Metformin (6 बची) / (ख़त्म) | Refill soon: Metformin (6 left) / (none left) | |
| Next visit | अगली मुलाक़ात: Dr Mehta, मंगल 11 नव, सुबह 11:00 | Next visit: Dr Mehta, Tue 11 Nov, 11:00 AM | |
| Next vaccine | अगला टीका: MMR (ख़ुराक 1), मंगल 11 नव / (बूस्टर 1) | Next vaccine: MMR (dose 1), Tue 11 Nov / (booster 1) | |
| More people | +2 और, ऐप में देखें। | +2 more in the app. | |
| Footer | पूरी जानकारी छाँव ऐप में। / ये अलर्ट बंद करने के लिए STOP लिखें। | Full details in the Chhaon app. / Reply STOP to turn off these alerts. | |
| Days | सोम मंगल बुध गुरु शुक्र शनि रवि | Mon ... Sun | |
| Months | जन फ़र मार्च अप्रैल मई जून जुलाई अग सित अक्टू नव दिस | Jan ... Dec | |
| Parts of the day | सुबह / दोपहर / शाम / रात | morning / afternoon / evening / night | |

**App ("This week" card on the Family tab)**

| Key | Hindi | English | OK? |
|---|---|---|---|
| weekly.title | इस हफ़्ते | This week | |
| weekly.quiet | पिछले 7 दिनों में {call} के लिए दिखाने को कुछ नहीं है। | Nothing to show for {call} in the last 7 days. | |
| weekly.checkinsValue | {total} में से {answered} का जवाब मिला | {answered} of {total} answered | |
| weekly.note | पिछले 7 दिन। जिन सदस्यों ने WhatsApp अलर्ट चालू किए हैं, उन्हें यह रविवार सुबह भी मिलता है। | The last 7 days. Family members with WhatsApp alerts on also get this on Sunday morning. | |
| weekly.missed / givenBy / refill / nextVisit / nextVaccine | छूटी / किसने दी / जल्द मँगाएँ / अगली मुलाक़ात / अगला टीका | Missed / Given by / Refill soon / Next visit / Next vaccine | |

## Part 13: the child home on Today (added 2026-11-05, branch `f8-vaccine-card`)

When the selected person is a child, Today starts with the child's age, who gets the medicine reminders, the next vaccine, a small
"vaccine card" of the current age band and two quick actions. Code: `web/src/screens/ChildHome.tsx`, texts under `childHome` in
`web/src/messages/hi.json`. Please check especially: the kicker "उम्र · 5 महीने" (English says "Child · 5 months"; "बच्चा" was avoided
because it is masculine), "देखना है" for a dose whose suggested date has passed, "लग गया / नहीं लगा" for given / not given, and
"शिशु रोग विशेषज्ञ" for paediatrician. Vaccine short names on the tiles (BCG, OPV, HepB, DTP, Rota, Flu ...) stay in Latin letters in
both languages, as on Indian vaccination cards.

| Key | Hindi | English | OK? |
|---|---|---|---|
| childHome.kicker | उम्र · {age} | Child · {age} | |
| childHome.kickerNoAge | बच्चे की प्रोफ़ाइल | Child | |
| childHome.newborn | नवजात | Newborn | |
| childHome.days | {count} दिन | {count, plural, one {# day} other {# days}} | |
| childHome.weeks | {count, plural, one {# हफ़्ता} other {# हफ़्ते}} | {count, plural, one {# week} other {# weeks}} | |
| childHome.months | {count, plural, one {# महीना} other {# महीने}} | {count, plural, one {# month} other {# months}} | |
| childHome.years | {count} साल | {count, plural, one {# year} other {# years}} | |
| childHome.receiverOther | दवा के रिमाइंडर {name} को जाते हैं | Medicine reminders go to {name} | |
| childHome.receiverYou | दवा के रिमाइंडर आपको आते हैं | Medicine reminders come to you | |
| childHome.receiverSomeone | किसी और | someone else | |
| childHome.nextLabel | अगला टीका | Next vaccine | |
| childHome.dueToday | आज · {date} | Today · {date} | |
| childHome.dueTomorrow | कल · {date} | Tomorrow · {date} | |
| childHome.inDays | {count} दिन में · लगभग {date} | In {count} days · around {date} | |
| childHome.checkLabel | देखना है | To check | |
| childHome.checkTitle | {count, plural, one {# टीका देखना है} other {# टीके देखने हैं}} | {count, plural, one {# vaccine to check} other {# vaccines to check}} | |
| childHome.checkBody | सुझाई गई तारीख़ निकल गई है। सूची खोलकर हर टीके के लिए बताइए: लग गया या नहीं लगा। | One: "Its suggested date has passed. Open the list and mark it as given or not given." / more: "Their suggested dates have passed. Open the list and mark each one as given or not given." | |
| childHome.checkNext | अगला: {vaccine} · लगभग {date} | Next: {vaccine} · around {date} | |
| childHome.checkAction | अभी देखें | Check now | |
| childHome.setupBody | जन्म की तारीख़ डालिए, हम तारीख़ें सुझाएँगे। | Add the date of birth and we suggest the dates. | |
| childHome.setupAction | शुरू करें | Set up | |
| childHome.allDone | प्लान में और कोई टीका नहीं है | No more vaccines in the plan | |
| childHome.retry | फिर से कोशिश करें | Try again | |
| childHome.band0 | पहला साल | First year | |
| childHome.band1 | 1-2 साल | 1-2 years | |
| childHome.band2 | 2-6 साल | 2-6 years | |
| childHome.band3 | 7-18 साल | 7-18 years | |
| childHome.cardCount | {total} में से {given} लग गए | {given} of {total} given | |
| childHome.seeAll | सब देखें | See all | |
| childHome.legendLabel | रंगों का मतलब | What the colours mean | |
| childHome.legendGiven | लग गया | Given | |
| childHome.legendNext | अगला | Next | |
| childHome.legendCheck | देखना है | To check | |
| childHome.legendLater | बाद में | Later | |
| childHome.legendNotGiven | नहीं लगा | Not given | |
| childHome.actionPhoto | अस्पताल के कार्ड की फ़ोटो | Photo of the hospital card | |
| childHome.actionPhotoSub | रिपोर्ट में रखें | Keep it in Reports | |
| childHome.actionRemind | 3 दिन पहले याद दिलाना | Reminder 3 days before | |
| childHome.actionRemindSub | ऐप में, परिवार के लिए | In the app, for the family | |
| childHome.remindTitle | टीके के रिमाइंडर | Vaccine reminders | |
| childHome.remindBody | हर टीके से 3 दिन पहले, छाँव पर {call} के परिवार के सभी लोगों को यहाँ ऐप में (घंटी में) रिमाइंडर मिलता है। | 3 days before each vaccine, everyone in {call}'s family on Chhaon gets a reminder here in the app (the bell). | |
| childHome.remindWhatsAppName | {name} को टीके के रिमाइंडर अभी WhatsApp पर नहीं जाते। दवा के रिमाइंडर पहले की तरह चलते रहेंगे। | {name} does not get vaccine reminders on WhatsApp yet. Medicine reminders are not affected. | |
| childHome.remindWhatsApp | टीके के रिमाइंडर अभी WhatsApp पर नहीं जाते। | Vaccine reminders do not go on WhatsApp yet. | |
| childHome.remindDates | टीकों वाली स्क्रीन पर हर तारीख़ बदली जा सकती है, जैसी डॉक्टर सलाह दें। | You can change any date on the Vaccines screen, as advised by your doctor. | |
| childHome.openVaccines | टीके खोलें | Open vaccines | |
| childHome.close | बंद करें | Close | |
| childHome.source | {edition} टीकाकरण सूची | {edition} schedule | |
| childHome.sourceUnreviewed | {edition} टीकाकरण सूची · शिशु रोग विशेषज्ञ से जाँच होनी बाकी | {edition} schedule · to be checked by a paediatrician | |

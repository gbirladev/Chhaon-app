# WhatsApp templates: ready to submit

> **Name change:** the app is now called **Chhaon** (छाँव in Hindi). Meta still holds the first versions of these templates with the name CareLink. After they are approved, edit the text of these five in WhatsApp Manager to match this file: `carelink_consent_request_v2`, `carelink_alert_optin`, `carelink_daily_checkin` (en + hi), and the Hindi `carelink_alert_call_requested` and `carelink_alert_parent_stopped`. The template names themselves stay as they are.

Copy each block into **WhatsApp Manager > Message templates > Create template** (business.facebook.com). Choose category **Utility**, template type **Default**, then add the language **English (en)** and **Hindi (hi)** under the SAME name. The same data is in `docs/whatsapp-templates.json`.

**Rules Meta checks** (already followed below): a body must not start or end with a variable (a variable followed only by a full stop counts as the end), variables are numbered {{1}}, {{2}} in order and every variable needs an example. Meta also refuses a message with too many variables for its length, so keep these bodies as written. Quick-reply buttons are at most 25 characters and are plain text with no emoji and the body at most 1024. Meta may move a template to Marketing if it reads like promotion; if so, reword it or appeal.

The app fills the variables automatically, in the order shown. Parent-facing templates go to the parent's number, caregiver templates to the caregiver's own number.

There are 19 templates, so 38 submissions (each in two languages).

## 1. `carelink_consent_request_v2`
*Asks the parent to agree before we send anything. Sent to the parent. Category: **Marketing** (Meta rejects permission-asking messages as Utility), type: Default.*

**English (en)**

- **Body:** Namaste {{1}} 🙏 {{2}} has set up medicine reminders and a daily check-in for you on Chhaon. To start receiving these WhatsApp messages, please confirm below. You can stop them at any time by replying STOP.
- **Buttons (Quick reply):** `Yes, start`  |  `No thanks`
- **Samples:** {{1}} = Papa, {{2}} = Ananya

**Hindi (hi)**

- **Body:** नमस्ते {{1}} 🙏 {{2}} ने आपके लिए छाँव पर दवा के रिमाइंडर और रोज़ का चेक-इन शुरू किया है। ये WhatsApp संदेश पाना शुरू करने के लिए कृपया नीचे पुष्टि करें। आप कभी भी STOP लिखकर इन्हें बंद कर सकते हैं।
- **Buttons (Quick reply):** `हाँ, शुरू करें`  |  `नहीं, धन्यवाद`
- **Samples:** {{1}} = पापा, {{2}} = अनन्या

## 2. `carelink_dose_reminder_photo`
*Medicine reminder with the strip or bottle photo. Sent to the parent. Category: Utility, type: Default.*

- **Header:** IMAGE (sample: any strip or bottle photo, JPG or PNG)
**English (en)**

- **Body:** Namaste {{1}} 🙏 Good day! It is time for your medicine. Please take {{2}} ({{3}}), {{4}}. When you have taken it, please tap the Taken button below so your family knows. Thank you.
- **Buttons (Quick reply):** `Taken`
- **Samples:** {{1}} = Papa, {{2}} = Metformin, {{3}} = 500 mg, {{4}} = After food

**Hindi (hi)**

- **Body:** नमस्ते {{1}} 🙏 आपकी दवा का समय हो गया है। कृपया {{2}} ({{3}}) लें, {{4}}। दवा लेने के बाद नीचे दिया गया “ले ली” बटन दबाइए, ताकि आपके परिवार को पता चल जाए। धन्यवाद।
- **Buttons (Quick reply):** `ले ली`
- **Samples:** {{1}} = पापा, {{2}} = Metformin, {{3}} = 500 mg, {{4}} = खाने के बाद

## 3. `carelink_dose_reminder`
*Medicine reminder without a photo. Sent to the parent. Category: Utility, type: Default.*

**English (en)**

- **Body:** Namaste {{1}} 🙏 Good day! It is time for your medicine. Please take {{2}} ({{3}}), {{4}}. When you have taken it, please tap the Taken button below so your family knows. Thank you.
- **Buttons (Quick reply):** `Taken`
- **Samples:** {{1}} = Papa, {{2}} = Metformin, {{3}} = 500 mg, {{4}} = After food

**Hindi (hi)**

- **Body:** नमस्ते {{1}} 🙏 आपकी दवा का समय हो गया है। कृपया {{2}} ({{3}}) लें, {{4}}। दवा लेने के बाद नीचे दिया गया “ले ली” बटन दबाइए, ताकि आपके परिवार को पता चल जाए। धन्यवाद।
- **Buttons (Quick reply):** `ले ली`
- **Samples:** {{1}} = पापा, {{2}} = Metformin, {{3}} = 500 mg, {{4}} = खाने के बाद

## 4. `carelink_daily_checkin`
*Good-morning check-in with one-tap replies. Sent to the parent. Category: Utility, type: Default.*

**English (en)**

- **Body:** Good morning {{1}}! ☀️ This is your daily check-in that your family set up on Chhaon. Are you feeling okay today? Please tap one of the buttons below.
- **Buttons (Quick reply):** `I'm okay`  |  `Call me`
- **Samples:** {{1}} = Papa

**Hindi (hi)**

- **Body:** नमस्ते {{1}} 🙏 यह आपके परिवार द्वारा छाँव पर शुरू किया गया रोज़ का स्वास्थ्य चेक-इन है। कृपया नीचे दिए गए बटन से बताइए कि आज आप कैसे हैं।
- **Buttons (Quick reply):** `मैं ठीक हूँ`  |  `मुझे फ़ोन करें`
- **Samples:** {{1}} = पापा

## 5. `carelink_appointment_reminder`
*Reminds the parent of a doctor visit (evening before and that morning). Sent to the parent. Category: Utility, type: Default.*

**English (en)**

- **Body:** Namaste {{1}} 🙏 This is a gentle reminder from your family. Your appointment with {{2}} is {{3}}. Please keep your reports and medicines ready. Take care.
- **Samples:** {{1}} = Papa, {{2}} = Dr. Mehta, {{3}} = tomorrow at 11:00 AM

**Hindi (hi)**

- **Body:** नमस्ते {{1}} 🙏 आपके परिवार की ओर से एक छोटी सी याद। {{2}} के साथ आपकी अपॉइंटमेंट {{3}} है। कृपया अपनी रिपोर्ट और दवाइयाँ साथ रखिए। अपना ध्यान रखिए।
- **Samples:** {{1}} = पापा, {{2}} = Dr. Mehta, {{3}} = कल सुबह 11:00

## 6. `carelink_alert_optin`
*Asks the caregiver to confirm WhatsApp alerts to their own number. Sent to the caregiver. Category: **Marketing** (Meta rejects permission-asking messages as Utility), type: Default.*

**English (en)**

- **Body:** Hi {{1}}, you asked to get Chhaon alerts on this WhatsApp number. These are messages about missed doses, check-ins with no reply and low medicine stock. Please confirm below to start receiving them.
- **Buttons (Quick reply):** `Yes, start`  |  `No thanks`
- **Samples:** {{1}} = Ananya

**Hindi (hi)**

- **Body:** नमस्ते {{1}}, आपने इस WhatsApp नंबर पर छाँव के अलर्ट माँगे थे। ये छूटी हुई दवा, बिना जवाब वाले चेक-इन और कम होती दवा के संदेश हैं। इन्हें पाना शुरू करने के लिए कृपया नीचे पुष्टि करें।
- **Buttons (Quick reply):** `हाँ, शुरू करें`  |  `नहीं, धन्यवाद`
- **Samples:** {{1}} = अनन्या

## 7. `carelink_alert_dose_missed`
*A dose was not confirmed. Sent to the caregiver. Category: Utility, type: Default.*

**English (en)**

- **Body:** Alert: {{1}} has not confirmed {{2}}, which was due at {{3}}. Please call them to check that everything is all right.
- **Samples:** {{1}} = Papa, {{2}} = Metformin, {{3}} = 8:00 AM

**Hindi (hi)**

- **Body:** अलर्ट: {{1}} ने {{2}} की पुष्टि नहीं की, जिसका समय {{3}} था। कृपया उन्हें फ़ोन करके पूछ लें कि सब ठीक है।
- **Samples:** {{1}} = पापा, {{2}} = Metformin, {{3}} = सुबह 8:00

## 8. `carelink_alert_checkin_missed`
*The daily check-in got no reply. Sent to the caregiver. Category: Utility, type: Default.*

**English (en)**

- **Body:** Alert: {{1}} has not replied to the morning check-in. Please call to make sure they are okay.
- **Samples:** {{1}} = Papa

**Hindi (hi)**

- **Body:** अलर्ट: {{1}} ने सुबह के चेक-इन का जवाब नहीं दिया। कृपया फ़ोन करके पूछ लें।
- **Samples:** {{1}} = पापा

## 9. `carelink_alert_call_requested`
*The parent tapped Call me. Sent to the caregiver. Category: Utility, type: Default.*

**English (en)**

- **Body:** Alert: {{1}} asked you to call them (from the daily check-in).
- **Samples:** {{1}} = Papa

**Hindi (hi)**

- **Body:** सेवा सूचना: आपके द्वारा शुरू किए गए छाँव चेक-इन में {{1}} ने “मुझे फ़ोन करें” बटन दबाया है। कृपया उन्हें फ़ोन करके हाल पूछ लें।
- **Samples:** {{1}} = पापा

## 10. `carelink_alert_low_stock`
*A medicine is running low. Sent to the caregiver. Category: Utility, type: Default.*

**English (en)**

- **Body:** Reminder: the medicine {{1}} is running low for {{2}}. Please arrange a refill soon so that no dose is missed.
- **Samples:** {{1}} = Metformin, {{2}} = Papa

**Hindi (hi)**

- **Body:** याद दिलाना: दवा {{1}} कम हो रही है, जो {{2}} के लिए है। कृपया जल्दी रिफ़िल का इंतज़ाम कर लें, ताकि कोई खुराक न छूटे।
- **Samples:** {{1}} = Metformin, {{2}} = पापा

## 11. `carelink_alert_out_of_stock`
*A medicine has run out. Sent to the caregiver. Category: Utility, type: Default.*

**English (en)**

- **Body:** Alert: the medicine {{1}} has run out for {{2}}. Please arrange it today so that no dose is missed.
- **Samples:** {{1}} = Metformin, {{2}} = Papa

**Hindi (hi)**

- **Body:** अलर्ट: दवा {{1}} खत्म हो गई है, जो {{2}} के लिए है। कृपया आज ही इंतज़ाम कर लें, ताकि कोई खुराक न छूटे।
- **Samples:** {{1}} = Metformin, {{2}} = पापा

## 12. `carelink_alert_not_sent`
*A reminder could not be sent. Sent to the caregiver. Category: Utility, type: Default.*

**English (en)**

- **Body:** Alert: we could not send the reminder for {{1}} to {{2}}. Please remind them yourself or give them a call.
- **Samples:** {{1}} = Metformin, {{2}} = Papa

**Hindi (hi)**

- **Body:** अलर्ट: {{1}} का रिमाइंडर {{2}} को नहीं भेजा जा सका। कृपया उन्हें खुद याद दिला दें या फ़ोन कर लें।
- **Samples:** {{1}} = Metformin, {{2}} = पापा

## 13. `carelink_alert_appointment`
*Tells the caregiver a doctor-visit reminder was sent. Sent to the caregiver. Category: Utility, type: Default.*

**English (en)**

- **Body:** We have reminded {{1}} about the appointment with {{2}} ({{3}}). You can see all doctor visits in the app, under Doctor visits.
- **Samples:** {{1}} = Papa, {{2}} = Dr. Mehta, {{3}} = tomorrow at 11:00 AM

**Hindi (hi)**

- **Body:** हमने {{1}} को {{2}} के साथ अपॉइंटमेंट ({{3}}) की याद दिलाई है। सभी डॉक्टर मुलाक़ातें आप ऐप में “डॉक्टर से मुलाक़ात” में देख सकते हैं।
- **Samples:** {{1}} = पापा, {{2}} = Dr. Mehta, {{3}} = कल सुबह 11:00

## 14. `carelink_alert_parent_stopped`
*The parent chose to stop messages. Sent to the caregiver. Category: Utility, type: Default.*

**English (en)**

- **Body:** Alert: {{1}} chose to stop reminder messages on WhatsApp. Reminders and check-ins are paused.
- **Samples:** {{1}} = Papa

**Hindi (hi)**

- **Body:** सेवा सूचना: आपके द्वारा जोड़े गए {{1}} ने छाँव के WhatsApp संदेश बंद करवा दिए हैं। इसलिए दवा के रिमाइंडर और रोज़ का चेक-इन अभी रुके हुए हैं।
- **Samples:** {{1}} = पापा

## 15. `carelink_dose_reminder_multi`
*One reminder for every medicine due at the same time (replaces several single reminders). Sent to the parent. Category: Utility, type: Default, no header.*
*The app writes each medicine as "Name (dose)", separated by commas; a very long list is cut and ends with "and 3 more" (Hindi: "और 3 दवाएँ"). One tap on the button marks all of them taken.*

**English (en)**

- **Body:** Namaste {{1}} 🙏 Good day! It is time for your medicines: {{2}}. When you have taken them, please tap the button below so your family knows. Thank you.
- **Buttons (Quick reply):** `Taken all`
- **Samples:** {{1}} = Papa, {{2}} = Metformin (500 mg), Amlodipine (5 mg), Atorvastatin (10 mg)

**Hindi (hi)**

- **Body:** नमस्ते {{1}} 🙏 आपकी दवाओं का समय हो गया है: {{2}}। दवाएँ लेने के बाद नीचे दिया गया बटन दबाइए, ताकि आपके परिवार को पता चल जाए। धन्यवाद।
- **Buttons (Quick reply):** `सब ले ली`
- **Samples:** {{1}} = पापा, {{2}} = Metformin (500 mg), Amlodipine (5 mg), Atorvastatin (10 mg)

## 16. `carelink_parent_dose_nudge`
*One gentle nudge when a medicine reminder was not answered. Sent to the parent. Category: Utility, type: Default, no header.*

**English (en)**

- **Body:** Namaste {{1}} 🙏 A gentle reminder: these medicines are still pending: {{2}}. Please tap the button below once you have taken them. Thank you.
- **Buttons (Quick reply):** `Taken`
- **Samples:** {{1}} = Papa, {{2}} = Metformin (500 mg), Amlodipine (5 mg)

**Hindi (hi)**

- **Body:** नमस्ते {{1}} 🙏 एक छोटी सी याद: ये दवाएँ अभी बाकी हैं: {{2}}। लेने के बाद नीचे दिया गया बटन दबाइए। धन्यवाद।
- **Buttons (Quick reply):** `ले ली`
- **Samples:** {{1}} = पापा, {{2}} = Metformin (500 mg), Amlodipine (5 mg)

## 17. `carelink_parent_checkin_nudge`
*One gentle nudge when the daily check-in was not answered. Sent to the parent. Category: **Marketing** (decided 2026-10-17: Meta kept classifying it as Marketing, in Hindi even after rewording, so we accept it; it costs more per message but is rare), type: Default, no header. Use the original wording below, or any wording Meta accepts.*

**English (en)**

- **Body:** Namaste {{1}} 🙏 We have not heard from you today. Are you okay? Please tap one of the buttons below.
- **Buttons (Quick reply):** `I'm okay`, `Call me`
- **Samples:** {{1}} = Papa

**Hindi (hi)**

- **Body:** नमस्ते {{1}} 🙏 आज आपका कोई जवाब नहीं आया। क्या आप ठीक हैं? कृपया नीचे दिए गए बटनों में से एक दबाइए।
- **Buttons (Quick reply):** `मैं ठीक हूँ`, `मुझे फ़ोन करें`
- **Samples:** {{1}} = पापा

## 18. `carelink_alert_parent_replied`
*Tells the family a parent has now answered after they were alerted. Sent to the caregiver. Category: Utility, type: Default, no header.*

**English (en)**

- **Body:** Update: {{1}} has now replied to Chhaon. You may not need to act on the earlier alert.
- **Buttons:** none
- **Samples:** {{1}} = Papa

**Hindi (hi)**

- **Body:** अपडेट: {{1}} ने अब छाँव को जवाब दे दिया है। पहले वाले अलर्ट पर शायद कुछ करने की ज़रूरत नहीं।
- **Buttons:** none
- **Samples:** {{1}} = पापा

## 19. `carelink_alert_parent_silent`
*Tells the family a parent has not replied to anything for days; extra nudges and alerts are paused. Sent to the caregiver. Category: Utility, type: Default, no header.*

**English (en)**

- **Body:** Alert: {{1}} has not replied to any Chhaon message for {{2}} days. We have paused the extra nudges and alerts until they reply. Please call them.
- **Buttons:** none
- **Samples:** {{1}} = Papa, {{2}} = 3

**Hindi (hi)**

- **Body:** अलर्ट: {{1}} ने {{2}} दिन से छाँव के किसी संदेश का जवाब नहीं दिया। जवाब आने तक हमने अतिरिक्त याद और अलर्ट रोक दिए हैं। कृपया उन्हें फ़ोन कीजिए।
- **Buttons:** none
- **Samples:** {{1}} = पापा, {{2}} = 3

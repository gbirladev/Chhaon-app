# Product Plan — Family Health & Care App (v1)

## 1. Why this product

- **Real, emotional, recurring problem:** children living away from aging parents worry about
  medicines, reports and health, and already spend money on it.
- **Global payers from day one:** NRIs in the US, UK, UAE, Canada and Australia pay in foreign
  currency, which solves India's price-sensitivity problem.
- **Software-only start:** no field staff needed. Physical services (lab tests at home, nursing,
  doctor visits) come later through **partnerships**, not hiring.
- **AI advantage:** reading prescriptions and lab reports and explaining them in local languages
  is now possible for a very small team.
- **Less crowded** than tutoring tools, freelancer payments or rental apps.

**Risks:** elderly users struggle with apps (solved by WhatsApp-only parent side), health data
needs strong privacy, trust takes time to build.

## 2. Team and manpower

- v1: 1–3 people (a developer + someone talking to users / design / marketing).
- Later services: partner with diagnostic labs, home nursing agencies, pharmacies; earn
  commission or referral fees.
- Own staff only after proof (thousands of paying families) and only if partner quality is poor.

## 3. Target users

- **Payer:** adult child, 25–45, living in another city or abroad.
- **Parent:** 60+, takes regular medicines, uses WhatsApp.

## 4. 12-week timeline

| Weeks | Focus | Output |
|---|---|---|
| 1–2 | Validate | 15–20 interviews; simple landing page + waitlist |
| 3–4 | Design | Screen sketches tested with 5 people; apply for WhatsApp Business account & templates (approval takes time) |
| 5–8 | Build | Reminders + WhatsApp flow first, then dashboard, AI reports, family sharing |
| 9–10 | Pilot | Free for 20–30 real families; weekly check-ins with them |
| 11–12 | Improve & price | Fix confusion; ask pilot users to pay (5–10 paying = strong signal) |

## 5. Pricing to test (experiments, not final)

- **Free:** one parent, basic medicine reminders.
- **Premium:** AI report reading, trends, family sharing, multiple parents.
- Starting points: ₹199–299/month (India), $5–8/month (NRIs).

## 6. Metrics

- % of reminders confirmed by parents on WhatsApp
- Weekly active caregivers
- Families still active after 4 weeks
- Number of pilot users willing to pay

## 7. Legal and trust

- Follow India's **DPDP Act 2023** (clear consent, data deletion, security).
- Prefer servers in India.
- Simple privacy policy and terms.
- AI explains, never diagnoses; always "consult your doctor."
- Short consultation with a health-tech lawyer before public launch.

## 8. Rough costs (pilot, excluding salaries)

Domain, hosting (free tiers often enough), WhatsApp per-message charges from Meta, AI API usage
(a few rupees per report). Expect a few thousand to a few tens of thousands of rupees.
**Check current prices before budgeting.**

## 9. Interview questions (weeks 1–2)

Talk to people who live away from their parents. Ask about the past and present, not
"would you use my app?"

**About their situation**
1. Where do your parents live, and where do you live? How often do you visit?
2. Do they take regular medicines? For what conditions?
3. Who helps them day to day?

**About the problem**
4. How do you know whether they've taken their medicines?
5. Tell me about the last time something went wrong with their health or medicines. What happened?
6. Where are their prescriptions and lab reports kept? How do you see them?
7. When they get a lab report, how do you understand what it means?
8. What worries you most about their health when you're away?

**About current solutions**
9. What do you use today to manage this (calls, WhatsApp, Excel, notes, apps)?
10. Have you tried any app for this? Why did you stop or keep using it?
11. What do you spend money on today for their care (caretaker, trips, tests, services)?

**About the parent**
12. How comfortable are your parents with WhatsApp? With other apps?
13. Would they reply to a WhatsApp reminder? What would annoy them?

**Closing**
14. If one thing could be solved for you tomorrow, what would it be?
15. Can I show you an early version in a few weeks? (Collect contact = waitlist.)

**Strong signals:** messy workarounds already in use, money already spent, a story about a real
missed dose or emergency, eagerness to try it.

## 10. Roadmap after v1 (only after proof)

1. Paid plans (Razorpay + international cards).
2. More Indian languages (Tamil, Telugu, Bengali, Marathi, Gujarati, Kannada, Malayalam).
3. Voice notes for parents in WhatsApp.
4. Partner services: home lab collection, pharmacy refills, nurse visits, doctor teleconsults.
5. Native mobile apps.
6. Expansion to other diaspora markets.

## 11. First prompt to give Claude Code in VS Code

> Read CLAUDE.md and docs/PRODUCT_PLAN.md. Before writing code, confirm the tech stack with me
> and suggest the folder structure. Then start with step 1 of the build order: project setup,
> authentication and the Supabase schema with Row Level Security. Work step by step and ask me
> before moving to the next step.

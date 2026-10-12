import { useEffect, useState, type ChangeEvent, type FormEvent, type ReactNode } from "react";

type Screen =
  | "welcome"
  | "otp"
  | "profile"
  | "today"
  | "medicines"
  | "addMedicine"
  | "reports"
  | "reportDetail"
  | "family"
  | "emergency"
  | "whatsapp";

type Medicine = {
  name: string;
  dose: string;
  timing: string;
  stock: number;
};

type Member = {
  name: string;
  role: string;
  initials: string;
};

const defaultMedicines: Medicine[] = [
  { name: "Metformin", dose: "500mg", timing: "Morning & Night", stock: 24 },
  { name: "Amlodipine", dose: "5mg", timing: "Night", stock: 6 },
  { name: "Calcium + D3", dose: "1 tablet", timing: "Afternoon", stock: 18 },
];

const defaultMembers: Member[] = [
  { name: "Ananya Sharma", role: "Admin • All access", initials: "AS" },
  { name: "Nikhil Sharma", role: "Brother • Can view & update", initials: "NS" },
  { name: "Meera Mausi", role: "Relative • View only", initials: "MM" },
];

function usePersistentState<T>(key: string, initial: T) {
  const [value, setValue] = useState<T>(() => {
    try {
      const saved = localStorage.getItem(key);
      return saved ? JSON.parse(saved) : initial;
    } catch {
      return initial;
    }
  });
  useEffect(() => {
    localStorage.setItem(key, JSON.stringify(value));
  }, [key, value]);
  return [value, setValue] as const;
}

function shareContent(title: string, text: string) {
  if (navigator.share) {
    return navigator.share({ title, text }).catch(() => undefined);
  }
  if (navigator.clipboard) {
    return navigator.clipboard.writeText(text).then(() => window.alert("Copied to clipboard."));
  }
  window.prompt("Copy and share this information:", text);
  return Promise.resolve();
}

const papaPhoto =
  "https://images.unsplash.com/photo-1542105907-bbbb542a5f79?crop=faces&cs=tinysrgb&fit=crop&fm=jpg&h=240&q=85&w=240";

function Icon({
  name,
  size = 22,
  strokeWidth = 1.8,
}: {
  name: string;
  size?: number;
  strokeWidth?: number;
}) {
  const paths: Record<string, ReactNode> = {
    home: <><path d="m3 10 9-7 9 7" /><path d="M5 9v11h14V9M9 20v-6h6v6" /></>,
    pill: <><path d="M10.5 4.5 4.7 10.3a5 5 0 0 0 7 7l5.8-5.8a5 5 0 0 0-7-7Z" /><path d="m8 7 9 9" /></>,
    report: <><path d="M6 3h9l3 3v15H6z" /><path d="M14 3v4h4M9 12h6M9 16h6" /></>,
    users: <><path d="M16 20v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" /><circle cx="9" cy="7" r="4" /><path d="M22 20v-2a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8" /></>,
    bell: <><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" /><path d="M10 21h4" /></>,
    chevron: <path d="m9 18 6-6-6-6" />,
    back: <><path d="m15 18-6-6 6-6" /><path d="M9 12h10" /></>,
    plus: <><path d="M12 5v14M5 12h14" /></>,
    calendar: <><rect x="3" y="5" width="18" height="16" rx="2" /><path d="M16 3v4M8 3v4M3 10h18" /></>,
    upload: <><path d="M12 16V4M7 9l5-5 5 5" /><path d="M5 14v6h14v-6" /></>,
    share: <><circle cx="18" cy="5" r="3" /><circle cx="6" cy="12" r="3" /><circle cx="18" cy="19" r="3" /><path d="m8.6 10.5 6.8-4M8.6 13.5l6.8 4" /></>,
    heart: <path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8l1.1 1.1L12 21l7.8-7.5a5.5 5.5 0 0 0 1-8.9Z" />,
    shield: <><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10" /><path d="m9 12 2 2 4-4" /></>,
    camera: <><path d="M4 7h4l2-3h4l2 3h4v13H4z" /><circle cx="12" cy="13" r="4" /></>,
    phone: <path d="M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.1 4.2 2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1 1 .4 2 .7 2.8a2 2 0 0 1-.4 2.1L8.1 9.9a16 16 0 0 0 6 6l1.3-1.3a2 2 0 0 1 2.1-.4c.9.3 1.8.6 2.8.7a2 2 0 0 1 1.7 2Z" />,
  };
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {paths[name]}
    </svg>
  );
}

function AppHeader({
  title,
  subtitle,
  back,
}: {
  title: string;
  subtitle?: string;
  back?: () => void;
}) {
  return (
    <header className="page-header">
      {back && <button className="icon-button" onClick={back} aria-label="Go back"><Icon name="back" /></button>}
      <div>
        <h1>{title}</h1>
        {subtitle && <p>{subtitle}</p>}
      </div>
    </header>
  );
}

function BottomNav({ screen, go }: { screen: Screen; go: (screen: Screen) => void }) {
  const tabs = [
    ["today", "home", "Today", "आज"],
    ["medicines", "pill", "Medicines", "दवाइयाँ"],
    ["reports", "report", "Reports", "रिपोर्ट"],
    ["family", "users", "Family", "परिवार"],
  ] as const;
  return (
    <nav className="bottom-nav">
      {tabs.map(([target, icon, label, hindi]) => {
        const active = screen === target || (screen === "addMedicine" && target === "medicines") || (screen === "reportDetail" && target === "reports") || (["emergency", "whatsapp"].includes(screen) && target === "family");
        return (
          <button key={target} className={active ? "active" : ""} onClick={() => go(target)}>
            <Icon name={icon} size={21} />
            <span>{label}</span>
            <small>{hindi}</small>
          </button>
        );
      })}
    </nav>
  );
}

function Welcome({ go }: { go: (screen: Screen) => void }) {
  const [method, setMethod] = useState<"phone" | "email">("phone");
  const [value, setValue] = useState("");
  const continueLogin = () => {
    if (!value.trim()) {
      window.alert(`Please enter your ${method === "phone" ? "phone number" : "email address"}.`);
      return;
    }
    sessionStorage.setItem("carelink-login", value);
    go("otp");
  };
  return (
    <main className="onboarding">
      <div className="welcome-art">
        <div className="brand-mark"><Icon name="heart" size={31} strokeWidth={2.2} /></div>
        <div className="portrait-wrap">
          <img src={papaPhoto} alt="Smiling elderly parent" />
          <span className="floating-chip chip-one">✓ Medicine taken</span>
          <span className="floating-chip chip-two">Papa is okay</span>
        </div>
      </div>
      <div className="welcome-copy">
        <p className="eyebrow">CARE, EVEN FROM FAR AWAY</p>
        <h1>Stay close to your parents, every day.</h1>
        <p>Medicines, reports and daily well-being—lovingly connected through WhatsApp.</p>
      </div>
      <div className="login-card">
        <label>{method === "phone" ? "Phone number" : "Email address"}</label>
        <div className="phone-input">
          {method === "phone" && <span>+91</span>}
          <input value={value} onChange={(event) => setValue(event.target.value)} placeholder={method === "phone" ? "98765 43210" : "you@example.com"} inputMode={method === "phone" ? "tel" : "email"} />
        </div>
        <button className="primary-button" onClick={continueLogin}>Continue with OTP</button>
        <button className="text-button" onClick={() => { setMethod(method === "phone" ? "email" : "phone"); setValue(""); }}>
          {method === "phone" ? "Use email instead" : "Use phone number instead"}
        </button>
      </div>
      <button className="demo-link" onClick={() => go("today")}>Explore demo dashboard</button>
    </main>
  );
}

function OtpScreen({ go }: { go: (screen: Screen) => void }) {
  const [otp, setOtp] = useState("");
  const destination = sessionStorage.getItem("carelink-login") || "your phone";
  const verify = () => {
    if (otp.length !== 6) {
      window.alert("Enter the 6-digit code. Use 123456 for this demo.");
      return;
    }
    localStorage.setItem("carelink-authenticated", "true");
    go("profile");
  };
  return (
    <main className="onboarding otp-screen">
      <AppHeader title="Verify it's you" subtitle={`We sent a code to ${destination}`} back={() => go("welcome")} />
      <div className="otp-icon"><Icon name="shield" size={30} /></div>
      <h2>Enter your 6-digit code</h2>
      <p>For this demo, use <strong>123456</strong>.</p>
      <input className="otp-input" value={otp} onChange={(event) => setOtp(event.target.value.replace(/\D/g, "").slice(0, 6))} inputMode="numeric" placeholder="• • • • • •" autoFocus />
      <button className="primary-button" onClick={verify}>Verify & continue</button>
      <button className="text-button" onClick={() => window.alert("A new code has been sent.")}>Resend code</button>
    </main>
  );
}

function ProfileForm({ go }: { go: (screen: Screen) => void }) {
  const saveProfile = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = Object.fromEntries(new FormData(event.currentTarget));
    localStorage.setItem("carelink-parent", JSON.stringify(data));
    go("today");
  };
  return (
    <main className="scroll-page no-nav">
      <AppHeader title="Add your parent" subtitle="Tell us a little about them" back={() => go("welcome")} />
      <div className="profile-photo">
        <div><Icon name="camera" /></div>
        <button>Add photo</button>
      </div>
      <form id="parent-form" className="form-card" onSubmit={saveProfile}>
        <div className="field"><label>Parent's name</label><input name="name" defaultValue="Rajesh Sharma" required /></div>
        <div className="two-col">
          <div className="field"><label>Age</label><input name="age" defaultValue="68" required /></div>
          <div className="field"><label>Relation</label><select name="relation" defaultValue="Father"><option>Father</option><option>Mother</option></select></div>
        </div>
        <div className="field"><label>WhatsApp number</label><input name="whatsapp" defaultValue="+91 98100 12345" required /></div>
        <div className="field"><label>City</label><input name="city" defaultValue="Lucknow, Uttar Pradesh" /></div>
        <div className="field"><label>Health conditions</label><input name="conditions" defaultValue="Type 2 Diabetes, High BP" /></div>
        <div className="two-col">
          <div className="field"><label>Blood group</label><select name="bloodGroup" defaultValue="B+"><option>B+</option><option>O+</option><option>A+</option></select></div>
          <div className="field"><label>Language</label><select name="language" defaultValue="Hindi"><option>Hindi</option><option>English</option></select></div>
        </div>
        <div className="field"><label>Allergies</label><input name="allergies" placeholder="None known" /></div>
      </form>
      <button className="primary-button sticky-action" type="submit" form="parent-form">Save & continue</button>
    </main>
  );
}

const StatusPill = ({ type, children }: { type: "taken" | "pending" | "missed"; children: ReactNode }) => (
  <span className={`status ${type}`}>{children}</span>
);

function Today({ go, language, setLanguage }: { go: (screen: Screen) => void; language: "en" | "hi"; setLanguage: (language: "en" | "hi") => void }) {
  return (
    <main className="scroll-page">
      <header className="home-header">
        <button className="parent-profile" onClick={() => go("profile")}>
          <img src={papaPhoto} alt="Rajesh Sharma" />
          <span><small>GOOD MORNING</small><strong>Papa</strong><em>Lucknow • 8:32 AM</em></span>
        </button>
        <div className="header-actions">
          <button className="language-button" onClick={() => setLanguage(language === "en" ? "hi" : "en")}>{language === "en" ? "हिं" : "EN"}</button>
          <button className="icon-button notification" aria-label="Notifications" onClick={() => window.alert("No new notifications.")}><Icon name="bell" /><i /></button>
        </div>
      </header>

      <section className="wellbeing-card">
        <div className="okay-icon">✓</div>
        <div><span>{language === "en" ? "DAILY CHECK-IN" : "आज की जानकारी"}</span><h2>{language === "en" ? "Papa is doing okay" : "पापा ठीक हैं"}</h2><p>{language === "en" ? "Replied “I'm okay” at 8:12 AM" : "सुबह 8:12 बजे “मैं ठीक हूँ” जवाब दिया"}</p></div>
        <button onClick={() => go("whatsapp")} aria-label="See WhatsApp messages"><Icon name="chevron" size={20} /></button>
      </section>

      <section className="alert-card">
        <div className="alert-icon">!</div>
        <div><strong>Evening dose not confirmed. Call Papa?</strong><p>Amlodipine • 8:00 PM</p></div>
        <button onClick={() => { window.location.href = "tel:+919810012345"; }}>Call</button>
      </section>

      <section className="section">
        <div className="section-title"><div><h2>Today's medicines</h2><p>आज की दवाइयाँ</p></div><button onClick={() => go("medicines")}>View all</button></div>
        <div className="timeline">
          <div className="medicine-row done">
            <div className="time"><strong>8:00</strong><small>AM</small></div>
            <div className="line"><i>✓</i></div>
            <div className="medicine-info"><h3>Metformin</h3><p>500mg • After breakfast</p></div>
            <StatusPill type="taken">Taken</StatusPill>
          </div>
          <div className="medicine-row current">
            <div className="time"><strong>1:00</strong><small>PM</small></div>
            <div className="line"><i><Icon name="pill" size={14} /></i></div>
            <div className="medicine-info"><h3>Calcium + D3</h3><p>1 tablet • After lunch</p></div>
            <StatusPill type="pending">Pending</StatusPill>
          </div>
          <div className="medicine-row">
            <div className="time"><strong>8:00</strong><small>PM</small></div>
            <div className="line missed-dot"><i>!</i></div>
            <div className="medicine-info"><h3>Amlodipine</h3><p>5mg • After dinner</p></div>
            <StatusPill type="missed">Missed</StatusPill>
          </div>
        </div>
      </section>

      <section className="appointment-card">
        <div className="calendar-tile"><Icon name="calendar" /><strong>18</strong><small>JUN</small></div>
        <div><span>UPCOMING APPOINTMENT</span><h3>Dr. Mehta • Diabetologist</h3><p>Tuesday at 10:30 AM</p></div>
        <Icon name="chevron" size={19} />
      </section>
    </main>
  );
}

function Medicines({ go, medicines }: { go: (screen: Screen) => void; medicines: Medicine[] }) {
  return (
    <main className="scroll-page">
      <AppHeader title="Medicines" subtitle="दवाइयाँ • 3 active" />
      <div className="stock-summary"><Icon name="pill" /><div><strong>All medicines tracked</strong><p>1 refill needed this week</p></div></div>
      <section className="medicine-list">
        {medicines.map(({ name, dose, timing, stock }, index) => {
          const color = stock <= 6 ? "orange" : index % 2 ? "blue" : "green";
          return (
          <article className="med-card" key={name}>
            <div className={`med-visual ${color}`}><span /><span /><span /></div>
            <div className="med-copy"><h3>{name}</h3><p>{dose} • {timing}</p><small className={color === "orange" ? "low-stock" : ""}>{stock} tablets{stock <= 6 ? " left" : ""}</small></div>
            <button aria-label={`Open ${name}`}><Icon name="chevron" size={19} /></button>
          </article>
        )})}
      </section>
      <button className="fab" onClick={() => go("addMedicine")}><Icon name="plus" /><span>Add medicine</span></button>
    </main>
  );
}

function AddMedicine({ go, onSave }: { go: (screen: Screen) => void; onSave: (medicine: Medicine) => void }) {
  const [timings, setTimings] = useState<string[]>(["Morning", "Night"]);
  const [customTime, setCustomTime] = useState("");
  const [photoName, setPhotoName] = useState("");
  const [refill, setRefill] = useState(true);
  const toggleTiming = (timing: string) => setTimings((current) => current.includes(timing) ? current.filter((item) => item !== timing) : [...current, timing]);
  const save = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const timing = [...timings.filter((item) => item !== "Custom time"), ...(customTime ? [customTime] : [])].join(" & ");
    onSave({
      name: String(data.get("name")),
      dose: String(data.get("dose")),
      timing,
      stock: Number(data.get("stock")) || 0,
    });
    if (refill && "Notification" in window && Notification.permission === "default") {
      Notification.requestPermission().catch(() => undefined);
    }
    go("medicines");
  };
  return (
    <main className="scroll-page no-nav">
      <AppHeader title="Add medicine" subtitle="नई दवा जोड़ें" back={() => go("medicines")} />
      <label className="photo-upload large-upload"><input type="file" accept="image/*" capture="environment" onChange={(event) => setPhotoName(event.target.files?.[0]?.name || "")} /><span className="camera-disc"><Icon name="camera" size={30} /></span><span><strong>{photoName || "Upload medicine strip photo"}</strong><small>Helps Papa recognise the right medicine</small></span><em>{photoName ? "Change photo" : "Open camera"}</em></label>
      <form id="medicine-form" className="form-card" onSubmit={save}>
        <div className="field"><label>Medicine name</label><input name="name" placeholder="e.g. Metformin" required /></div>
        <div className="field"><label>Dosage</label><input name="dose" placeholder="e.g. 500mg, 1 tablet" required /></div>
        <div className="field"><label>When should it be taken?</label><div className="choice-chips wrap">{["Morning", "Afternoon", "Night", "Custom time"].map((timing) => <button type="button" key={timing} className={timings.includes(timing) ? "selected" : ""} onClick={() => toggleTiming(timing)}>{timing}</button>)}</div></div>
        {timings.includes("Custom time") && <div className="field"><label>Custom reminder time</label><input type="time" value={customTime} onChange={(event) => setCustomTime(event.target.value)} required /></div>}
        <div className="field"><label>Instructions</label><select><option>After food</option><option>Before food</option><option>With food</option></select></div>
        <div className="two-col">
          <div className="field"><label>Tablets in stock</label><input name="stock" placeholder="30" inputMode="numeric" required /></div>
          <div className="field"><label>Remind at</label><input defaultValue="5 tablets" /></div>
        </div>
        <label className="toggle-row"><span><strong>Refill reminder</strong><small>We'll remind you before it runs out</small></span><input type="checkbox" checked={refill} onChange={(event) => setRefill(event.target.checked)} /><i className={refill ? "" : "off"} /></label>
      </form>
      <button className="primary-button sticky-action" type="submit" form="medicine-form">Save medicine</button>
    </main>
  );
}

function Reports({ go }: { go: (screen: Screen) => void }) {
  const [processing, setProcessing] = useState(false);
  const uploadReport = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;
    setProcessing(true);
    sessionStorage.setItem("carelink-report-name", file.name.replace(/\.[^.]+$/, ""));
    window.setTimeout(() => {
      setProcessing(false);
      go("reportDetail");
    }, 900);
  };
  return (
    <main className="scroll-page">
      <AppHeader title="Health reports" subtitle="रिपोर्ट • All in one place" />
      <label className="upload-card"><input type="file" accept="image/*,.pdf,application/pdf" capture="environment" onChange={uploadReport} /><span><Icon name="upload" /></span><div><strong>{processing ? "Reading your report…" : "Upload a new report"}</strong><p>{processing ? "Extracting health values securely" : "Take a photo or choose a PDF"}</p></div><Icon name="chevron" size={19} /></label>
      <div className="section-title reports-title"><div><h2>Recent reports</h2><p>Sorted by newest</p></div></div>
      <section className="reports-list">
        <button className="report-card" onClick={() => go("reportDetail")}>
          <div className="file-icon">PDF</div><div><h3>Diabetes Panel</h3><p>Ruby Hall Clinic • 12 Jun 2025</p><span className="attention-dot">2 values need attention</span></div><Icon name="chevron" size={19} />
        </button>
        <button className="report-card" onClick={() => go("reportDetail")}>
          <div className="file-icon image">IMG</div><div><h3>Thyroid Profile</h3><p>Metropolis Labs • 18 Mar 2025</p><span className="normal-dot">All values in range</span></div><Icon name="chevron" size={19} />
        </button>
        <button className="report-card" onClick={() => go("reportDetail")}>
          <div className="file-icon">PDF</div><div><h3>Complete Blood Count</h3><p>Apollo Diagnostics • 02 Jan 2025</p><span className="normal-dot">All values in range</span></div><Icon name="chevron" size={19} />
        </button>
      </section>
    </main>
  );
}

function ReportDetail({ go }: { go: (screen: Screen) => void }) {
  const reportName = sessionStorage.getItem("carelink-report-name") || "Diabetes Panel";
  return (
    <main className="scroll-page no-nav">
      <AppHeader title={reportName} subtitle="12 Jun 2025 • AI-read report" back={() => go("reports")} />
      <section className="report-summary">
        <span>2</span><div><strong>Values need attention</strong><p>Here's a simple summary of Papa's results.</p></div>
      </section>
      <section className="values-card">
        <div className="value-row"><div><strong>HbA1c</strong><small>Normal range: below 5.7%</small></div><span><b>7.8%</b><em className="tag high">High</em></span></div>
        <div className="value-row"><div><strong>Fasting Sugar</strong><small>Normal range: 70–99 mg/dL</small></div><span><b>142 mg/dL</b><em className="tag high">High</em></span></div>
        <div className="value-row"><div><strong>TSH (Thyroid)</strong><small>Normal range: 0.4–4.0 mIU/L</small></div><span><b>2.1 mIU/L</b><em className="tag normal">Normal</em></span></div>
      </section>
      <section className="explanation-card"><div className="bulb">i</div><div><strong>What this means</strong><p>Papa's average blood sugar is above the usual range. His thyroid level looks okay. It would be helpful to discuss the sugar results with his doctor.</p></div></section>
      <section className="chart-card">
        <div className="section-title"><div><h2>HbA1c trend</h2><p>Last 4 reports</p></div><strong>7.8%</strong></div>
        <div className="chart">
          <div className="range-label">Target range</div>
          <svg viewBox="0 0 300 110" preserveAspectRatio="none"><path className="chart-area" d="M5 72 C60 80,80 54,110 59 S170 45,205 50 S255 29,295 28 L295 110 L5 110Z" /><path className="chart-line" d="M5 72 C60 80,80 54,110 59 S170 45,205 50 S255 29,295 28" /><g><circle cx="5" cy="72" r="4" /><circle cx="110" cy="59" r="4" /><circle cx="205" cy="50" r="4" /><circle cx="295" cy="28" r="5" /></g></svg>
          <div className="chart-labels"><span>Sep</span><span>Dec</span><span>Mar</span><span>Jun</span></div>
        </div>
      </section>
      <div className="disclaimer-card"><Icon name="shield" size={19} /><p><strong>This is not medical advice.</strong><br />Please consult your doctor.</p></div>
      <button className="primary-button doctor-share" onClick={() => shareContent(reportName, "CareLink report summary: HbA1c 7.8% (High), Fasting Sugar 142 mg/dL (High), TSH 2.1 mIU/L (Normal). Please consult a doctor.")}><Icon name="share" size={19} /> Share with doctor</button>
    </main>
  );
}

function Family({ go, members, onInvite }: { go: (screen: Screen) => void; members: Member[]; onInvite: (member: Member) => void }) {
  const [inviting, setInviting] = useState(false);
  const invite = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const name = String(data.get("name"));
    const role = String(data.get("role"));
    onInvite({ name, role: `${role} • Invite pending`, initials: name.split(" ").map((part) => part[0]).join("").slice(0, 2).toUpperCase() });
    setInviting(false);
    shareContent("Join our CareLink family circle", `Hi ${name}, you've been invited to help care for Papa on CareLink with ${role.toLowerCase()} access.`);
  };
  return (
    <main className="scroll-page">
      <AppHeader title="Family circle" subtitle="परिवार • Caring together" />
      <section className="family-hero"><div className="avatar-stack"><img src={papaPhoto} alt="" /><span>AS</span><span>NS</span></div><h2>{members.length} people care for Papa</h2><p>Everyone stays updated, wherever they are.</p><button onClick={() => setInviting(!inviting)}><Icon name="plus" size={18} /> Invite family member</button></section>
      {inviting && <form className="invite-form" onSubmit={invite}><input name="name" placeholder="Name or email" required /><select name="role"><option>Can view & update</option><option>View only</option><option>Admin</option></select><button type="submit">Send invite</button></form>}
      <div className="section-title members-title"><div><h2>Family members</h2><p>Manage access and roles</p></div></div>
      <section className="member-list">
        {members.map((member, index) => <div className="member" key={`${member.name}-${index}`}><span className={`member-avatar ${index % 3 === 0 ? "teal" : index % 3 === 1 ? "orange" : "plum"}`}>{member.initials}</span><div><h3>{member.name} {index === 0 && <em>You</em>}</h3><p>{member.role}</p></div><Icon name="chevron" size={18} /></div>)}
      </section>
      <button className="feature-link emergency-link" onClick={() => go("emergency")}><span><Icon name="heart" /></span><div><strong>Emergency card</strong><p>Important details, ready to share</p></div><Icon name="chevron" /></button>
      <button className="feature-link whatsapp-link" onClick={() => go("whatsapp")}><span><Icon name="phone" /></span><div><strong>Preview Papa's WhatsApp</strong><p>See the messages he receives</p></div><Icon name="chevron" /></button>
      <button className="account-link" onClick={() => go("welcome")}>Sign-in & welcome screen</button>
    </main>
  );
}

function Emergency({ go }: { go: (screen: Screen) => void }) {
  const emergencyText = "EMERGENCY INFO — Rajesh Sharma, 68, B Positive. Allergic to Penicillin. Conditions: Type 2 Diabetes, Hypertension. Medicines: Metformin 500mg, Amlodipine 5mg, Calcium + D3. Doctor: Dr. Amit Mehta +91 98220 45678. Family: Ananya Sharma +91 98765 43210.";
  const downloadCard = () => {
    const blob = new Blob([emergencyText], { type: "text/plain" });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = "Rajesh-Sharma-Emergency-Card.txt";
    anchor.click();
    URL.revokeObjectURL(url);
  };
  return (
    <main className="scroll-page no-nav emergency-page">
      <header className="emergency-title">
        <button onClick={() => go("family")} aria-label="Go back"><Icon name="back" /></button>
        <div><strong>EMERGENCY INFO</strong><small>Show this card to medical staff</small></div>
        <Icon name="heart" />
      </header>
      <section className="emergency-id">
        <div className="emergency-head"><img src={papaPhoto} alt="Rajesh Sharma" /><div><h2>Rajesh Sharma</h2><p>68 years • Lucknow, India</p></div><span>B+</span></div>
        <div className="emergency-grid"><div><small>BLOOD GROUP</small><strong>B Positive</strong></div><div><small>ALLERGIES</small><strong>Penicillin</strong></div></div>
        <div className="emergency-section"><small>CONDITIONS</small><div><span>Type 2 Diabetes</span><span>Hypertension</span></div></div>
        <div className="emergency-section"><small>CURRENT MEDICINES</small><ul><li>Metformin 500mg <span>Morning, Night</span></li><li>Amlodipine 5mg <span>Night</span></li><li>Calcium + D3 <span>Afternoon</span></li></ul></div>
        <div className="contact-row"><div><span>DOCTOR</span><strong>Dr. Amit Mehta</strong><p>+91 98220 45678</p></div><button aria-label="Call doctor" onClick={() => { window.location.href = "tel:+919822045678"; }}><Icon name="phone" /></button></div>
        <div className="contact-row"><div><span>FAMILY CONTACT</span><strong>Ananya Sharma</strong><p>+91 98765 43210</p></div><button aria-label="Call family contact" onClick={() => { window.location.href = "tel:+919876543210"; }}><Icon name="phone" /></button></div>
      </section>
      <div className="emergency-actions">
        <button className="primary-button share-button" onClick={() => shareContent("Rajesh Sharma — Emergency Info", emergencyText)}><Icon name="share" size={19} /> Share link</button>
        <button className="download-button" onClick={downloadCard}><Icon name="report" size={19} /> Download</button>
      </div>
      <p className="privacy-note"><Icon name="shield" size={16} /> Link expires automatically in 24 hours</p>
    </main>
  );
}

function WhatsApp({ go }: { go: (screen: Screen) => void }) {
  const [medicineReply, setMedicineReply] = useState(false);
  const [wellbeingReply, setWellbeingReply] = useState("");
  return (
    <main className="whatsapp-page">
      <header className="wa-header"><button onClick={() => go("family")}><Icon name="back" /></button><img src="/carelink-logo.svg" onError={(e) => { e.currentTarget.style.display = "none"; }} alt="" /><span className="wa-logo"><Icon name="heart" size={19} /></span><div><strong>CareLink</strong><small>WhatsApp reminders</small></div></header>
      <div className="wa-chat">
        <div className="wa-date">TODAY</div>
        <div className="wa-message medicine-message">
          <p>Namaste Papa 🙏</p><p>Time for your morning medicine:</p>
          <div className="wa-med-photo"><div className="blister"><i /><i /><i /><i /><i /><i /></div><span><strong>Metformin 500mg</strong><small>1 tablet • After breakfast</small></span></div>
          <small>8:30 AM ✓✓</small>
          <button onClick={() => setMedicineReply(true)}>✅ Taken</button>
        </div>
        {medicineReply && <div className="wa-reply"><p>✅ Taken</p><small>8:34 AM ✓✓</small></div>}
        <div className="wa-date next-day">TOMORROW</div>
        <div className="wa-message">
          <p>Good morning Papa! ☀️</p><p>Are you feeling okay today?</p><small>8:00 AM ✓✓</small>
          <div className="wa-buttons"><button onClick={() => setWellbeingReply("👍 I'm okay")}>👍 I'm okay</button><button onClick={() => setWellbeingReply("📞 Please call me")}>📞 Call me</button></div>
        </div>
        {wellbeingReply && <div className="wa-reply"><p>{wellbeingReply}</p><small>8:03 AM ✓✓</small></div>}
        <div className="wa-helper">One tap is all Papa needs. His reply appears instantly in your CareLink app.</div>
      </div>
      <div className="wa-input"><span>Message</span><button><Icon name="chevron" /></button></div>
    </main>
  );
}

export default function App() {
  const [screen, setScreen] = useState<Screen>("today");
  const [language, setLanguage] = usePersistentState<"en" | "hi">("carelink-language", "en");
  const [medicines, setMedicines] = usePersistentState<Medicine[]>("carelink-medicines", defaultMedicines);
  const [members, setMembers] = usePersistentState<Member[]>("carelink-family", defaultMembers);
  const hasNav = ["today", "medicines", "addMedicine", "reports", "reportDetail", "family", "emergency", "whatsapp"].includes(screen);
  return (
    <div className="app-shell">
      <div className="phone-frame">
        {screen === "welcome" && <Welcome go={setScreen} />}
        {screen === "otp" && <OtpScreen go={setScreen} />}
        {screen === "profile" && <ProfileForm go={setScreen} />}
        {screen === "today" && <Today go={setScreen} language={language} setLanguage={setLanguage} />}
        {screen === "medicines" && <Medicines go={setScreen} medicines={medicines} />}
        {screen === "addMedicine" && <AddMedicine go={setScreen} onSave={(medicine) => setMedicines([...medicines, medicine])} />}
        {screen === "reports" && <Reports go={setScreen} />}
        {screen === "reportDetail" && <ReportDetail go={setScreen} />}
        {screen === "family" && <Family go={setScreen} members={members} onInvite={(member) => setMembers([...members, member])} />}
        {screen === "emergency" && <Emergency go={setScreen} />}
        {screen === "whatsapp" && <WhatsApp go={setScreen} />}
        {hasNav && !["addMedicine", "reportDetail", "emergency", "whatsapp"].includes(screen) && <BottomNav screen={screen} go={setScreen} />}
      </div>
    </div>
  );
}

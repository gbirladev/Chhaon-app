import { NavLink } from "react-router";
import { useTranslations } from "use-intl";
import { Icon } from "./Icon";

const TABS = [
  { to: "/dashboard", icon: "home", key: "today" },
  { to: "/medicines", icon: "pill", key: "medicines" },
  { to: "/reports", icon: "report", key: "reports" },
  { to: "/family", icon: "users", key: "family" },
] as const;

export function BottomNav() {
  const t = useTranslations("nav");
  return (
    <nav className="bottom-nav" aria-label="Main">
      {TABS.map(({ to, icon, key }) => (
        <NavLink key={to} to={to} className={({ isActive }) => (isActive ? "active" : "")}>
          <Icon name={icon} size={22} />
          <span>{t(key)}</span>
        </NavLink>
      ))}
    </nav>
  );
}

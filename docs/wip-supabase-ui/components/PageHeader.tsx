import { Link } from "react-router";
import { useTranslations } from "use-intl";
import { Icon } from "./Icon";

export function PageHeader({ title, subtitle, backTo }: { title: string; subtitle?: string; backTo?: string }) {
  const t = useTranslations("common");
  return (
    <header className="page-header">
      {backTo && (
        <Link to={backTo} className="icon-button" aria-label={t("back")}><Icon name="back" /></Link>
      )}
      <div>
        <h1>{title}</h1>
        {subtitle && <p>{subtitle}</p>}
      </div>
    </header>
  );
}

import { useTranslations } from "use-intl";
import { PageHeader } from "@/components/PageHeader";

export default function ComingSoonPage() {
  const t = useTranslations("comingSoon");
  return (
    <main className="scroll-page">
      <PageHeader title={t("title")} />
      <p className="empty-state">{t("body")}</p>
    </main>
  );
}

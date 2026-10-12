import { Navigate, Outlet } from "react-router";
import { useTranslations } from "use-intl";
import { useAuth } from "@/lib/auth";
import { useRecipient } from "@/lib/recipient";

export function Loading() {
  const t = useTranslations("common");
  return <p role="status" className="empty-state">{t("loading")}</p>;
}

/** Anonymous visitors go to /login. */
export function RequireAuth() {
  const { user, loading } = useAuth();
  if (loading) return <Loading />;
  return user ? <Outlet /> : <Navigate to="/login" replace />;
}

/** First-time caregivers go to the add-parent form. */
export function RequireRecipient() {
  const { recipient, loading, error, reload } = useRecipient();
  const t = useTranslations("common");
  if (loading) return <Loading />;
  if (error) {
    return (
      <div className="empty-state">
        <strong>{t("somethingWrong")}</strong>
        <button className="secondary-button" onClick={() => void reload()}>{t("retry")}</button>
      </div>
    );
  }
  return recipient ? <Outlet /> : <Navigate to="/parent/new" replace />;
}

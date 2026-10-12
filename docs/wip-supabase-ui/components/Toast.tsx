import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";

type ToastApi = { show: (message: string, kind?: "ok" | "error") => void };
const ToastContext = createContext<ToastApi>({ show: () => {} });

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<{ message: string; kind: "ok" | "error"; id: number } | null>(null);
  const timer = useRef<number | undefined>(undefined);

  const show = useCallback((message: string, kind: "ok" | "error" = "ok") => {
    window.clearTimeout(timer.current);
    setToast({ message, kind, id: Date.now() });
    timer.current = window.setTimeout(() => setToast(null), 3200);
  }, []);
  useEffect(() => () => window.clearTimeout(timer.current), []);

  return (
    <ToastContext.Provider value={{ show }}>
      {children}
      <div aria-live="polite" role="status">
        {toast && <div key={toast.id} className={`toast ${toast.kind === "error" ? "error" : ""}`}>{toast.message}</div>}
      </div>
    </ToastContext.Provider>
  );
}

export const useToast = () => useContext(ToastContext);

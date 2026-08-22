import { useEffect } from "react";
import { api } from "../api/client";
import { useApi } from "./useApi";

const REFRESH_MS = 5000;

/** Live count of in-flight grabs — shared by TopBar's pill and Sidebar's nav badge/status line, so both agree with what ActivityPage itself shows rather than each polling (or worse, hardcoding) their own. */
export function useActiveGrabCount(): number {
  const { data: grabs, reload } = useApi(() => api.listGrabs(), []);

  useEffect(() => {
    const id = window.setInterval(reload, REFRESH_MS);
    return () => window.clearInterval(id);
  }, [reload]);

  return grabs?.filter((g) => g.status === "GRABBED").length ?? 0;
}

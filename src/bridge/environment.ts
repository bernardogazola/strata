export const isTauri = (): boolean => "__TAURI_INTERNALS__" in window;

export type WindowPlatform = "linux" | "macos" | "windows";

const MAC_USER_AGENT = /Macintosh|Mac OS X/;
const WINDOWS_USER_AGENT = /Windows NT/;

export const platformFromUserAgent = (userAgent: string): WindowPlatform => {
  if (MAC_USER_AGENT.test(userAgent)) {
    return "macos";
  }
  return WINDOWS_USER_AGENT.test(userAgent) ? "windows" : "linux";
};

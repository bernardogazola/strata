import {
  IconDeviceDesktop,
  IconFileText,
  IconShield,
} from "@tabler/icons-react";
import { AppHeader, BrandMark } from "@/features/shell/app-header";
import { t } from "@/i18n/messages";
import { OpenRepository } from "./open-repository";

const BRAND_MARK_SIZE = 32;

const [LOCAL_ONLY, BUILD_AS_TEXT, READ_ONLY] = t.start.guarantees;

const GUARANTEES = [
  { Icon: IconDeviceDesktop, text: LOCAL_ONLY },
  { Icon: IconFileText, text: BUILD_AS_TEXT },
  { Icon: IconShield, text: READ_ONLY },
] as const;

export function StartScreen() {
  return (
    <div className="flex min-h-svh flex-col bg-background">
      <AppHeader />
      <main
        aria-label={t.shell.regions.start}
        className="flex grow items-center justify-center px-4 pt-8 pb-12"
      >
        <div className="flex w-full max-w-[520px] flex-col gap-7">
          <div className="flex flex-col gap-3">
            <BrandMark size={BRAND_MARK_SIZE} />
            <h1 className="font-medium text-2xl text-foreground">
              {t.start.title}
            </h1>
            <p className="text-muted-foreground text-sm/5">{t.start.body}</p>
          </div>

          <OpenRepository />

          <ul className="flex flex-col gap-3 border-t pt-5">
            {GUARANTEES.map(({ Icon, text }) => (
              <li
                className="flex items-center gap-2.5 text-muted-foreground text-sm"
                key={text}
              >
                <Icon
                  aria-hidden="true"
                  className="size-4 shrink-0 text-subtle-foreground"
                  stroke={1.5}
                />
                {text}
              </li>
            ))}
          </ul>
        </div>
      </main>
    </div>
  );
}

import { IconMoon, IconSun } from "@tabler/icons-react";
import { useTheme } from "@/components/theme-provider";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { t } from "@/i18n/messages";
import type { Theme } from "@/lib/theme";

const THEME_OPTIONS: readonly Theme[] = ["light", "dark", "system"];

const ICON_STROKE = 1.5;

export function ModeToggle() {
  const { theme, resolvedTheme, setTheme } = useTheme();

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <Button
            className="size-7 text-muted-foreground hover:text-foreground aria-expanded:text-foreground"
            size="icon-sm"
            variant="ghost"
          />
        }
      >
        {resolvedTheme === "dark" ? (
          <IconMoon aria-hidden="true" stroke={ICON_STROKE} />
        ) : (
          <IconSun aria-hidden="true" stroke={ICON_STROKE} />
        )}
        <span className="sr-only">{t.theme.label}</span>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuRadioGroup onValueChange={setTheme} value={theme}>
          {THEME_OPTIONS.map((option) => (
            <DropdownMenuRadioItem closeOnClick key={option} value={option}>
              {t.theme.options[option]}
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

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
import type { Theme } from "@/lib/theme";

const THEME_OPTIONS: ReadonlyArray<{ label: string; value: Theme }> = [
  { label: "Claro", value: "light" },
  { label: "Escuro", value: "dark" },
  { label: "Sistema", value: "system" },
];

export function ModeToggle() {
  const { theme, resolvedTheme, setTheme } = useTheme();

  return (
    <DropdownMenu>
      <DropdownMenuTrigger render={<Button size="icon" variant="outline" />}>
        {resolvedTheme === "dark" ? <IconMoon /> : <IconSun />}
        <span className="sr-only">Alternar tema</span>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuRadioGroup onValueChange={setTheme} value={theme}>
          {THEME_OPTIONS.map((option) => (
            <DropdownMenuRadioItem
              closeOnClick
              key={option.value}
              value={option.value}
            >
              {option.label}
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

import { Text, View } from "nsh";
import { Button, Input, Select, Tab, Tabs } from "nsh/base";
import type { SelectItem } from "nsh/base";
import { MOTION_CLASS, MOTION_DISTANCE, createSurfaceMotion } from "../../motion";

export type ButtonVariant = "primary" | "secondary" | "ghost" | "destructive";
export type ToneVariant = "neutral" | "accent" | "success" | "warning" | "danger";

const BUTTON_SURFACE: Record<ButtonVariant, string> = {
  primary:
    "bg-primary hover:bg-primary-hover active:bg-primary-active border border-primary",
  secondary:
    "bg-secondary hover:bg-secondary-hover active:bg-secondary-active border border-border",
  ghost: "bg-transparent hover:bg-muted active:bg-accent border border-transparent",
  destructive: "bg-destructive hover:opacity-90 active:opacity-80 border border-destructive",
};

const BUTTON_TEXT: Record<ButtonVariant, string> = {
  primary: "text-primary-foreground",
  secondary: "text-secondary-foreground",
  ghost: "text-foreground",
  destructive: "text-destructive-foreground",
};

export function ActionButton(props: {
  label: string;
  onPress: () => void;
  variant?: ButtonVariant;
  disabled?: boolean;
  busy?: boolean;
  selected?: boolean;
  testID?: string;
  accessibilityLabel?: string;
  accessibilityHint?: string;
  class?: string;
}) {
  const variant = () => props.variant ?? "primary";
  return (
    <Button
      testID={props.testID}
      disabled={props.disabled}
      busy={props.busy}
      selected={props.selected}
      accessibilityLabel={props.accessibilityLabel ?? props.label}
      accessibilityHint={props.accessibilityHint}
      onPress={props.onPress}
      class={`items-center justify-center rounded-(--radius) px-3.5 py-2 focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-50 ${MOTION_CLASS.interactiveTransition} ${BUTTON_SURFACE[variant()]} ${props.class ?? ""}`}
    >
      <Text class={`text-sm font-medium ${BUTTON_TEXT[variant()]}`}>{props.label}</Text>
    </Button>
  );
}

const BADGE_SURFACE: Record<ToneVariant, string> = {
  neutral: "bg-muted border-border",
  accent: "bg-accent border-border",
  success: "bg-success/15 border-success/35",
  warning: "bg-warning/15 border-warning/35",
  danger: "bg-destructive/15 border-destructive/35",
};

const BADGE_TEXT: Record<ToneVariant, string> = {
  neutral: "text-muted-foreground",
  accent: "text-accent-foreground",
  success: "text-success",
  warning: "text-warning",
  danger: "text-destructive",
};

const BADGE_DOT: Record<ToneVariant, string> = {
  neutral: "bg-muted-foreground",
  accent: "bg-primary",
  success: "bg-success",
  warning: "bg-warning",
  danger: "bg-destructive",
};

export function StatusBadge(props: {
  label: string;
  tone?: ToneVariant;
  testID?: string;
  class?: string;
}) {
  const tone = () => props.tone ?? "neutral";
  return (
    <View
      testID={props.testID}
      class={`flex-row items-center gap-1.5 rounded-full border px-2.5 py-0.5 ${MOTION_CLASS.colorTransition} ${BADGE_SURFACE[tone()]} ${props.class ?? ""}`}
    >
      <View class={`size-1.5 rounded-full ${BADGE_DOT[tone()]}`} />
      <Text class={`text-xs font-medium ${BADGE_TEXT[tone()]}`}>{props.label}</Text>
    </View>
  );
}

export interface SegmentOption {
  value: string;
  label: string;
  disabled?: boolean;
  testID?: string;
}

export function SegmentedTabs(props: {
  options: SegmentOption[];
  value: string | null;
  onChange: (value: string) => void;
  disabled?: boolean;
  accessibilityLabel: string;
  class?: string;
}) {
  return (
    <Tabs
      orientation="horizontal"
      accessibilityLabel={props.accessibilityLabel}
      class={`flex-row flex-wrap gap-1 rounded-(--radius-lg) border border-border bg-muted p-1 ${props.class ?? ""}`}
      indicatorClass="inset-y-1 rounded-(--radius) border border-border bg-card shadow-xs"
    >
      <Each of={props.options} key={(item: SegmentOption) => item.value}>
        {(option) => (
          <Tab
            testID={option().testID}
            selected={props.value === option().value}
            disabled={props.disabled || option().disabled}
            onPress={() => props.onChange(option().value)}
            class={`group flex-1 items-center justify-center rounded-(--radius) px-3 py-1.5 focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-50 ${MOTION_CLASS.colorTransition}`}
          >
            <Text class="text-xs font-medium text-muted-foreground group-selected:text-foreground">
              {option().label}
            </Text>
          </Tab>
        )}
      </Each>
    </Tabs>
  );
}

export type SelectFieldOption = SelectItem;

export function SelectField(props: {
  label: string;
  items: SelectItem[];
  value: string | null;
  placeholder: string;
  onChange: (value: string | null) => void;
  searchable?: boolean;
  searchPlaceholder?: string;
  disabled?: boolean;
  testID?: string;
  searchTestID?: string;
  hint?: string;
}) {
  const selectedTitle = () => {
    const match = props.items.find((item) => item.value === props.value);
    return match?.title ?? null;
  };

  return (
    <View class="gap-1.5">
      <Text class="text-xs font-medium text-muted-foreground">{props.label}</Text>
      <Select
        testID={props.testID}
        items={props.items}
        value={props.value}
        onChange={({ value }) => props.onChange(value)}
        searchable={props.searchable}
        searchPlaceholder={props.searchPlaceholder}
        searchAccessibilityLabel={props.searchPlaceholder ?? props.label}
        searchTestID={props.searchTestID}
        disabled={props.disabled}
        accessibilityLabel={props.label}
        offset={MOTION_DISTANCE.popupOffset}
        popupClass={`max-h-64 rounded-(--radius) border border-border bg-popover p-1 text-popover-foreground shadow-lg ${MOTION_CLASS.surfaceTransition}`}
        popupStartingClass={MOTION_CLASS.popupStarting}
        popupEndingClass={MOTION_CLASS.popupEnding}
        backdropClass="bg-black/40"
        listClass="gap-0.5"
        groupClass="px-2 py-1 text-xs font-semibold text-muted-foreground"
        itemClass="rounded-(--radius-sm) px-2.5 py-1.5 text-sm text-popover-foreground focus:bg-accent focus:text-accent-foreground selected:bg-accent selected:font-medium disabled:opacity-50"
        searchClass="mb-1 rounded-(--radius-sm) border border-input bg-background px-2.5 py-1.5 text-sm text-foreground placeholder:text-muted-foreground"
        trigger={
          <View
            class={`h-9 flex-row items-center justify-between gap-2 rounded-(--radius) border border-input bg-background px-3 hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring ${MOTION_CLASS.colorTransition}`}
          >
            <Text class={selectedTitle() ? "text-sm text-foreground" : "text-sm text-muted-foreground"}>
              {selectedTitle() ?? props.placeholder}
            </Text>
            <Text class="text-xs text-muted-foreground">▾</Text>
          </View>
        }
      />
      {props.hint ? <Text class="text-xs text-muted-foreground">{props.hint}</Text> : null}
    </View>
  );
}

export function TextField(props: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  onSubmit?: () => void;
  placeholder?: string;
  hint?: string;
  error?: string | null;
  disabled?: boolean;
  testID?: string;
}) {
  return (
    <View class="gap-1.5">
      <Text class="text-xs font-medium text-muted-foreground">{props.label}</Text>
      <Input
        testID={props.testID}
        value={props.value}
        onChange={({ value }) => props.onChange(value)}
        onSubmit={() => props.onSubmit?.()}
        placeholder={props.placeholder}
        readonly={props.disabled}
        accessibilityLabel={props.label}
        returnKeyType="done"
        class={`h-9 rounded-(--radius) border bg-background px-3 text-sm text-foreground placeholder:text-muted-foreground focus-visible:ring-2 focus-visible:ring-ring ${MOTION_CLASS.colorTransition} ${props.error ? "border-destructive" : "border-input"}`}
      />
      {props.error ? (
        <Text testID={props.testID ? `${props.testID}-error` : undefined} class="text-xs text-destructive">
          {props.error}
        </Text>
      ) : props.hint ? (
        <Text class="text-xs text-muted-foreground">{props.hint}</Text>
      ) : null}
    </View>
  );
}

const BANNER_SURFACE: Record<ToneVariant, string> = {
  neutral: "border-border bg-muted",
  accent: "border-primary/30 bg-accent",
  success: "border-success/35 bg-success/10",
  warning: "border-warning/35 bg-warning/10",
  danger: "border-destructive/35 bg-destructive/10",
};

const BANNER_TITLE: Record<ToneVariant, string> = {
  neutral: "text-foreground",
  accent: "text-accent-foreground",
  success: "text-success",
  warning: "text-warning",
  danger: "text-destructive",
};

export function NoticeBanner(props: {
  title: string;
  detail?: string | null;
  tone?: ToneVariant;
  testID?: string;
}) {
  const tone = () => props.tone ?? "neutral";
  const motion = createSurfaceMotion("fadeIn");
  return (
    <View
      testID={props.testID}
      style={motion.style}
      class={`gap-1 rounded-(--radius) border px-3 py-2 ${MOTION_CLASS.colorTransition} ${BANNER_SURFACE[tone()]}`}
    >
      <Text class={`text-xs font-medium ${BANNER_TITLE[tone()]}`}>{props.title}</Text>
      {props.detail ? <Text class="text-xs text-muted-foreground">{props.detail}</Text> : null}
    </View>
  );
}

export interface MetricItem {
  id: string;
  label: string;
  valueText: string;
  caption?: string | null;
  low?: boolean;
  testID?: string;
}

export function MetricGrid(props: { items: MetricItem[] }) {
  return (
    <View class="flex-row gap-2.5">
      <Each of={props.items} key={(item: MetricItem) => item.id}>
        {(item) => (
          <View
            testID={item().testID ?? `metric-${item().id}`}
            class={`flex-1 gap-1 rounded-(--radius) border border-border bg-muted/60 p-3 ${MOTION_CLASS.colorTransition}`}
          >
            <Text class="text-xs text-muted-foreground">{item().label}</Text>
            <Text class={`text-lg font-semibold ${item().low ? "text-destructive" : "text-foreground"}`}>
              {item().valueText}
            </Text>
            {item().caption ? (
              <Text class="text-xs text-muted-foreground">{item().caption}</Text>
            ) : null}
          </View>
        )}
      </Each>
    </View>
  );
}

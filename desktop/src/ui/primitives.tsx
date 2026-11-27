import { Scroll, Text, View } from "nsh";
import type { JSX, MotionName } from "nsh";
import { createSurfaceMotion } from "../../motion";

export function PageShell(props: { children?: JSX.Element; class?: string }) {
  return (
    <View class={`flex-1 bg-background ${props.class ?? ""}`}>
      {props.children}
    </View>
  );
}

export function TitleBar(props: {
  title: string;
  subtitle?: string;
  height?: number;
  right?: JSX.Element;
}) {
  return (
    <View
      desktop={{ windowDrag: true }}
      style={{ height: props.height ?? 44 }}
      class="flex-row items-center justify-between border-b border-border bg-sidebar px-4"
    >
      <View class="flex-row items-center gap-2">
        <Text class="text-sm font-semibold text-sidebar-foreground">{props.title}</Text>
        {props.subtitle ? (
          <Text class="text-xs text-muted-foreground">{props.subtitle}</Text>
        ) : null}
      </View>
      {props.right ? <View class="flex-row items-center gap-2">{props.right}</View> : null}
    </View>
  );
}

export function ScrollArea(props: { children?: JSX.Element; class?: string }) {
  return (
    <Scroll class={`flex-1 ${props.class ?? ""}`}>
      {props.children}
    </Scroll>
  );
}

export function Column(props: { children?: JSX.Element; class?: string; testID?: string }) {
  return (
    <View testID={props.testID} class={`flex-col ${props.class ?? ""}`}>
      {props.children}
    </View>
  );
}

export function Row(props: { children?: JSX.Element; class?: string; testID?: string }) {
  return (
    <View testID={props.testID} class={`flex-row ${props.class ?? ""}`}>
      {props.children}
    </View>
  );
}
export function Card(props: {
  children?: JSX.Element;
  class?: string;
  testID?: string;
  animateEntrance?: boolean;
  entrance?: MotionName;
  delay?: number;
}) {
  const motion = props.animateEntrance
    ? createSurfaceMotion(props.entrance ?? "fadeInUp", props.delay ?? 0)
    : null;
  return (
    <View
      testID={props.testID}
      style={motion?.style}
      class={`gap-3 rounded-(--radius-lg) border border-border bg-card p-4 ${props.class ?? ""}`}
    >
      {props.children}
    </View>
  );
}

export function Divider(props: { class?: string }) {
  return <View class={`h-px bg-border ${props.class ?? ""}`} />;
}

export function Heading(props: { children: string; class?: string }) {
  return (
    <Text role="header" class={`text-base font-semibold text-foreground ${props.class ?? ""}`}>
      {props.children}
    </Text>
  );
}

export function SectionTitle(props: { children: string; class?: string }) {
  return (
    <Text role="header" class={`text-sm font-semibold text-card-foreground ${props.class ?? ""}`}>
      {props.children}
    </Text>
  );
}

export function BodyText(props: { children: string; class?: string; testID?: string }) {
  return (
    <Text testID={props.testID} class={`text-sm text-foreground ${props.class ?? ""}`}>
      {props.children}
    </Text>
  );
}

export function MutedText(props: { children: string; class?: string; testID?: string }) {
  return (
    <Text testID={props.testID} class={`text-xs text-muted-foreground ${props.class ?? ""}`}>
      {props.children}
    </Text>
  );
}

export function LabelText(props: { children: string; class?: string }) {
  return (
    <Text class={`text-xs font-medium text-muted-foreground ${props.class ?? ""}`}>
      {props.children}
    </Text>
  );
}

export function MonoText(props: { children: string; class?: string; testID?: string }) {
  return (
    <Text testID={props.testID} class={`font-mono text-xs text-foreground ${props.class ?? ""}`}>
      {props.children}
    </Text>
  );
}

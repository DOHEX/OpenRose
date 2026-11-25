import type { Bounds, Point, Size } from "nsh";
import type { Display } from "nsh/desktop";

function containsPoint(bounds: Bounds, point: Point): boolean {
  return (
    point.x >= bounds.origin.x &&
    point.x < bounds.origin.x + bounds.size.width &&
    point.y >= bounds.origin.y &&
    point.y < bounds.origin.y + bounds.size.height
  );
}

function clamp(value: number, min: number, max: number): number {
  if (max < min) return min;
  return Math.min(Math.max(value, min), max);
}

export function computeTrayPanelPosition(
  icon: Bounds,
  panelSize: Size,
  availableDisplays: readonly Display[],
): Point {
  const display =
    availableDisplays.find((item) => containsPoint(item.bounds, icon.origin)) ??
    availableDisplays.find((item) => item.primary) ??
    availableDisplays[0];
  if (!display) {
    return { x: icon.origin.x, y: icon.origin.y + icon.size.height + 4 };
  }
  const area = display.visibleBounds;
  const openBelow = icon.origin.y < area.origin.y + area.size.height / 2;
  const rawX = icon.origin.x + (icon.size.width - panelSize.width) / 2;
  const rawY = openBelow
    ? icon.origin.y + icon.size.height + 6
    : icon.origin.y - panelSize.height - 6;
  return {
    x: Math.round(clamp(rawX, area.origin.x, area.origin.x + area.size.width - panelSize.width)),
    y: Math.round(clamp(rawY, area.origin.y, area.origin.y + area.size.height - panelSize.height)),
  };
}

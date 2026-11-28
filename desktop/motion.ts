import { createMotion, reduceMotion } from "nsh";
import type { Motion, MotionName } from "nsh";

export const MOTION_DURATION = {
  normal: 200,
} as const;

export const MOTION_DISTANCE = {
  popupOffset: 4,
} as const;

export const MOTION_CLASS = {
  colorTransition: "transition-colors duration-150 ease-out motion-reduce:transition-none",
  interactiveTransition: "transition-all duration-150 ease-out active:scale-[0.98] motion-reduce:transition-none motion-reduce:active:scale-100",
  thumbTransition: "transition-transform duration-150 ease-out motion-reduce:transition-none",
  surfaceTransition: "transition-all duration-200 ease-out motion-reduce:transition-none",
  popupStarting: "opacity-0 scale-95",
  popupEnding: "opacity-0 scale-95",
} as const;

export function createSurfaceMotion(preset: MotionName = "fadeInUp", delay = 0): Motion {
  return createMotion({
    enter: preset,
    duration: MOTION_DURATION.normal,
    delay: reduceMotion() ? 0 : delay,
  });
}

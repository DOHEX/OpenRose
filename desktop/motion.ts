import { createMotion, reduceMotion } from "nsh";
import type { Motion, MotionName } from "nsh";

export const MOTION_DURATION = {
  instant: 0,
  press: 120,
  fast: 150,
  normal: 200,
  emphasis: 260,
  pulse: 1600,
} as const;

export const MOTION_EASING = {
  standard: [0.2, 0, 0, 1] as const,
  decelerate: [0.22, 1, 0.36, 1] as const,
  accelerate: [0.4, 0, 1, 1] as const,
} as const;

export const MOTION_SPRING = {
  snappy: { stiffness: 520, damping: 34, mass: 1 } as const,
  gentle: { stiffness: 360, damping: 30, mass: 1 } as const,
} as const;

export const MOTION_SCALE = {
  press: 0.98,
  popupClosed: 0.96,
} as const;

export const MOTION_DISTANCE = {
  popupOffset: 4,
  entranceOffset: 8,
} as const;

export const MOTION_CLASS = {
  colorTransition: "transition-colors duration-150 ease-out motion-reduce:transition-none",
  interactiveTransition: "transition-all duration-150 ease-out active:scale-[0.98] motion-reduce:transition-none motion-reduce:active:scale-100",
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

export function createStatusPulseMotion(): Motion {
  return createMotion();
}

export function playFeedbackMotion(motion: Motion, preset: MotionName) {
  if (reduceMotion()) {
    return;
  }
  motion.play(preset, { duration: MOTION_DURATION.emphasis });
}

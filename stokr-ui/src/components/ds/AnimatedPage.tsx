import { motion, useReducedMotion } from "framer-motion";
import type { ReactNode } from "react";
import { pageTransition, reducedMotionPageEnter } from "../../lib/motionPresets";

export function AnimatedPage({ children, pageKey }: { children: ReactNode; pageKey: string }) {
  const reduceMotion = useReducedMotion();
  const variants = reducedMotionPageEnter(reduceMotion);

  return (
    <motion.div
      key={pageKey}
      initial={variants.initial}
      animate={variants.animate}
      exit={variants.exit}
      transition={pageTransition}
      className="flex min-h-0 flex-1 flex-col"
    >
      {children}
    </motion.div>
  );
}

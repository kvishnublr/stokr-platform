# 🎨 Stokr Premium Trading Terminal — Design System

**Philosophy:** Institutional power meets modern elegance. Every pixel serves trader psychology.

---

## 🎭 Color System

### Primary Palette (Institutional Luxury)

```css
/* Backgrounds */
--bg-primary: #0a0e27;          /* Deep navy */
--bg-secondary: #11152d;        /* Slightly lighter navy */
--bg-tertiary: #1a2042;         /* Card backgrounds */
--bg-quaternary: #222d4d;       /* Hover states */

/* Accent Colors */
--accent-blue: #00d9ff;         /* Electric blue - Primary action */
--accent-emerald: #00ff9f;      /* Emerald - Positive/gains */
--accent-red: #ff3860;          /* Premium red - Losses/alerts */
--accent-purple: #c77dff;       /* Soft purple - Warnings */
--accent-gold: #ffd700;         /* Gold - Premium highlights */

/* Neutrals */
--text-primary: #ffffff;        /* Main text */
--text-secondary: #a8b5c8;      /* Secondary text */
--text-tertiary: #6b7b94;       /* Tertiary/muted */
--border-light: rgba(0, 217, 255, 0.1);
--border-medium: rgba(0, 217, 255, 0.2);
```

### Semantic Colors

```css
--status-success: #00ff9f;       /* Green trades, positive signals */
--status-warning: #c77dff;      /* Caution alerts, cooldown */
--status-critical: #ff3860;     /* Losses, blocked trades */
--status-info: #00d9ff;         /* Information, neutral alerts */
--status-neutral: #6b7b94;      /* Inactive, muted states */
```

---

## 📏 Spacing System

```css
/* Consistent 4px grid */
--space-0: 0;
--space-1: 4px;
--space-2: 8px;
--space-3: 12px;
--space-4: 16px;
--space-5: 20px;
--space-6: 24px;
--space-8: 32px;
--space-10: 40px;
--space-12: 48px;
--space-16: 64px;
```

---

## 🔤 Typography System

```css
/* Display - Hero moments */
--font-display-xl: 48px / 1.1 / 700 / -1px;
--font-display-lg: 36px / 1.2 / 700 / -0.5px;

/* Heading - Section titles */
--font-heading-lg: 24px / 1.3 / 600 / 0;
--font-heading-md: 18px / 1.4 / 600 / 0.2px;
--font-heading-sm: 14px / 1.5 / 600 / 0.3px;

/* Body - Main content */
--font-body-lg: 16px / 1.5 / 400 / 0.3px;
--font-body-md: 14px / 1.5 / 400 / 0.2px;
--font-body-sm: 12px / 1.4 / 400 / 0.1px;

/* Code - Data/numbers */
--font-mono-lg: 16px / 1.4 / 500 / 0 (JetBrains Mono);
--font-mono-md: 14px / 1.4 / 500 / 0;
--font-mono-sm: 12px / 1.4 / 500 / 0;

Font Stack: 
- Display/Heading: Inter
- Body: Inter
- Numbers: JetBrains Mono
```

---

## 🌑 Shadow & Depth System

```css
/* Glassmorphic layers */
--shadow-sm: 
  0 2px 4px rgba(0, 0, 0, 0.1),
  inset 0 1px 0 rgba(255, 255, 255, 0.1);

--shadow-md: 
  0 4px 12px rgba(0, 0, 0, 0.2),
  inset 0 1px 0 rgba(255, 255, 255, 0.15);

--shadow-lg: 
  0 8px 24px rgba(0, 0, 0, 0.3),
  inset 0 1px 0 rgba(255, 255, 255, 0.2);

--shadow-xl: 
  0 16px 48px rgba(0, 0, 0, 0.4),
  inset 0 1px 0 rgba(255, 255, 255, 0.25);

/* Glow effects */
--glow-blue: 0 0 20px rgba(0, 217, 255, 0.3);
--glow-emerald: 0 0 20px rgba(0, 255, 159, 0.3);
--glow-red: 0 0 20px rgba(255, 56, 96, 0.3);
```

---

## 🎯 Border Radius System

```css
--radius-none: 0;
--radius-sm: 4px;      /* Subtle, data-dense components */
--radius-md: 8px;      /* Default cards, inputs */
--radius-lg: 12px;     /* Hero cards, panels */
--radius-xl: 16px;     /* Large modals, drawers */
--radius-full: 9999px; /* Badges, pills */
```

---

## ⚡ Motion & Animation

### Animation Principles
- **Spring-based**: Natural, premium feel
- **Purpose-driven**: Every animation communicates
- **GPU-optimized**: transform, opacity only
- **Responsive**: Reduced motion respected

### Animation Library

```javascript
// Framer Motion presets
const animations = {
  // Entry animations
  fadeIn: {
    initial: { opacity: 0 },
    animate: { opacity: 1 },
    transition: { duration: 0.3 }
  },
  
  slideInUp: {
    initial: { opacity: 0, y: 20 },
    animate: { opacity: 1, y: 0 },
    transition: { type: "spring", stiffness: 300, damping: 30 }
  },
  
  slideInLeft: {
    initial: { opacity: 0, x: -20 },
    animate: { opacity: 1, x: 0 },
    transition: { type: "spring", stiffness: 300, damping: 30 }
  },
  
  // Hover animations
  liftHover: {
    whileHover: { y: -4, boxShadow: "var(--shadow-xl)" },
    transition: { duration: 0.2 }
  },
  
  glowHover: {
    whileHover: { boxShadow: "var(--glow-blue)" },
    transition: { duration: 0.3 }
  },
  
  // Pulse animations
  pulse: {
    animate: { opacity: [1, 0.7, 1] },
    transition: { duration: 2, repeat: Infinity }
  },
  
  pulseGlow: {
    animate: { boxShadow: ["var(--glow-blue)", "var(--glow-blue) 0 0 40px"] },
    transition: { duration: 1.5, repeat: Infinity }
  },
  
  // Data animations
  numberIncrement: {
    animate: { opacity: [0.5, 1] },
    transition: { duration: 0.6 }
  },
  
  shimmer: {
    animate: { backgroundPosition: ["0% 0%", "100% 0%"] },
    transition: { duration: 1.5, repeat: Infinity }
  }
};
```

---

## 🎨 Component Styling Patterns

### Premium Card Pattern

```jsx
<div className="
  bg-bg-tertiary
  border border-border-medium
  rounded-lg
  shadow-md
  backdrop-blur-xl
  hover:shadow-lg hover:border-accent-blue
  transition-all duration-300
  p-6
">
  {/* Animated border on hover */}
  <div className="absolute inset-0 rounded-lg border border-accent-blue opacity-0 hover:opacity-100 transition-opacity" />
</div>
```

### Live Data Pattern

```jsx
<motion.div
  key={data.id}
  initial={{ opacity: 0, y: 10 }}
  animate={{ opacity: 1, y: 0 }}
  className="relative"
>
  {/* Shimmer effect for live updates */}
  {isUpdating && (
    <motion.div
      animate={{ opacity: [0, 0.5, 0] }}
      transition={{ duration: 0.6 }}
      className="absolute inset-0 bg-gradient-to-r from-transparent via-accent-blue to-transparent"
    />
  )}
  {content}
</motion.div>
```

### Status Indicator Pattern

```jsx
<div className="flex items-center gap-2">
  <div className={`
    w-2 h-2 rounded-full
    animate-pulse
    ${isActive ? 'bg-accent-emerald shadow-glow-emerald' : 'bg-status-neutral'}
  `} />
  <span className="text-text-secondary">{status}</span>
</div>
```

---

## 🎭 Glassmorphism Specifications

All major panels use glass effect:

```css
backdrop-filter: blur(20px);
background: rgba(17, 21, 45, 0.4);
border: 1px solid rgba(0, 217, 255, 0.1);

/* On interactive states */
hover: 
  backdrop-filter: blur(24px);
  background: rgba(17, 21, 45, 0.6);
  border-color: rgba(0, 217, 255, 0.3);
```

---

## 📱 Responsive Breakpoints

```css
--breakpoint-xs: 320px;    /* Mobile */
--breakpoint-sm: 640px;    /* Tablet */
--breakpoint-md: 1024px;   /* Desktop */
--breakpoint-lg: 1440px;   /* Ultrawide */
--breakpoint-xl: 1920px;   /* 2K+ */
```

---

## 🎯 Component State Patterns

### Default State
- Clean, minimal
- Secondary text color
- Standard shadows

### Hover State
- Border color: accent-blue
- Shadow elevated
- Subtle lift (y: -4px)
- Slightly higher opacity

### Active State
- Border color: accent-blue
- Background elevated
- Glow effect
- Full opacity

### Loading State
- Shimmer animation
- Skeleton placeholders
- Disabled interactions
- Subtle pulsing

### Error State
- Border color: accent-red
- Glow: accent-red
- Error icon + message
- Attention red accent

### Success State
- Border color: accent-emerald
- Glow: accent-emerald
- Checkmark icon
- Positive reinforcement

---

## 🎬 Micro-Interaction Guidelines

1. **Button Clicks**: Spring bounce (stiffness: 400, damping: 25)
2. **Card Opens**: Fade + slide up (200ms)
3. **Data Updates**: Shimmer effect (600ms)
4. **Toggle Switches**: Spring rotate (stiffness: 300, damping: 20)
5. **Live Indicators**: Continuous pulse (2s cycle)
6. **Notifications**: Float in (300ms) + auto-dismiss

---

## 💡 Usage Examples

### Premium Alert Card
```jsx
<motion.div 
  initial={{ opacity: 0, x: -20 }}
  animate={{ opacity: 1, x: 0 }}
  className="bg-bg-tertiary border border-accent-red rounded-lg p-4 shadow-md"
>
  <div className="flex gap-3">
    <AlertIcon className="text-accent-red" />
    <div>
      <h4 className="font-heading-sm text-text-primary">Risk Alert</h4>
      <p className="text-text-secondary text-body-sm">Position limit exceeded</p>
    </div>
  </div>
</motion.div>
```

### Live Data Widget
```jsx
<motion.div className="bg-bg-tertiary rounded-lg p-6 shadow-md">
  <motion.h3 
    className="font-heading-md text-accent-blue"
    animate={isUpdating ? { opacity: [1, 0.7, 1] } : {}}
    transition={{ duration: 0.6 }}
  >
    {value}
  </motion.h3>
  <p className="text-text-secondary">Live • {timestamp}</p>
</motion.div>
```

---

## 🎨 Tailwind Configuration

```javascript
// tailwind.config.js
module.exports = {
  theme: {
    colors: {
      bg: {
        primary: '#0a0e27',
        secondary: '#11152d',
        tertiary: '#1a2042',
        quaternary: '#222d4d'
      },
      accent: {
        blue: '#00d9ff',
        emerald: '#00ff9f',
        red: '#ff3860',
        purple: '#c77dff',
        gold: '#ffd700'
      },
      text: {
        primary: '#ffffff',
        secondary: '#a8b5c8',
        tertiary: '#6b7b94'
      }
    },
    extend: {
      backdropBlur: {
        xl: 'blur(20px)'
      },
      boxShadow: {
        glow: '0 0 20px rgba(0, 217, 255, 0.3)',
        'glow-emerald': '0 0 20px rgba(0, 255, 159, 0.3)',
        'glow-red': '0 0 20px rgba(255, 56, 96, 0.3)'
      }
    }
  }
};
```

---

**This design system ensures every component looks premium, feels alive, and serves trader psychology.**


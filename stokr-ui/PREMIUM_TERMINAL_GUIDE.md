# 🎨 Premium Stokr Terminal — Implementation Guide

**Status:** Production-Ready Design System  
**Created:** May 25, 2026  
**Version:** 1.0

---

## 📚 Overview

This guide walks through implementing the premium, institutional-grade Intraday Trading Terminal redesign.

**What's Included:**
- ✅ Complete design system (colors, spacing, typography)
- ✅ Premium React components with animations
- ✅ Tailwind configuration
- ✅ Framer Motion animation presets
- ✅ Full Intraday Terminal page
- ✅ Responsive layouts
- ✅ Dark mode optimized

---

## 🚀 Quick Start

### 1. Install Dependencies

```bash
npm install framer-motion clsx
```

### 2. Update Tailwind Config

Use the new premium configuration:

```bash
# Replace your tailwind.config.js with the premium version
cp stokr-ui/tailwind.config.premium.js stokr-ui/tailwind.config.js
```

### 3. Import Components

```tsx
import { PremiumCard } from '@/components/premium/PremiumCard';
import { LiveDataWidget } from '@/components/premium/LiveDataWidget';
import { StrategyCard } from '@/components/premium/StrategyCard';
import { PremiumIntradayTerminal } from '@/pages/premium/PremiumIntradayTerminal';
```

### 4. Use in Routes

```tsx
// In your router configuration
import PremiumIntradayTerminal from '@/pages/premium/PremiumIntradayTerminal';

routes = [
  { path: '/intraday', element: <PremiumIntradayTerminal /> },
  // ... other routes
];
```

---

## 🎨 Color System

All colors are in the design system. Use with Tailwind classes:

### Background Colors
```tsx
<div className="bg-slate-950">Primary background</div>
<div className="bg-slate-900">Secondary panels</div>
```

### Accent Colors
```tsx
<div className="text-cyan-400">Electric blue accent</div>
<div className="text-emerald-400">Success/positive</div>
<div className="text-red-500">Critical/losses</div>
<div className="text-purple-400">Warnings</div>
```

### Utilities
```tsx
{/* Glassmorphism */}
<div className="glass glass-hover">...</div>

{/* Glows */}
<div className="shadow-glow">Blue glow</div>
<div className="shadow-glow-emerald">Green glow</div>
<div className="shadow-glow-red">Red glow</div>
```

---

## 🧩 Component Usage

### Premium Card

Basic card with glass effect and hover animations:

```tsx
<PremiumCard
  glow="blue"
  interactive={true}
  animated={true}
  status="active"
  className="p-6"
>
  <h3>Card Title</h3>
  <p>Card content goes here</p>
</PremiumCard>
```

**Props:**
- `glow`: 'blue' | 'emerald' | 'red' | 'purple' | 'none'
- `interactive`: Show hover lift effect
- `animated`: Fade-in animation on mount
- `status`: 'default' | 'active' | 'warning' | 'error' | 'success'

### Live Data Widget

Displays real-time data with shimmer animations:

```tsx
<LiveDataWidget
  label="Day PnL"
  value={2450}
  unit="₹"
  change={1.24}
  isPositive={true}
  isLive={true}
  trend="up"
/>
```

**Props:**
- `label`: Widget label text
- `value`: The metric value
- `unit`: Optional unit (₹, %, ms, etc.)
- `change`: Percentage change
- `isPositive`: Color green or red
- `isLive`: Show shimmer animation
- `trend`: 'up' | 'down' | 'neutral'

### Strategy Card

Shows strategy status and real-time metrics:

```tsx
<StrategyCard
  strategyName="Gap Fills"
  symbol="NIFTY_50"
  status="running"
  confidence={78}
  tradeProb={68}
  regime="MEAN_REVERSION"
  signalStrength={85}
  activeOrders={2}
  dayPnL={2.4}
/>
```

**Props:**
- `status`: 'running' | 'cooling' | 'idle' | 'blocked'
- `confidence`: 0-100
- `tradeProb`: 0-100 (trade probability)
- `signalStrength`: 0-100
- `activeOrders`: Current active orders
- `dayPnL`: Day's profit/loss percentage

---

## ⚡ Animations

### Framer Motion Patterns

**Fade In:**
```tsx
<motion.div
  initial={{ opacity: 0 }}
  animate={{ opacity: 1 }}
  transition={{ duration: 0.3 }}
/>
```

**Slide Up:**
```tsx
<motion.div
  initial={{ opacity: 0, y: 20 }}
  animate={{ opacity: 1, y: 0 }}
  transition={{ type: 'spring', stiffness: 300, damping: 30 }}
/>
```

**Hover Lift:**
```tsx
<motion.div
  whileHover={{ y: -4, boxShadow: '0 8px 24px ...' }}
  transition={{ duration: 0.2 }}
/>
```

**Pulse Animation:**
```tsx
<motion.div
  animate={{ opacity: [1, 0.7, 1] }}
  transition={{ duration: 2, repeat: Infinity }}
/>
```

**Number Counter:**
```tsx
<motion.div
  animate={{ opacity: [0.8, 1] }}
  transition={{ duration: 0.6 }}
  key={value}
>
  {value}
</motion.div>
```

---

## 🌈 Layout Patterns

### Glassmorphic Panel

```tsx
<div className="
  backdrop-blur-xl
  bg-slate-900/40
  border border-cyan-500/20
  rounded-lg
  shadow-md
  shadow-inner shadow-white/5
  p-6
">
  Content
</div>
```

### Status Indicator

```tsx
<div className="flex items-center gap-2">
  <motion.div
    animate={{ opacity: [1, 0.5, 1] }}
    transition={{ duration: 2, repeat: Infinity }}
    className="w-2 h-2 rounded-full bg-emerald-400"
  />
  <span className="text-emerald-400">Active</span>
</div>
```

### Metric Row

```tsx
<div className="flex justify-between items-center py-2 border-b border-white/5">
  <span className="text-text-secondary">Metric Name</span>
  <span className="font-mono font-bold text-cyan-400">123.45</span>
</div>
```

---

## 📱 Responsive Design

The terminal is fully responsive:

```tsx
// Grid layout adapts to screen size
<div className="grid grid-cols-12 gap-6">
  <aside className="col-span-3 md:col-span-4">Sidebar</aside>
  <section className="col-span-4 md:col-span-3">Center</section>
  <section className="col-span-5 md:col-span-5">Right</section>
</div>
```

**Breakpoints:**
- `sm`: 640px
- `md`: 1024px
- `lg`: 1440px
- `xl`: 1920px

---

## 🔌 Connecting to Real Data

Replace mock data with live API calls:

```tsx
// Current (Mock)
const [strategies, setStrategies] = useState([...mock]);

// Updated (Live)
useEffect(() => {
  const unsubscribe = marketDataService.subscribeToStrategies(
    (data) => setStrategies(data)
  );
  return unsubscribe;
}, []);
```

### WebSocket Integration

```tsx
// Connect live market data
useEffect(() => {
  const ws = new WebSocket('wss://api.stokr.in/live/market');

  ws.onmessage = (event) => {
    const tick = JSON.parse(event.data);
    setMarketData(prev => ({
      ...prev,
      [tick.symbol]: tick
    }));
  };

  return () => ws.close();
}, []);
```

---

## 🎯 Customization

### Change Color Scheme

Edit in `tailwind.config.premium.js`:

```javascript
colors: {
  accent: {
    blue: '#00d9ff',    // ← Change primary accent
    emerald: '#00ff9f', // ← Change success color
    // ... etc
  }
}
```

### Adjust Spacing

Edit spacing system in the same file:

```javascript
spacing: {
  4: '4px',   // 1 unit
  6: '24px',  // 1.5 units
  8: '32px',  // 2 units
  // ... etc
}
```

### Animation Timing

Modify Framer Motion presets in components:

```tsx
// Slower animations
transition={{ type: 'spring', stiffness: 200, damping: 40 }}

// Faster animations
transition={{ type: 'spring', stiffness: 400, damping: 25 }}
```

---

## 📊 Component Showcase

### All Components in One View

```tsx
import { PremiumCard, LiveDataWidget, StrategyCard } from '@/components/premium';

export function ComponentShowcase() {
  return (
    <div className="p-8 space-y-6">
      {/* Premium Cards */}
      <PremiumCard glow="blue">
        <h3>Premium Card - Blue Glow</h3>
      </PremiumCard>

      <PremiumCard glow="emerald" status="success">
        <h3>Premium Card - Success</h3>
      </PremiumCard>

      {/* Live Widgets */}
      <div className="grid grid-cols-4 gap-4">
        <LiveDataWidget label="PnL" value="2,450" unit="₹" isLive />
        <LiveDataWidget label="Risk" value="46" unit="%" />
        <LiveDataWidget label="Orders" value="12" />
        <LiveDataWidget label="Health" value="99.8" unit="%" />
      </div>

      {/* Strategy Cards */}
      <div className="grid grid-cols-3 gap-4">
        <StrategyCard
          strategyName="Gap Fills"
          symbol="NIFTY_50"
          status="running"
          confidence={78}
          // ... more props
        />
        {/* More strategy cards */}
      </div>
    </div>
  );
}
```

---

## 🚨 Performance Optimization

### Virtualization for Large Lists

```tsx
import { FixedSizeList } from 'react-window';

<FixedSizeList
  height={600}
  itemCount={strategies.length}
  itemSize={120}
  width="100%"
>
  {({ index, style }) => (
    <StrategyCard
      style={style}
      {...strategies[index]}
    />
  )}
</FixedSizeList>
```

### Memoization

```tsx
const MemoizedStrategyCard = React.memo(StrategyCard);
const MemoizedLiveWidget = React.memo(LiveDataWidget);
```

### Lazy Loading

```tsx
const PremiumTerminal = lazy(() =>
  import('@/pages/premium/PremiumIntradayTerminal')
);

<Suspense fallback={<LoadingSpinner />}>
  <PremiumTerminal />
</Suspense>
```

---

## 🧪 Testing

### Component Tests

```tsx
import { render, screen } from '@testing-library/react';
import { StrategyCard } from '@/components/premium/StrategyCard';

test('displays strategy status', () => {
  render(
    <StrategyCard
      strategyName="Test Strategy"
      symbol="TEST"
      status="running"
      confidence={75}
      tradeProb={60}
      signalStrength={80}
    />
  );

  expect(screen.getByText('Test Strategy')).toBeInTheDocument();
  expect(screen.getByText('RUNNING')).toBeInTheDocument();
});
```

### Visual Regression Tests

```tsx
// Use Percy or similar for visual testing
describe('Premium Components', () => {
  it('renders strategy card correctly', async () => {
    const { container } = render(<StrategyCard {...props} />);
    await percySnapshot(container);
  });
});
```

---

## 📚 Typography

All text uses the Tailwind text utilities:

```tsx
<h1 className="text-5xl font-bold">Display XL</h1>
<h2 className="text-3xl font-semibold">Heading LG</h2>
<h3 className="text-2xl font-semibold">Heading MD</h3>
<p className="text-base">Body text</p>
<p className="text-sm text-text-secondary">Secondary text</p>
<code className="font-mono text-sm">Code/monospace</code>
```

---

## 🎬 Animation Library

Pre-built animation configs available:

```tsx
// In your component
const animations = {
  fadeIn: { initial: { opacity: 0 }, animate: { opacity: 1 } },
  slideInUp: { initial: { y: 20, opacity: 0 }, animate: { y: 0, opacity: 1 } },
  // ... etc from DESIGN_SYSTEM.md
};

<motion.div {...animations.fadeIn}>Content</motion.div>
```

---

## 🚀 Deployment Checklist

- [ ] Update Tailwind config to premium version
- [ ] Install framer-motion dependency
- [ ] Create `/components/premium/` directory with all components
- [ ] Create `/pages/premium/` directory with new pages
- [ ] Update router to include new routes
- [ ] Connect real data APIs
- [ ] Test responsive layouts on mobile/tablet/desktop
- [ ] Test dark mode (already default)
- [ ] Performance audit (Lighthouse)
- [ ] Accessibility check (WCAG)
- [ ] Browser compatibility (Chrome, Firefox, Safari, Edge)

---

## 📞 Support

For issues or questions:
1. Check the DESIGN_SYSTEM.md for detailed specs
2. Review component prop types in source files
3. Test with mock data first
4. Check console for errors
5. Verify Tailwind config is properly imported

---

**🎉 Congratulations! You now have a premium, institutional-grade trading terminal.**

Next steps:
1. Integrate with live data APIs
2. Add real-time WebSocket updates
3. Implement user preferences/customization
4. Add dark/light mode toggle
5. Performance optimization for high-frequency data


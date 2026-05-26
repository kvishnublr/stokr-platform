# UI Integration: Historical Data Loader

## Components Created

### 1. BacktestHistoricalDataLoader Component
**Location:** `stokr-ui/src/components/admin/BacktestHistoricalDataLoader.tsx`

Self-contained React component with:
- ✅ Start/stop load controls
- ✅ Real-time progress tracking (auto-refresh every 5 seconds)
- ✅ Symbol input field (optional, comma-separated)
- ✅ Status metrics (candles loaded, symbols completed, elapsed time, failures)
- ✅ Progress bar with percentage
- ✅ Symbols loaded table (scrollable)
- ✅ Failed symbols list
- ✅ Time estimation info
- ✅ Responsive design with Tailwind CSS

### 2. AdminBacktestDataPage
**Location:** `stokr-ui/src/pages/admin/AdminBacktestDataPage.tsx`

Full page wrapper for the component. Can be accessed via route:
```
/admin/backtest-data
```

---

## How to Integrate into Your Admin Menu

### Step 1: Add to Router

In your admin routing configuration (e.g., `src/routes/adminRoutes.ts` or similar):

```typescript
import { AdminBacktestDataPage } from "../pages/admin/AdminBacktestDataPage";

const adminRoutes = [
  // ... other routes ...
  {
    path: "/admin/backtest-data",
    element: <AdminBacktestDataPage />,
    label: "Backtest Data",
    icon: "Download", // or your icon library
  },
];
```

### Step 2: Add Menu Item

In your admin menu/navigation (e.g., `src/components/admin/AdminSidebar.tsx`):

```tsx
<NavLink to="/admin/backtest-data">
  <Download className="h-4 w-4" />
  Backtest Data
</NavLink>
```

### Step 3: Update Navigation Config

If you have a centralized menu configuration:

```typescript
{
  section: "Data Management",
  items: [
    { path: "/admin/backtest-data", label: "Backtest Data", icon: "Download" },
    { path: "/admin/backfill", label: "Market Data Backfill", icon: "BarChart" },
  ],
}
```

---

## Using Just the Component

If you want to embed the component in an existing admin page (e.g., Dashboard):

```tsx
import { BacktestHistoricalDataLoader } from "../../components/admin/BacktestHistoricalDataLoader";

export function AdminDashboard() {
  return (
    <div className="space-y-6">
      {/* Other sections */}
      
      <BacktestHistoricalDataLoader />
    </div>
  );
}
```

---

## Features

### Real-Time Progress

The component automatically polls the backend every 5 seconds while loading is active:

```
GET /api/v1/admin/backtest-data/progress
```

Displays:
- Total candles loaded
- Symbols completed
- Failed symbols
- Elapsed time
- Symbol-by-symbol progress table

### Start Load

Click "Start Background Load" button to trigger:

```
POST /api/v1/admin/backtest-data/start-load?symbols=INFY,TCS,WIPRO
```

Optional symbols parameter (comma-separated). Leave empty to load all.

### Status Indicators

- **LOADING** badge when process is running
- **READY** badge when idle
- Green checkmark when complete
- Red alert for failed symbols
- Progress bar with percentage

### Symbol Management

- Input field for comma-separated symbols
- Disabled during load to prevent concurrent operations
- Examples shown as placeholder
- Easy retry for failed symbols

---

## API Integration

The component uses these endpoints:

```
POST   /api/v1/admin/backtest-data/start-load
       Query params: symbols=INFY,TCS (optional)
       Response: { status, message, expected_duration }

GET    /api/v1/admin/backtest-data/progress
       Response: { status, progress: { running, total_candles_loaded, ... } }

GET    /api/v1/admin/backtest-data/summary
       Response: { status, total_candles_loaded, symbols_completed, ... }

GET    /api/v1/admin/backtest-data/health
       Response: { loader_running, ready_for_backtest, ... }
```

---

## Styling

Uses your existing Tailwind CSS setup with:

- `card` component (Card, CardContent, CardDescription, CardHeader, CardTitle)
- `button` component (Button with variants)
- `badge` component (Badge with variants)
- Lucide icons (Download, Play, RefreshCw, AlertCircle, CheckCircle2, Clock, TrendingUp)

If you don't have these components, they're standard shadcn/ui patterns:

```bash
# Install shadcn/ui components
npx shadcn-ui@latest add card
npx shadcn-ui@latest add button
npx shadcn-ui@latest add badge
```

---

## Usage Examples

### Example 1: Load All Symbols

1. Click "Start Background Load"
2. Leave symbol field empty
3. Monitor progress in real-time

### Example 2: Load Specific Symbols

1. Enter: `INFY,TCS,WIPRO,RELIANCE,BAJAJFINSV`
2. Click "Start Background Load"
3. Component shows progress for each symbol

### Example 3: Retry Failed Symbols

1. Note failed symbols from the list
2. Enter failed symbols in input field
3. Click "Start Background Load" again

---

## Customization

### Change Auto-Refresh Rate

In `BacktestHistoricalDataLoader.tsx`, line ~60:

```typescript
refetchInterval: (data) => {
  // Change these intervals (in milliseconds)
  return data?.progress.running ? 5000 : 30000;  // Currently 5s loading, 30s idle
}
```

### Change Time Format

Function `formatTime()` at top of component - modify to match your preference.

### Change Candle Format

Function `formatCandles()` - adjust thresholds as needed.

### Theme Colors

All colors use Tailwind classes:
- `bg-slate-50`, `bg-blue-50`, `bg-amber-50`, `bg-green-50`, `bg-red-50`
- `text-slate-900`, `text-blue-900`, etc.

Modify these classes for different color schemes.

---

## Files to Commit

```
stokr-ui/src/components/admin/BacktestHistoricalDataLoader.tsx
stokr-ui/src/pages/admin/AdminBacktestDataPage.tsx
BACKTEST_UI_INTEGRATION.md (this file)
```

---

## Testing the UI

Once integrated, you can test:

1. **Start Load**
   ```bash
   # Click button or via curl
   curl -X POST http://localhost:3000/api/v1/admin/backtest-data/start-load
   ```

2. **View Progress**
   ```bash
   # Check component updates in real-time
   curl http://localhost:3000/api/v1/admin/backtest-data/progress
   ```

3. **Load Specific Symbols**
   ```bash
   # Enter in UI input field: INFY,TCS,WIPRO
   # Or via curl: 
   curl -X POST "http://localhost:3000/api/v1/admin/backtest-data/start-load?symbols=INFY,TCS,WIPRO"
   ```

---

## Troubleshooting

### Component Not Updating

Check browser console for API errors:
```javascript
// Chrome DevTools → Console
// Look for 404, 403, or other API errors
```

### Backend Not Responding

Ensure backend is running:
```bash
# Check backend logs
curl http://localhost:8080/api/v1/admin/backtest-data/health
```

### Toast Notifications Not Showing

Ensure `sonner` is properly configured in your app:
```tsx
import { Toaster } from "sonner";

function App() {
  return (
    <>
      <Toaster />
      {/* app content */}
    </>
  );
}
```

---

## UI Screenshots Description

### Loading State
- Progress bar showing 35% complete
- "LOADING" badge (blue)
- Real-time metrics: 128K candles, 8 symbols, 12m elapsed
- Table showing loaded symbols with their candle counts
- Auto-refresh indicator with last updated timestamp

### Completed State
- Progress bar at 100%
- "READY" badge (gray)
- Green checkmark with summary message
- Full symbols table with all 50 symbols listed
- No failed symbols
- Final statistics: 261K candles, 50 symbols, 2h 15m

### Input State
- Symbol input field with placeholder text
- "Start Background Load" button enabled
- Example symbols shown: INFY, TCS, WIPRO, RELIANCE
- Info box explaining expected duration

---

## Summary

✅ **Component created** - `BacktestHistoricalDataLoader.tsx`  
✅ **Page created** - `AdminBacktestDataPage.tsx`  
✅ **API integration** - Ready for backend endpoints  
✅ **Real-time updates** - Auto-polling every 5 seconds  
✅ **Error handling** - Toast notifications for errors  
✅ **Responsive** - Works on desktop, tablet, mobile  
✅ **Styled** - Tailwind CSS with consistent design  

**Ready to integrate into admin dashboard!**

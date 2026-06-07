import { motion } from "framer-motion";
import { TrendingUp, TrendingDown, DollarSign, Activity, Zap, Target, ArrowUpRight, ArrowDownLeft, MoreVertical } from "lucide-react";

const containerVariants = {
  hidden: { opacity: 0 },
  show: {
    opacity: 1,
    transition: {
      staggerChildren: 0.1,
      delayChildren: 0.2,
    },
  },
};

const itemVariants = {
  hidden: { opacity: 0, y: 20 },
  show: {
    opacity: 1,
    y: 0,
    transition: {
      duration: 0.6,
      ease: "easeOut",
    },
  },
};

export function ModernDashboard() {
  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-900 via-slate-800 to-slate-900 p-8">
      {/* Background Animations */}
      <motion.div
        className="fixed top-0 left-0 w-full h-full pointer-events-none -z-10"
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ duration: 1 }}
      >
        <motion.div
          className="absolute top-40 left-1/4 w-96 h-96 rounded-full blur-3xl"
          style={{
            background: "linear-gradient(135deg, rgba(99, 102, 241, 0.15) 0%, rgba(168, 85, 247, 0.05) 100%)",
          }}
          animate={{
            y: [0, 30, 0],
          }}
          transition={{ duration: 6, repeat: Infinity }}
        />
        <motion.div
          className="absolute bottom-40 right-1/4 w-96 h-96 rounded-full blur-3xl"
          style={{
            background: "linear-gradient(135deg, rgba(34, 197, 94, 0.1) 0%, rgba(99, 102, 241, 0.05) 100%)",
          }}
          animate={{
            y: [0, -30, 0],
          }}
          transition={{ duration: 8, repeat: Infinity }}
        />
      </motion.div>

      <div className="relative z-10 max-w-7xl mx-auto">
        {/* Header */}
        <motion.div
          className="mb-12"
          initial={{ opacity: 0, y: -20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.8 }}
        >
          <h1 className="text-4xl font-bold text-white mb-2">Trading Dashboard</h1>
          <p className="text-white/60">Welcome back! Here's your trading performance</p>
        </motion.div>

        {/* Key Metrics */}
        <motion.div
          className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6 mb-8"
          variants={containerVariants}
          initial="hidden"
          animate="show"
        >
          {[
            {
              title: "Portfolio Value",
              value: "₹24,580",
              change: "+12.5%",
              icon: DollarSign,
              trend: "up",
              color: "from-emerald-500 to-green-600",
            },
            {
              title: "Today's P&L",
              value: "₹2,450",
              change: "+8.2%",
              icon: TrendingUp,
              trend: "up",
              color: "from-blue-500 to-cyan-600",
            },
            {
              title: "Active Strategies",
              value: "12",
              change: "2 alerts",
              icon: Activity,
              trend: "neutral",
              color: "from-purple-500 to-pink-600",
            },
            {
              title: "Risk Score",
              value: "42/100",
              change: "-5.2%",
              icon: Target,
              trend: "down",
              color: "from-orange-500 to-red-600",
            },
          ].map((metric, i) => (
            <motion.div key={i} variants={itemVariants}>
              <motion.div
                className="backdrop-blur-xl bg-white/10 border border-white/20 rounded-2xl p-6 h-full hover:border-white/40 transition-all duration-300 group"
                whileHover={{ y: -8, scale: 1.02 }}
                transition={{ duration: 0.3 }}
              >
                <div className="flex items-start justify-between mb-4">
                  <div className={`p-3 rounded-lg bg-gradient-to-br ${metric.color}`}>
                    <metric.icon className="w-6 h-6 text-white" />
                  </div>
                  <motion.button
                    className="p-2 hover:bg-white/10 rounded-lg text-white/60 hover:text-white transition-all"
                    whileHover={{ rotate: 90 }}
                  >
                    <MoreVertical className="w-4 h-4" />
                  </motion.button>
                </div>

                <p className="text-white/60 text-sm font-medium mb-2">{metric.title}</p>
                <h3 className="text-3xl font-bold text-white mb-3">{metric.value}</h3>

                <div
                  className={`flex items-center gap-1 text-sm font-semibold ${
                    metric.trend === "up"
                      ? "text-emerald-400"
                      : metric.trend === "down"
                        ? "text-red-400"
                        : "text-white/60"
                  }`}
                >
                  {metric.trend === "up" && <ArrowUpRight className="w-4 h-4" />}
                  {metric.trend === "down" && <ArrowDownLeft className="w-4 h-4" />}
                  {metric.change}
                </div>
              </motion.div>
            </motion.div>
          ))}
        </motion.div>

        {/* Charts Section */}
        <motion.div
          className="grid grid-cols-1 lg:grid-cols-3 gap-6 mb-8"
          variants={containerVariants}
          initial="hidden"
          animate="show"
        >
          {/* Main Chart */}
          <motion.div
            className="lg:col-span-2"
            variants={itemVariants}
          >
            <motion.div
              className="backdrop-blur-xl bg-white/10 border border-white/20 rounded-2xl p-8 h-80"
              whileHover={{ borderColor: "rgba(255, 255, 255, 0.4)" }}
              transition={{ duration: 0.3 }}
            >
              <h2 className="text-white font-semibold mb-6">Performance Chart</h2>

              {/* Animated Chart Placeholder */}
              <div className="flex items-end justify-between h-64 gap-2">
                {[65, 45, 75, 55, 85, 60, 90, 70].map((height, i) => (
                  <motion.div
                    key={i}
                    className="flex-1 rounded-t-lg bg-gradient-to-t from-indigo-500 to-purple-400 relative group cursor-pointer"
                    style={{ height: `${height}%` }}
                    initial={{ height: 0 }}
                    animate={{ height: `${height}%` }}
                    transition={{
                      duration: 1,
                      delay: i * 0.1,
                      ease: "easeOut",
                    }}
                    whileHover={{
                      scale: 1.1,
                      filter: "brightness(1.2)",
                    }}
                  >
                    <motion.div
                      className="absolute -top-8 left-1/2 -translate-x-1/2 text-white text-xs font-semibold opacity-0 group-hover:opacity-100 transition-opacity"
                      initial={{ y: 10 }}
                      whileHover={{ y: 0 }}
                    >
                      {height}%
                    </motion.div>
                  </motion.div>
                ))}
              </div>
            </motion.div>
          </motion.div>

          {/* Stats Card */}
          <motion.div variants={itemVariants}>
            <motion.div
              className="backdrop-blur-xl bg-white/10 border border-white/20 rounded-2xl p-8 h-80"
              whileHover={{ borderColor: "rgba(255, 255, 255, 0.4)" }}
            >
              <h2 className="text-white font-semibold mb-6">Quick Stats</h2>
              <div className="space-y-4">
                {[
                  { label: "Win Rate", value: "68%", color: "from-emerald-500 to-green-600" },
                  { label: "Total Trades", value: "234", color: "from-blue-500 to-cyan-600" },
                  { label: "Avg Return", value: "4.2%", color: "from-purple-500 to-pink-600" },
                ].map((stat, i) => (
                  <motion.div
                    key={i}
                    className="flex items-center justify-between"
                    initial={{ opacity: 0, x: -20 }}
                    animate={{ opacity: 1, x: 0 }}
                    transition={{ duration: 0.5, delay: 0.3 + i * 0.1 }}
                  >
                    <span className="text-white/60 text-sm">{stat.label}</span>
                    <span className={`text-lg font-bold bg-gradient-to-r ${stat.color} bg-clip-text text-transparent`}>
                      {stat.value}
                    </span>
                  </motion.div>
                ))}
              </div>
            </motion.div>
          </motion.div>
        </motion.div>

        {/* Recent Trades */}
        <motion.div
          variants={itemVariants}
          initial="hidden"
          animate="show"
          transition={{ delay: 0.6 }}
        >
          <motion.div
            className="backdrop-blur-xl bg-white/10 border border-white/20 rounded-2xl p-8"
            whileHover={{ borderColor: "rgba(255, 255, 255, 0.4)" }}
          >
            <h2 className="text-white font-semibold mb-6">Recent Trades</h2>

            <div className="space-y-4">
              {[
                { symbol: "SBIN", quantity: "50", price: "₹645.20", status: "✓ Completed", profit: "+₹2,450", color: "emerald" },
                { symbol: "INFY", quantity: "30", price: "₹2,845.75", status: "→ Pending", profit: "-₹125", color: "orange" },
                { symbol: "BAJAJ", quantity: "20", price: "₹7,245.00", status: "✓ Completed", profit: "+₹890", color: "emerald" },
              ].map((trade, i) => (
                <motion.div
                  key={i}
                  className="flex items-center justify-between p-4 rounded-xl bg-white/5 hover:bg-white/10 border border-white/10 transition-all cursor-pointer group"
                  initial={{ opacity: 0, x: -20 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ duration: 0.5, delay: 0.7 + i * 0.1 }}
                  whileHover={{ scale: 1.02, x: 8 }}
                >
                  <div>
                    <p className="text-white font-semibold">{trade.symbol}</p>
                    <p className="text-white/60 text-sm">{trade.quantity} shares @ {trade.price}</p>
                  </div>
                  <div className="flex items-center gap-4">
                    <span className={`text-sm font-semibold text-${trade.color}-400`}>{trade.status}</span>
                    <span className={`font-semibold text-${trade.color}-400`}>{trade.profit}</span>
                  </div>
                </motion.div>
              ))}
            </div>
          </motion.div>
        </motion.div>
      </div>
    </div>
  );
}

capital = 500000
nifty_edge = 680
bnifty_edge = 515
blended = (nifty_edge + bnifty_edge) / 2
max_pos = 3
cycles = 5  # 4 nifty weekly + 1 banknifty monthly

print("=" * 60)
print("MONTHLY INCOME ESTIMATE -- 5L CAPITAL")
print("=" * 60)
print()
print("SCENARIO 1: BEST CASE (3 positions x 5 cycles)")
best = max_pos * blended * cycles
print("  Monthly: Rs.%s" % "{:,.0f}".format(best))
print("  Annual:  Rs.%s (%.1f%% return)" % ("{:,.0f}".format(best*12), best*12/capital*100))
print()
print("SCENARIO 2: AVERAGE (70%% occupancy)")
avg = max_pos * 0.7 * blended * cycles * 0.8
print("  Monthly: Rs.%s" % "{:,.0f}".format(avg))
print("  Annual:  Rs.%s (%.1f%% return)" % ("{:,.0f}".format(avg*12), avg*12/capital*100))
print()
print("SCENARIO 3: CONSERVATIVE (50%% occupancy)")
con = max_pos * 0.5 * blended * cycles * 0.6
print("  Monthly: Rs.%s" % "{:,.0f}".format(con))
print("  Annual:  Rs.%s (%.1f%% return)" % ("{:,.0f}".format(con*12), con*12/capital*100))
print()
print("=" * 60)
print("HONEST ANSWER")
print("=" * 60)
print()
print("  Expected: Rs.%s - Rs.%s per month" % ("{:,.0f}".format(con), "{:,.0f}".format(best)))
print("  Annual:   Rs.%s - Rs.%s" % ("{:,.0f}".format(con*12), "{:,.0f}".format(best*12)))
print("  Return:   %.0f%% - %.0f%% on 5L (risk-free)" % (con*12/capital*100, best*12/capital*100))
print()
print("  With MORE capital:")
print("  10L -> Rs.%s-%s/month (%.0f%% annual)" % ("{:,.0f}".format(con*2), "{:,.0f}".format(best*2), con*24/capital*100))
print("  25L -> Rs.%s-%s/month (%.0f%% annual)" % ("{:,.0f}".format(con*5), "{:,.0f}".format(best*5), con*60/capital*100))

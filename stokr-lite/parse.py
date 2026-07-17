import json, sys
d = json.load(open(sys.argv[1]))
for k in ['strategy','universe','totalTrades','winCount','lossCount','winRate','netPnL','totalBrokerage','avgPnL','maxDrawdown','profitFactor','maxProfitDay','maxLossDay','avgProfitDay','profitDays','lossDays','totalTradingDays']:
    print(k + ': ' + str(d.get(k, 'N/A')))

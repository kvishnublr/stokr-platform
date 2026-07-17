#!/usr/bin/env python3
"""Fix futures price estimation in OptionArbitrageController.java"""
f = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionArbitrageController.java"
with open(f) as fp:
    code = fp.read()

# Fix NIFTY futures: always use cost-of-carry as primary, since NFO quote often returns spot
old_nifty = '''                double niftyFut = spotFetcher.getSpotPrice("NFO:NIFTY26JULFUT");
                if (niftyFut <= 0 || Math.abs(niftyFut - niftySpot) > 200) {
                    // Fallback: cost-of-carry model F = S * e^(r*T)
                    double r = RISK_FREE_RATE;
                    double t = 7.0 / 365.0;
                    niftyFut = niftySpot * Math.exp(r * t);
                }'''

new_nifty = '''                double niftyFut = spotFetcher.getSpotPrice("NFO:NIFTY26JULFUT");
                // Use cost-of-carry as primary estimate if NFO quote is missing or too close to spot
                // Real NIFTY futures trade at a premium over spot; if quote is within 20pts of spot, it's likely spot echo
                double futPremiumEstimate = niftySpot * (Math.exp(RISK_FREE_RATE * 7.0 / 365.0) - 1.0);
                if (niftyFut <= 0 || Math.abs(niftyFut - niftySpot) < futPremiumEstimate * 0.5) {
                    niftyFut = niftySpot * Math.exp(RISK_FREE_RATE * 7.0 / 365.0);
                }'''

code = code.replace(old_nifty, new_nifty)

# Fix BANKNIFTY similarly
old_bank = '''                double bankNiftyFut = spotFetcher.getSpotPrice("NFO:BANKNIFTY26JULFUT");
                if (bankNiftyFut <= 0 || Math.abs(bankNiftyFut - bankNiftySpot) > 500) {
                    double r = RISK_FREE_RATE;
                    double t = 7.0 / 365.0;
                    bankNiftyFut = bankNiftySpot * Math.exp(r * t);
                }'''

new_bank = '''                double bankNiftyFut = spotFetcher.getSpotPrice("NFO:BANKNIFTY26JULFUT");
                double bankFutPremiumEstimate = bankNiftySpot * (Math.exp(RISK_FREE_RATE * 7.0 / 365.0) - 1.0);
                if (bankNiftyFut <= 0 || Math.abs(bankNiftyFut - bankNiftySpot) < bankFutPremiumEstimate * 0.5) {
                    bankNiftyFut = bankNiftySpot * Math.exp(RISK_FREE_RATE * 7.0 / 365.0);
                }'''

code = code.replace(old_bank, new_bank)

with open(f, 'w') as fp:
    fp.write(code)
print("Futures price estimation fixed")

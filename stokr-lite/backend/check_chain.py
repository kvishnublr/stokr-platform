import json

with open('/tmp/chain.json') as f:
    d = json.load(f)

print("spot:", d.get("spotPrice"))
print("fut:", d.get("futuresPrice"))
q = d.get("quotes", {})
print("total quotes:", len(q))

# Check for 25400 strike
k25400 = [k for k in q if "25400" in k]
print("25400 keys:", k25400[:5])

# Check if fetchQuotes builds symbols correctly
# buildNfoSymbol("NIFTY", LocalDate(2026,8,11), 25400, "CE")
# Format: NIFTY26AUG25400CE
target_ce = "NIFTY26AUG25400CE"
target_pe = "NIFTY26AUG25400PE"
target_fut = "NIFTY26AUGFUT"

print("Looking for:", target_ce, target_pe, target_fut)
print("CE in quotes:", target_ce in q)
print("PE in quotes:", target_pe in q)
print("FUT in quotes:", target_fut in q)

if target_ce in q:
    ce = q[target_ce]
    print("CE last:", ce.get("lastPrice"), "bid:", ce.get("bid"), "ask:", ce.get("ask"))
if target_pe in q:
    pe = q[target_pe]
    print("PE last:", pe.get("lastPrice"), "bid:", pe.get("bid"), "ask:", pe.get("ask"))
if target_fut in q:
    fut = q[target_fut]
    print("FUT last:", fut.get("lastPrice"), "bid:", fut.get("bid"), "ask:", fut.get("ask"))

# List first 10 quote keys to understand the format
print("\nFirst 10 quote keys:")
for k in sorted(q.keys())[:10]:
    print(f"  {k}")

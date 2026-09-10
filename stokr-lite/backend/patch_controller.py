import re
with open('/root/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionArbitrageController.java', 'r', encoding='utf-8') as f:
    content = f.read()

bad_str = '''    @PostMapping(value = " /paper-trade/execute\, consumes = MediaType.APPLICATION_JSON_VALUE)
 
 @PostMapping(value = \/paper-trade/execute-diff\, consumes = MediaType.APPLICATION_JSON_VALUE)'''

good_str = ''' @PostMapping(value = \/paper-trade/execute-diff\, consumes = MediaType.APPLICATION_JSON_VALUE)'''

content = content.replace(bad_str, good_str)

with open('/root/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionArbitrageController.java', 'w', encoding='utf-8') as f:
 f.write(content)

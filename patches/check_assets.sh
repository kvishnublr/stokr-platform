#!/bin/bash
DIR=/opt/stokr/ui/assets
for f in AdminAuditLog-CMqZuj44.js AdminBrokerHealth-DlDuWIap.js AdminDashboard-BHcutvbc.js AdminDeployments-CnuBOcY7.js AdminErrorLogs-D0J77UyF.js AdminKillSwitch-Cm8Ogc7j.js AdminOrders-tDMV2hwv.js AdminStrategyConfigs-BGRQjnH-.js AdminStrategyMappings-e8UOMKbP.js AdminUniverseGroups-DIKQSv1a.js AdminUsers-DLDF7XZU.js AdvancedBacktest-CqqylE0H.js Brokers-CM9tb3iS.js Dashboard-CeEghpH0.js Deployments-CLDoO5lp.js OptionArbitrage-D2ZnF9qC.js Orders-BcCDAslX.js Positions-CsHdwmIF.js Settings-C6jLf6iy.js Signals-C2gvrqU_.js Strategies-Dl3JCORM.js TraderDashboard-Cty0Kbvk.js useMutation-1TD6uSZz.js useQuery-BTHVBTCT.js rolldown-runtime-QTnfLwEv.js react-vendor-BPN2y53-.js index-D3ewvDoM.js index-DtVF8GlW.css; do
  if [ -f "$DIR/$f" ]; then
    sz=$(stat -c%s "$DIR/$f" 2>/dev/null)
    if [ "$sz" = "0" ]; then
      echo "EMPTY: $f"
    fi
  else
    echo "MISSING: $f"
  fi
done
echo "DONE"

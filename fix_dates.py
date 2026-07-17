#!/usr/bin/env python3
"""Fix: History dates endpoint - change to native query returning String"""

path_repo = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionArbOpportunityRepository.java"

with open(path_repo, 'r') as f:
    content = f.read()

old = '    @Query("SELECT DISTINCT FUNCTION(\'date\', o.scanTime) FROM OptionArbOpportunity o WHERE o.scanTime >= :since ORDER BY FUNCTION(\'date\', o.scanTime) DESC")\n    List<java.time.LocalDate> findDistinctDatesSince(@Param("since") LocalDateTime since);'

new = '    @Query(value = "SELECT DISTINCT CAST(o.scan_time AS date)::text as scan_date FROM option_arb_opportunities o WHERE o.scan_time >= :since ORDER BY scan_date DESC", nativeQuery = true)\n    List<String> findDistinctDatesSince(@Param("since") LocalDateTime since);'

if old in content:
    content = content.replace(old, new)
    with open(path_repo, 'w') as f:
        f.write(content)
    print("Fixed repository query")
else:
    if "nativeQuery = true" in content:
        print("Already fixed")
    else:
        print("Could not find old query to replace")
        # Show current query
        idx = content.find("findDistinctDatesSince")
        if idx > 0:
            print(content[max(0,idx-200):idx+200])

# Fix service - change return type to List<LocalDate> by parsing strings
path_svc = "/opt/stokr/stokr-platform/stokr-lite/backend/src/main/java/com/stokr/arbitrage/OptionArbHistoryService.java"
with open(path_svc, 'r') as f:
    svc = f.read()

old_svc = """    public List<LocalDate> getAvailableDates(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        return repository.findDistinctDatesSince(since);
    }"""

new_svc = """    public List<LocalDate> getAvailableDates(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        List<String> dateStrs = repository.findDistinctDatesSince(since);
        List<LocalDate> dates = new ArrayList<>();
        for (String ds : dateStrs) {
            try {
                dates.add(LocalDate.parse(ds));
            } catch (Exception e) {
                // skip unparseable
            }
        }
        return dates;
    }"""

if old_svc in svc:
    svc = svc.replace(old_svc, new_svc)
    # Add ArrayList import if missing
    if "import java.util.ArrayList;" not in svc and "import java.util.*" not in svc:
        svc = svc.replace("import java.util.List;", "import java.util.List;\nimport java.util.ArrayList;")
    with open(path_svc, 'w') as f:
        f.write(svc)
    print("Fixed service method")
else:
    if "List<String> dateStrs" in svc:
        print("Service already fixed")
    else:
        print("Could not find service method to fix")

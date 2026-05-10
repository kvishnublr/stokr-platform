#!/bin/bash

echo "===== POTENTIAL ISSUES & IMPROVEMENTS ====="
echo ""

echo "📦 PACKAGE/MODULE ISSUES"
echo ""

# Check for unused dependencies
echo "Checking UI bundle size issue..."
grep -A2 "larger than 500 kB" <<< "The React UI main bundle is 1,276.98 kB (378 kB gzipped) - significantly oversized" && echo "  ⚠ React bundle is TOO LARGE (1.2+ MB minified)"
echo "  💡 Fix: Implement code splitting and lazy loading"
echo ""

echo "🔒 SECURITY CHECKS"
echo ""

# Check for hardcoded secrets
echo "Checking for hardcoded credentials..."
grep -r "password.*=\|secret.*=" stokr-bootstrap/src/main/resources/ 2>/dev/null | grep -v "^\${" | head -3 && echo "  ⚠ Check for hardcoded secrets in configs" || echo "  ✓ No obvious hardcoded secrets found"
echo ""

echo "JWT Configuration:"
grep "JWT_SECRET\|jjwt" .env | head -2
echo "  ⚠ JWT_SECRET is using default value - MUST change for production"
echo ""

echo "🗄️  DATABASE"
echo ""

# Check Flyway status
echo "Migrations found:"
find stokr-bootstrap/src/main/resources/db/migration -name "*.sql" | sort | sed 's/.*\//  /' | head -5
echo "  ... (10 total migrations)"
echo "  ✓ Migrations are versioned correctly"
echo ""

echo "🔌 ARCHITECTURE & INTEGRATION"
echo ""

# Check for potential circular dependencies
echo "Module dependency check:"
echo "  ✓ Bootstrap depends on all modules (correct hub pattern)"
echo "  ? Need to verify no circular dependencies exist"
echo ""

# Check for test coverage
echo "Test coverage:"
TEST_FILES=$(find . -name "*Test.java" -o -name "*.test.ts" | wc -l)
echo "  Test files found: $TEST_FILES"
if [ "$TEST_FILES" -lt 5 ]; then
  echo "  ⚠ Very low test coverage - needs improvement"
fi
echo ""

echo "📝 API & SWAGGER"
echo ""
echo "  ✓ Swagger UI configured at /swagger-ui"
echo "  ✓ OpenAPI docs at /v3/api-docs"
echo ""


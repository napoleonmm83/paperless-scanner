#!/bin/bash
# Local Android CI/CD Deploy Script for Paperless Scanner
# Usage: ./scripts/local-deploy.sh [track]
# Tracks: internal (default), alpha, beta, production

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
TRACK="${1:-internal}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$PROJECT_DIR"

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}  Paperless Scanner - Local Deploy${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""

# Check prerequisites
check_prerequisites() {
    echo -e "${YELLOW}Checking prerequisites...${NC}"

    # Check Java
    if ! command -v java &> /dev/null; then
        echo -e "${RED}Error: Java not found. Please install JDK 21.${NC}"
        exit 1
    fi

    # Check Fastlane
    if ! command -v fastlane &> /dev/null; then
        echo -e "${RED}Error: Fastlane not found.${NC}"
        echo "Install with: gem install fastlane"
        exit 1
    fi

    # Check gradlew
    if [[ ! -f "./gradlew" ]]; then
        echo -e "${RED}Error: gradlew not found in project root.${NC}"
        exit 1
    fi

    # Check Play Store key
    if [[ ! -f "fastlane/play-store-key.json" ]]; then
        echo -e "${RED}Error: Play Store service account key not found.${NC}"
        echo "Expected: fastlane/play-store-key.json"
        exit 1
    fi

    # Check keystore environment variables
    if [[ -z "$KEYSTORE_FILE" ]]; then
        echo -e "${YELLOW}Warning: KEYSTORE_FILE not set.${NC}"
        echo "Set with: export KEYSTORE_FILE=/path/to/keystore.jks"
    fi

    echo -e "${GREEN}All prerequisites met.${NC}"
    echo ""
}

# Get current version
get_version() {
    MAJOR=$(grep VERSION_MAJOR version.properties | cut -d'=' -f2)
    MINOR=$(grep VERSION_MINOR version.properties | cut -d'=' -f2)
    PATCH=$(grep VERSION_PATCH version.properties | cut -d'=' -f2)
    VERSION="${MAJOR}.${MINOR}.${PATCH}"
    VERSION_CODE=$((MAJOR * 10000 + MINOR * 100 + PATCH))
    echo -e "${BLUE}Current Version: ${VERSION} (code: ${VERSION_CODE})${NC}"
}

# Run tests
run_tests() {
    echo -e "${YELLOW}Running unit tests...${NC}"
    ./gradlew testDebugUnitTest --no-daemon
    if [[ $? -ne 0 ]]; then
        echo -e "${RED}Tests failed! Aborting deploy.${NC}"
        exit 1
    fi
    echo -e "${GREEN}Tests passed.${NC}"
    echo ""
}

# Run lint check
run_lint() {
    echo -e "${YELLOW}Running lint check...${NC}"
    ./gradlew lintDebug --no-daemon
    if [[ $? -ne 0 ]]; then
        echo -e "${RED}Lint check failed! Aborting deploy.${NC}"
        exit 1
    fi
    echo -e "${GREEN}Lint check passed.${NC}"
    echo ""
}

# Bump version (optional)
bump_version() {
    if [[ "$BUMP_VERSION" == "true" ]]; then
        echo -e "${YELLOW}Bumping patch version...${NC}"

        MAJOR=$(grep VERSION_MAJOR version.properties | cut -d'=' -f2)
        MINOR=$(grep VERSION_MINOR version.properties | cut -d'=' -f2)
        PATCH=$(grep VERSION_PATCH version.properties | cut -d'=' -f2)
        NEW_PATCH=$((PATCH + 1))

        if [[ "$OSTYPE" == "darwin"* ]]; then
            sed -i '' "s/VERSION_PATCH=.*/VERSION_PATCH=$NEW_PATCH/" version.properties
        else
            sed -i "s/VERSION_PATCH=.*/VERSION_PATCH=$NEW_PATCH/" version.properties
        fi

        NEW_VERSION="${MAJOR}.${MINOR}.${NEW_PATCH}"
        echo -e "${GREEN}Version bumped to: ${NEW_VERSION}${NC}"
        echo ""
    fi
}

# Build release AAB
build_release() {
    echo -e "${YELLOW}Building release AAB...${NC}"
    ./gradlew bundleRelease --no-daemon
    if [[ $? -ne 0 ]]; then
        echo -e "${RED}Build failed! Aborting deploy.${NC}"
        exit 1
    fi

    AAB_PATH="app/build/outputs/bundle/release/app-release.aab"
    if [[ ! -f "$AAB_PATH" ]]; then
        echo -e "${RED}AAB not found at $AAB_PATH${NC}"
        exit 1
    fi

    AAB_SIZE=$(du -h "$AAB_PATH" | cut -f1)
    echo -e "${GREEN}AAB built successfully: $AAB_PATH ($AAB_SIZE)${NC}"
    echo ""
}

# Deploy to Play Store
deploy() {
    echo -e "${YELLOW}Deploying to ${TRACK} track...${NC}"

    case $TRACK in
        internal)
            fastlane android internal
            ;;
        alpha)
            fastlane android alpha
            ;;
        beta)
            fastlane android beta
            ;;
        production)
            echo -e "${RED}Production deployment requires manual confirmation.${NC}"
            read -p "Are you sure you want to deploy to PRODUCTION? (yes/no): " confirm
            if [[ "$confirm" != "yes" ]]; then
                echo "Deployment cancelled."
                exit 0
            fi
            fastlane android production
            ;;
        *)
            echo -e "${RED}Unknown track: $TRACK${NC}"
            echo "Valid tracks: internal, alpha, beta, production"
            exit 1
            ;;
    esac

    if [[ $? -ne 0 ]]; then
        echo -e "${RED}Deployment failed!${NC}"
        exit 1
    fi

    echo -e "${GREEN}Deployment successful!${NC}"
}

# Print summary
print_summary() {
    echo ""
    echo -e "${GREEN}========================================${NC}"
    echo -e "${GREEN}  Deployment Summary${NC}"
    echo -e "${GREEN}========================================${NC}"
    get_version
    echo -e "Track: ${TRACK}"
    echo -e "AAB: app/build/outputs/bundle/release/app-release.aab"
    echo -e "${GREEN}========================================${NC}"
}

# Main execution
main() {
    echo -e "Deploy target: ${YELLOW}${TRACK}${NC}"
    echo ""

    check_prerequisites
    get_version
    echo ""

    # Ask for confirmation
    read -p "Run tests before deploy? (y/n, default: y): " run_tests_confirm
    run_tests_confirm=${run_tests_confirm:-y}

    read -p "Bump version before deploy? (y/n, default: n): " bump_confirm
    bump_confirm=${bump_confirm:-n}

    echo ""

    if [[ "$run_tests_confirm" == "y" ]]; then
        run_tests
        run_lint
    fi

    if [[ "$bump_confirm" == "y" ]]; then
        BUMP_VERSION=true bump_version
    fi

    build_release
    deploy
    print_summary
}

# Help
if [[ "$1" == "-h" || "$1" == "--help" ]]; then
    echo "Paperless Scanner - Local Deploy Script"
    echo ""
    echo "Usage: ./scripts/local-deploy.sh [track]"
    echo ""
    echo "Tracks:"
    echo "  internal    Deploy to Internal Testing (default)"
    echo "  alpha       Deploy to Alpha track"
    echo "  beta        Deploy to Beta track"
    echo "  production  Deploy to Production (requires confirmation)"
    echo ""
    echo "Environment Variables:"
    echo "  KEYSTORE_FILE       Path to release keystore"
    echo "  KEYSTORE_PASSWORD   Keystore password"
    echo "  KEY_ALIAS           Key alias"
    echo "  KEY_PASSWORD        Key password"
    echo ""
    echo "Prerequisites:"
    echo "  - JDK 21"
    echo "  - Fastlane (gem install fastlane)"
    echo "  - Play Store service account key (fastlane/play-store-key.json)"
    exit 0
fi

main

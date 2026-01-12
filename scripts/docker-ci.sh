#!/bin/bash
# Docker-basiertes Android CI/CD fuer Paperless Scanner
#
# Usage: ./scripts/docker-ci.sh [command]
# Commands:
#   test     - Unit Tests ausfuehren
#   lint     - Lint Check ausfuehren
#   build    - Debug APK bauen
#   bundle   - Release AAB bauen
#   full     - Komplett CI Pipeline (test + lint + build)
#   deploy   - Zu Play Store deployen
#   shell    - Interaktive Shell im Container
#   clean    - Docker Volumes loeschen

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
COMPOSE_FILE="$PROJECT_DIR/docker-compose.ci.yml"

cd "$PROJECT_DIR"

# Check Docker
check_docker() {
    if ! command -v docker &> /dev/null; then
        echo -e "${RED}Error: Docker nicht installiert.${NC}"
        echo "Installiere Docker Desktop: https://www.docker.com/products/docker-desktop"
        exit 1
    fi

    if ! docker info &> /dev/null; then
        echo -e "${RED}Error: Docker Daemon laeuft nicht.${NC}"
        echo "Starte Docker Desktop."
        exit 1
    fi
}

# Show help
show_help() {
    echo -e "${BLUE}Paperless Scanner - Docker CI/CD${NC}"
    echo ""
    echo "Usage: ./scripts/docker-ci.sh [command]"
    echo ""
    echo "Commands:"
    echo "  test     Unit Tests ausfuehren (~2-3 Min)"
    echo "  lint     Lint Check ausfuehren (~1 Min)"
    echo "  build    Debug APK bauen (~2-3 Min)"
    echo "  bundle   Release AAB bauen (~3-4 Min)"
    echo "  full     Komplett CI Pipeline (~5-7 Min)"
    echo "  deploy   Zu Play Store deployen"
    echo "  shell    Interaktive Shell im Container"
    echo "  clean    Docker Volumes loeschen"
    echo ""
    echo "Beispiele:"
    echo "  ./scripts/docker-ci.sh test      # Tests ausfuehren"
    echo "  ./scripts/docker-ci.sh full      # Komplette CI"
    echo "  ./scripts/docker-ci.sh deploy    # Deployen"
}

# Run command
run_command() {
    local service=$1
    echo -e "${BLUE}========================================${NC}"
    echo -e "${BLUE}  Docker CI: $service${NC}"
    echo -e "${BLUE}========================================${NC}"
    echo ""

    START_TIME=$(date +%s)

    docker-compose -f "$COMPOSE_FILE" run --rm "ci-$service"

    END_TIME=$(date +%s)
    DURATION=$((END_TIME - START_TIME))

    echo ""
    echo -e "${GREEN}Fertig in ${DURATION}s${NC}"
}

# Main
check_docker

case "${1:-help}" in
    test)
        run_command "test"
        ;;
    lint)
        run_command "lint"
        ;;
    build)
        run_command "build"
        ;;
    bundle)
        run_command "bundle"
        ;;
    full)
        run_command "full"
        ;;
    deploy)
        echo -e "${YELLOW}Deploy zu Play Store...${NC}"
        echo ""
        read -p "Track (internal/alpha/beta/production): " track
        track=${track:-internal}

        docker-compose -f "$COMPOSE_FILE" run --rm ci-base sh -c "
            gem install fastlane &&
            ./gradlew bundleRelease --no-daemon &&
            fastlane android $track
        "
        ;;
    shell)
        echo -e "${BLUE}Starte interaktive Shell...${NC}"
        docker-compose -f "$COMPOSE_FILE" run --rm ci
        ;;
    clean)
        echo -e "${YELLOW}Loesche Docker Volumes...${NC}"
        docker volume rm paperless-gradle-cache paperless-android-cache 2>/dev/null || true
        echo -e "${GREEN}Volumes geloescht.${NC}"
        ;;
    help|--help|-h)
        show_help
        ;;
    *)
        echo -e "${RED}Unbekannter Befehl: $1${NC}"
        echo ""
        show_help
        exit 1
        ;;
esac

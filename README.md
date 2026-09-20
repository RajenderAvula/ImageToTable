# ImageToTable

A Compose Multiplatform desktop application for editable and adjustable interactive tables.

## Features
- **Sideways Column Shifts:** Move columns left and right with directional arrow controls (`◀` / `▶`).
- **Vertical Row Shifts:** Reorder rows up and down (`▲` / `▼`) and remove rows on the fly.
- **Inline Cell Editing:** Click any cell or header to modify text directly.
- **Clipboard Sync:** Intercept and parse TSV blocks from spreadsheets (Excel, Google Sheets) to paste multi-cell ranges seamlessly.

## Prerequisites
- JDK 17 or higher
- Gradle 8+

## Running the Application

```bash
# Run locally in desktop mode
./gradlew run

# Package distribution binary for your current operating system
./gradlew packageDistributionForCurrentOS

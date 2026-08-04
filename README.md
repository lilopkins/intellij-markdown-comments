# Markdown Comments

An IntelliJ Platform plugin that renders regular code comments as inline markdown in the editor.

## Features

- Renders line and block comments as markdown-style text directly in the editor.
- Preserves inline markdown formatting for emphasis (`**bold**`, `*italic*`) and inline code (`` `code` ``).
- Renders fenced code blocks using a monospaced style.
- Excludes doc comments (`/** ... */`) so native Javadoc/KDoc behavior remains intact.
- Works from PSI comments, so it applies across IntelliJ-supported languages with comment PSI.
- Adds a per-comment gutter button to swap that comment between raw text and markdown preview.
- Includes a toggle action: **Render Comments as Markdown** in editor context and View menu.
- Includes **Switch Comments to Display Mode** action (also on main toolbar) to restore preview mode for all comments in the current editor.

## How it works

The plugin keeps source text unchanged and applies an editor presentation layer:

- collapses the original comment region;
- inserts a block inlay at the comment offset;
- draws a markdown-rendered text view in that inlay.
- attaches a gutter button so each comment can be toggled back to raw text for editing.

## Development

```bash
./gradlew ktlintCheck
./gradlew ktlintFormat
./gradlew check
./gradlew runIde
```

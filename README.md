# Custom UI Anchors

A RuneLite plugin that allows you to customize overlay anchor positions with configurable X/Y offsets.

## Features

- **Per-anchor offset configuration**: Set horizontal and vertical offsets for each of the 7 overlay anchor positions
- **Visual anchor preview**: Toggle a debug overlay to see anchor points and their offsets
- **Automatic viewport handling**: Positions update automatically when the game window resizes

## Configuration

The plugin adds offset controls for each anchor point:

| Anchor Position | Description |
|-----------------|-------------|
| Top Left | Default position for InfoBoxes |
| Top Center | Center of the top edge |
| Top Right | Right side of viewport |
| Bottom Left | Left side of viewport bottom |
| Bottom Right | Right side of viewport bottom |
| Above Chatbox Right | Just above the chatbox (resizable mode) |
| Canvas Top Right | Absolute top-right of game canvas |

### Settings

- **Enable Anchor Customization**: Toggle the plugin on/off
- **Show Anchor Points**: Display visual indicators for debugging
- **X/Y Offsets**: Each anchor has horizontal and vertical offset settings (-500 to +500 pixels)

## Usage

1. Enable the plugin in RuneLite's plugin configuration
2. Open the Custom UI Anchors settings panel
3. Adjust X/Y offsets for the anchor points you want to move
4. Optionally enable "Show Anchor Points" to see a visual preview

## Installation

### From Plugin Hub (Recommended)
Search for "Custom UI Anchors" in the RuneLite Plugin Hub.

### Manual Installation
1. Clone this repository
2. Run `./gradlew build`
3. Copy the resulting JAR to your RuneLite plugins folder

## Development

```bash
# Build the and package the plugin
./gradlew shadowJar

# Run RuneLite with the plugin loaded (Developer mode)
./gradlew run
```

## License

BSD 2-Clause License

## Credits

Inspired by [GitHub Issue #8515](https://github.com/runelite/runelite/issues/8515) proposing customizable anchors.

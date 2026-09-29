# AnTTS

Android Accessibility Text-to-Speech reader.

AnTTS is an open-source Android Accessibility Service focused on reading the main content of pages while providing a minimal full-width control bar over the status-bar area.

## Planned features

- Main-content extraction through Android Accessibility Service
- TTS reading to the end of the page
- Full-width progress/control bar
- Black background with thin white monthly mobile-traffic figures
- Tap the bar to start/pause reading
- Horizontal swipes on the bar to move to previous/next text block
- Page scrolling to keep the spoken text focused
- Smooth continuous or page-by-page automatic scrolling
- Automatic reading of text entered through voice input
- Settings for overlay transparency, traffic accounting start date, and scrolling mode

## TalkBack extraction branch

The `feature/talkback-text-extraction` branch keeps the AnTTS interface and TTS pipeline while
using a compact TalkBack-inspired accessibility-tree extractor. It intentionally excludes
TalkBack UI, braille, image captioning, and native screen-understanding components so the APK
does not inherit TalkBack's large native payload or its second CPU ABI. See
[`NOTICE-TALKBACK.md`](NOTICE-TALKBACK.md) for scope and licensing notes.

## License

MIT

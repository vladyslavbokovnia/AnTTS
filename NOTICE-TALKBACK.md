# TalkBack-derived extraction layer

This branch contains a compact, independently adapted accessibility-tree extraction layer in
`app/src/main/java/com/antts/app/TalkBackTextExtractor.kt`. It follows the public TalkBack
approach of traversing visible accessibility nodes, selecting speakable leaf nodes, applying
role-aware filters, and ordering nodes in document order.

The original TalkBack project is copyright The Android Open Source Project and is licensed under
the Apache License, Version 2.0. The full license text is available in the upstream repository:
<https://github.com/google/talkback/blob/master/LICENSE>.

This branch does **not** copy or package TalkBack's application UI, braille modules, training,
image-captioning services, or native screen-understanding libraries. AnTTS's existing overlay,
TTS interface, input-field sentence reading, scrolling, and settings remain the application
surface.

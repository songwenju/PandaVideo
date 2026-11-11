# PandaVideo mobile

Kotlin Multiplatform + Compose Multiplatform Android/iOS application.
Follow docs/KMP-Compose-Multiplatform技术方案.md.

- Keep shared business and UI code in shared/src/commonMain.
- Keep Android/iOS framework types in their platform source sets.
- app owns China build variants; shared is a single-variant Android KMP library.
- Preserve cancellation; never log media URLs or search tokens.
- Check shared host tests, Android build/install and iOS framework compilation.
- Mobile connects directly to public catalog/online sources; never require frontend/NAS or add NAS download actions.
- Public SDK KDoc is English. User-facing text is Chinese.
- No Node, Expo, EAS or CocoaPods is required.

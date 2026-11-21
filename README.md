# Luxi Anime Extensions

First-party extension store for [Luxi](https://github.com/gishin05/Luxi), focused on **Anime and Donghua** sources.

## Store URL

Add this store URL in Luxi (**Browse → Extensions → Extension stores**):

```
https://raw.githubusercontent.com/gishin05/LUXI-AnimeExtension/repo/index.json
```

## Included Extensions

| Extension | Language | Package |
| --- | --- | --- |
| AniZone | All (`all`) | `eu.kanade.tachiyomi.animeextension.all.anizone` |
| AnimeHeaven | English (`en`) | `eu.kanade.tachiyomi.animeextension.en.animeheaven` |
| AnimeXin | English (`en`) | `eu.kanade.tachiyomi.animeextension.en.animexin` |
| AnimeKhor | English (`en`) | `eu.kanade.tachiyomi.animeextension.en.animekhor` |
| KickAssAnime | English (`en`) | `eu.kanade.tachiyomi.animeextension.en.kickassanime` |

## Repository Layout

- `core/` — shared `tachiyomi.animeextension` AndroidManifest template and base resources
- `lib/` — shared stream utilities (`playlist-utils`, `okru-extractor`, `dailymotion-extractor`)
- `lib-multisrc/` — reusable anime themes (`animestream`)
- `src/<lang>/<name>/` — individual extension modules
- `.github/workflows/` — PR verification and automatic `repo` branch publishing
- `.github/scripts/` — v2 (`index.json`) and legacy (`repo.json` / `index.min.json`) index generator

## Building Locally

```bash
JAVA_HOME=/home/xezar/android-studio/jbr ./gradlew assembleDebug
```

## GitHub Actions Signing Secrets

Configure the repository secrets using the same signing keystore as `LUXI-MangaExtension` (`SIGNING_KEY`, `ALIAS`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`, `SIGNING_FINGERPRINT` = `8bf8a0a410d83a1d23de76f89da5cfedf3f1d867f5f575a078c4e42a5cb9aee2`) so Luxi trusts all published anime extensions automatically without prompting.

## Policy

Extensions only access publicly exposed site endpoints and APIs. CAPTCHA-solving or anti-bot bypass scripts are not permitted.

## License

Licensed under the [Apache License 2.0](./LICENSE). Structure inspired by [aniyomiorg/extensions-source](https://github.com/aniyomiorg/extensions-source) and [keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source).
